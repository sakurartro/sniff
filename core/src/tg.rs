use crate::keys::ApiKeys;
use crate::recs::{matches, Cand};
use crate::store::{Store, Track};
use anyhow::{anyhow, Result};
use grammers_client::{client::InlineResult, media::{Media, PhotoSize}, message::Message, peer::Peer, tl, Client, SignInError};
use grammers_mtsender::{ConnectionParams, SenderPool};
use grammers_session::{storages::SqliteSession, types::PeerId, Session};
use grammers_mtsender::SenderPoolFatHandle;
use std::{collections::HashMap, path::Path, sync::{Arc, Mutex}, time::Duration};

pub use grammers_client::client::LoginToken;
pub use grammers_client::client::PasswordToken;

pub enum SignIn {
    Done,
    PasswordRequired(PasswordToken),
}

pub struct Tg {
    client: Client,
    keys: ApiKeys,
    session: Arc<SqliteSession>,
    handle: SenderPoolFatHandle,
    peers: Mutex<HashMap<i64, grammers_session::types::PeerRef>>,
    _pool: tokio::task::JoinHandle<()>,
}

impl Tg {
    pub async fn connect(session_file: impl AsRef<Path>, keys: ApiKeys) -> Result<Self> {
        let session = Arc::new(SqliteSession::open(session_file.as_ref()).await?);
        let SenderPool { runner, handle, .. } = SenderPool::with_configuration(
            Arc::clone(&session),
            keys.id,
            // Telegram silently withholds login codes for the default library fingerprint; explicit params fix it.
            ConnectionParams {
                device_model: "Linux".into(),
                system_version: "Arch Linux".into(),
                app_version: "1.0".into(),
                lang_code: "ru".into(),
                system_lang_code: "ru-RU".into(),
                ..Default::default()
            },
        );
        let client = Client::new(handle.clone());
        let pool = tokio::spawn(async move {
            let _ = runner.run().await;
        });
        Ok(Self { client, keys, session, handle, peers: Default::default(), _pool: pool })
    }

    pub async fn is_authorized(&self) -> Result<bool> {
        Ok(self.client.is_authorized().await?)
    }

    pub async fn request_code(&self, phone: &str) -> Result<LoginToken> {
        Ok(self.client.request_login_code(phone, &self.keys.hash).await?)
    }

    /// Debug: send a login code and report how Telegram says it was delivered (the token is discarded).
    pub async fn probe_code(&self, phone: &str) -> Result<String> {
        use grammers_client::tl;
        let req = tl::functions::auth::SendCode {
            phone_number: phone.into(),
            api_id: self.keys.id,
            api_hash: self.keys.hash.clone(),
            settings: tl::types::CodeSettings { allow_flashcall: false, current_number: false, allow_app_hash: false, allow_missed_call: false, allow_firebase: false, logout_tokens: None, token: None, app_sandbox: None, unknown_number: false }.into(),
        };
        let r = self.client.invoke(&req).await;
        Ok(match r {
            Ok(tl::enums::auth::SentCode::Code(c)) => format!("type={:?} next={:?} timeout={:?}", c.r#type, c.next_type, c.timeout),
            Ok(other) => format!("{other:?}"),
            Err(e) => format!("error: {e:?}"),
        })
    }

    /// Log in by approving from an already logged-in Telegram app (no SMS/app code needed).
    /// `on_link` gets a `tg://login?token=...` link each time the token changes: show it as a QR
    /// (other device) or open it directly on the same phone.
    pub async fn login_by_token(&self, on_link: impl Fn(&str)) -> Result<SignIn> {
        use base64::{engine::general_purpose::URL_SAFE_NO_PAD as B64, Engine};
        use grammers_client::tl::{self, enums::auth::LoginToken as LT};
        let export = tl::functions::auth::ExportLoginToken { api_id: self.keys.id, api_hash: self.keys.hash.clone(), except_ids: vec![] };
        let mut last = vec![];
        loop {
            match self.client.invoke(&export).await {
                Ok(LT::Token(t)) => {
                    if t.token != last {
                        on_link(&format!("tg://login?token={}", B64.encode(&t.token)));
                        last = t.token;
                    }
                }
                Ok(LT::MigrateTo(m)) => {
                    // account lives on another DC: move home there and import the token
                    let old = self.session.home_dc_id()?;
                    self.handle.disconnect_from_dc(old);
                    self.session.set_home_dc_id(m.dc_id).await?;
                    if let LT::Success(_) = self.client.invoke(&tl::functions::auth::ImportLoginToken { token: m.token }).await? {
                        return Ok(SignIn::Done);
                    }
                }
                Ok(LT::Success(_)) => return Ok(SignIn::Done),
                Err(grammers_client::InvocationError::Rpc(e)) if e.name == "SESSION_PASSWORD_NEEDED" => {
                    let p = self.client.invoke(&tl::functions::account::GetPassword {}).await?;
                    let tl::enums::account::Password::Password(p) = p;
                    return Ok(SignIn::PasswordRequired(PasswordToken::new(p)));
                }
                Err(e) => return Err(anyhow!("token login failed: {e}")),
            }
            tokio::time::sleep(Duration::from_secs(3)).await;
        }
    }

    pub async fn sign_in(&self, token: &LoginToken, code: &str) -> Result<SignIn> {
        match self.client.sign_in(token, code).await {
            Ok(_) => Ok(SignIn::Done),
            Err(SignInError::PasswordRequired(t)) => Ok(SignIn::PasswordRequired(t)),
            Err(e) => Err(anyhow!("sign in failed: {e}")),
        }
    }

    pub async fn check_password(&self, token: PasswordToken, password: &str) -> Result<()> {
        self.client.check_password(token, password.as_bytes()).await.map_err(|e| anyhow!("{e}"))?;
        Ok(())
    }

    /// Refresh the chat list in the store (keeps `selected` flags).
    pub async fn refresh_chats(&self, store: &Store) -> Result<()> {
        let mut dialogs = self.client.iter_dialogs();
        while let Some(d) = dialogs.next().await? {
            let p = d.peer();
            if let Some(id) = p.id().bot_api_dialog_id() {
                store.upsert_chat(id, p.name().unwrap_or("?")).await?;
            }
        }
        Ok(())
    }

    /// Scan one backfill page (up to `limit` audio messages, newest to oldest, below the stored cursor).
    /// Returns (new tracks, reached the end of history).
    pub async fn backfill_page(&self, store: &Store, chat_id: i64, limit: usize) -> Result<(usize, bool)> {
        let peer = self.peer_ref(chat_id).await?;
        let cursor = store.cursor(chat_id).await?;
        let mut it = self
            .client
            .search_messages(peer)
            .filter(grammers_client::tl::enums::MessagesFilter::InputMessagesFilterMusic)
            .offset_id(cursor);
        let (mut seen, mut new) = (0, 0);
        while seen < limit {
            let Some(m) = it.next().await? else {
                return Ok((new, true));
            };
            seen += 1;
            // cursor moves per message so an interrupted run resumes without rescanning
            store.set_cursor(chat_id, m.id()).await?;
            if let Some(t) = track_of(&m, chat_id) {
                new += store.add_track(&t).await? as usize;
            }
            // shortcut: fixed 300ms spacing, replace with real FloodWait handling when syncing many chats
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        Ok((new, false))
    }

    /// Resolve `@username` to a bot peer.
    async fn bot(&self, username: &str) -> Result<grammers_session::types::PeerRef> {
        let p = self.client.resolve_username(username.trim_start_matches('@')).await?.ok_or_else(|| anyhow!("no such user: {username}"))?;
        if !matches!(&p, Peer::User(u) if u.is_bot()) {
            return Err(anyhow!("{username} is not a bot"));
        }
        p.to_ref().await.map_err(|e| anyhow!("{e}"))?.ok_or_else(|| anyhow!("cannot reference {username}"))
    }

    /// True if the bot answers inline queries with audio (the only mode supported so far).
    pub async fn probe_bot(&self, username: &str) -> Result<bool> {
        let mut it = self.client.inline_query(self.bot(username).await?, "Imagine Dragons Believer");
        for _ in 0..5 {
            match it.next().await? {
                Some(r) if audio_meta(&r).is_some() => return Ok(true),
                Some(_) => {}
                None => break,
            }
        }
        Ok(false)
    }

    /// Ask the bot inline for the candidate; a result whose tags match is sent to Saved Messages and
    /// indexed there, so it becomes an ordinary library track. None if the bot has no match.
    pub async fn fetch_via_bot(&self, store: &Store, username: &str, c: &Cand) -> Result<Option<Track>> {
        let bot = self.bot(username).await?;
        let me = self.client.get_me().await?;
        let me_ref = me.to_ref().await.map_err(|e| anyhow!("{e}"))?.ok_or_else(|| anyhow!("cannot reference self"))?;
        let chat_id = me.id().bot_api_dialog_id().ok_or_else(|| anyhow!("bad self id"))?;
        let mut it = self.client.inline_query(bot, &format!("{} {}", c.artist, c.title));
        for _ in 0..10 {
            let Some(r) = it.next().await? else { break };
            let Some((performer, title, dur)) = audio_meta(&r) else { continue };
            if !matches(c, &performer, &title, dur) {
                continue;
            }
            r.send(me_ref.clone()).await?;
            let m = self.client.iter_messages(me_ref.clone()).limit(1).next().await?.ok_or_else(|| anyhow!("sent audio not found"))?;
            let t = track_of(&m, chat_id).ok_or_else(|| anyhow!("bot result was not an audio file"))?;
            store.upsert_chat(chat_id, "Saved Messages").await?;
            store.set_selected(chat_id, true).await?;
            store.add_track(&t).await?;
            return Ok(Some(t));
        }
        Ok(None)
    }

    pub async fn media_of(&self, store: &Store, document_id: i64) -> Result<Media> {
        let t = store.track(document_id).await?.ok_or_else(|| anyhow!("unknown track"))?;
        let peer = self.peer_ref(t.chat_id).await?;
        let m = self.client.get_messages_by_id(peer, &[t.message_id]).await?.into_iter().next().flatten().ok_or_else(|| anyhow!("message gone"))?;
        m.media().ok_or_else(|| anyhow!("message has no media"))
    }

    /// Chunk number `idx` (512 KiB each) of the document; the last one may be shorter.
    pub async fn chunk(&self, media: &Media, idx: i32) -> Result<Vec<u8>> {
        let b = self.client.iter_download(media).skip_chunks(idx).next().await?;
        b.ok_or_else(|| anyhow!("chunk {idx} is past the end"))
    }

    pub async fn download(&self, store: &Store, document_id: i64, dir: &Path) -> Result<std::path::PathBuf> {
        let path = dir.join(format!("{document_id}.audio"));
        if path.exists() {
            return Ok(path);
        }
        let media = self.media_of(store, document_id).await?;
        let tmp = path.with_extension("part");
        self.client.download_media(&media, &tmp).await?;
        std::fs::rename(&tmp, &path)?;
        Ok(path)
    }

    /// Telegram's own thumbnail of the audio document (usually the embedded album art), saved as `<id>.jpg`.
    /// None if the document has no usable thumb.
    /// shortcut: stripped/path thumbs (tiny blurred previews) are ignored, add a fallback if too many tracks have only those.
    pub async fn cover(&self, store: &Store, document_id: i64, dir: &Path) -> Result<Option<std::path::PathBuf>> {
        let path = dir.join(format!("{document_id}.jpg"));
        if path.exists() {
            return Ok(Some(path));
        }
        let Media::Document(d) = self.media_of(store, document_id).await? else {
            return Ok(None);
        };
        let best = d
            .thumbs()
            .into_iter()
            .filter(|t| matches!(t, PhotoSize::Size(_) | PhotoSize::Cached(_) | PhotoSize::Progressive(_)))
            .max_by_key(PhotoSize::size);
        let Some(thumb) = best else {
            return Ok(None);
        };
        let tmp = path.with_extension("part");
        self.client.download_media(&thumb, &tmp).await?;
        std::fs::rename(&tmp, &path)?;
        Ok(Some(path))
    }

    async fn peer_ref(&self, chat_id: i64) -> Result<grammers_session::types::PeerRef> {
        let id = PeerId::from_bot_api_dialog_id(chat_id).ok_or_else(|| anyhow!("bad chat id"))?;
        if let Some(p) = self.peers.lock().unwrap().get(&chat_id) {
            return Ok(p.clone());
        }
        // one dialog scan per chat per app run; all dialogs seen on the way are cached too
        let mut dialogs = self.client.iter_dialogs();
        let mut found = None;
        while let Some(d) = dialogs.next().await? {
            let r = d.peer_ref();
            if d.peer().id() == id {
                found = Some(r.clone());
            }
            if let Some(cid) = d.peer().id().bot_api_dialog_id() {
                self.peers.lock().unwrap().insert(cid, r);
            }
            if found.is_some() {
                return Ok(found.unwrap());
            }
        }
        Err(anyhow!("chat {chat_id} not found in dialogs"))
    }
}

fn track_of(m: &Message, chat_id: i64) -> Option<Track> {
    let Some(Media::Document(d)) = m.media() else { return None };
    if !d.mime_type().is_some_and(|t| t.starts_with("audio/")) {
        return None;
    }
    Some(Track {
        document_id: d.id(),
        title: d.audio_title().or(d.name()).unwrap_or("Unknown").to_string(),
        performer: d.performer().unwrap_or("").to_string(),
        duration: d.duration().unwrap_or(0.0) as u32,
        size: d.size().unwrap_or(0) as u64,
        chat_id,
        message_id: m.id(),
    })
}

/// (performer, title, duration) of an inline result if it is audio. Cached-media results carry real tags;
/// plain "audio" results follow the Telegram convention title = song, description = artist.
fn audio_meta(r: &InlineResult) -> Option<(String, String, Option<u32>)> {
    use tl::{enums::{BotInlineResult, Document, DocumentAttribute}, types};
    match &r.raw {
        BotInlineResult::BotInlineMediaResult(m) => {
            let Some(Document::Document(d)) = &m.document else { return None };
            d.attributes.iter().find_map(|a| match a {
                DocumentAttribute::Audio(types::DocumentAttributeAudio { title, performer, duration, .. }) => {
                    Some((performer.clone().unwrap_or_default(), title.clone().unwrap_or_default(), Some(*duration as u32)))
                }
                _ => None,
            })
        }
        BotInlineResult::Result(x) if x.r#type == "audio" => Some((x.description.clone().unwrap_or_default(), x.title.clone().unwrap_or_default(), None)),
        _ => None,
    }
}
