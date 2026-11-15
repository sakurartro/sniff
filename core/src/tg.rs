use crate::keys::ApiKeys;
use crate::recs::{self, Cand};
use crate::store::{Store, Track};
use anyhow::{anyhow, Result};
use grammers_client::{media::{Media, PhotoSize}, message::Message, peer::Peer, tl, Client, SignInError};
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

    /// Source bot kind of a chat: "inline" (results carry audio), "deeplink" (result cards link to `/start <payload>`),
    /// "text" (send a query, get audio or song buttons back), "" (not a bot).
    pub async fn detect_bot(&self, chat_id: i64) -> Result<String> {
        let id = PeerId::from_bot_api_dialog_id(chat_id).ok_or_else(|| anyhow!("bad chat id"))?;
        let mut dialogs = self.client.iter_dialogs();
        while let Some(d) = dialogs.next().await? {
            if d.peer().id() != id {
                continue;
            }
            let Peer::User(u) = d.peer() else { return Ok(String::new()) };
            let tl::enums::User::User(raw) = &u.raw else { return Ok(String::new()) };
            if !raw.bot {
                return Ok(String::new());
            }
            if raw.bot_inline_placeholder.is_none() {
                return Ok("text".into());
            }
            let mut it = self.client.inline_query(d.peer_ref(), "Imagine Dragons Believer");
            let mut kind = "text";
            for _ in 0..5 {
                let Some(r) = it.next().await? else { break };
                let card = Card::of(&r.raw);
                if card.audio.is_some() {
                    return Ok("inline".into());
                }
                if card.payload.is_some() {
                    kind = "deeplink";
                }
            }
            return Ok(kind.into());
        }
        Err(anyhow!("chat {chat_id} not found in dialogs"))
    }

    /// Get the song from a source bot; the audio lands in the bot chat and is added to the library.
    /// None if the bot has no matching song.
    pub async fn fetch(&self, store: &Store, chat_id: i64, kind: &str, c: &Cand) -> Result<Option<Track>> {
        let peer = self.peer_ref(chat_id).await?;
        let last = self.client.iter_messages(peer.clone()).limit(1).next().await?.map_or(0, |m| m.id());
        let query = format!("{} {}", c.artist, c.title);
        let t = match kind {
            "inline" | "deeplink" => {
                let mut it = self.client.inline_query(peer.clone(), &query);
                let mut hit = None;
                for _ in 0..10 {
                    let Some(r) = it.next().await? else { break };
                    let card = Card::of(&r.raw);
                    let ok = match &card.audio {
                        Some((p, t, d)) => recs::matches(c, p, t, Some(*d)),
                        None => recs::matches(c, &card.artist, &card.title, None),
                    };
                    if ok && (card.audio.is_some() || card.payload.is_some()) {
                        hit = Some((r, card));
                        break;
                    }
                }
                let Some((r, card)) = hit else { return Ok(None) };
                match card.payload {
                    Some(p) if card.audio.is_none() => self.client.send_message(peer.clone(), format!("/start {p}")).await.map(drop)?,
                    _ => r.send(peer.clone()).await?,
                }
                // downloader bots fetch the file first; 45s covers a slow Deezer rip
                self.wait_audio(&peer, chat_id, last, 45, |_| true).await?
            }
            "text" => {
                self.client.send_message(peer.clone(), query.as_str()).await?;
                let mut button = None;
                let got = self
                    .wait_audio(&peer, chat_id, last, 15, |m| {
                        if button.is_none() {
                            button = m.reply_markup().and_then(|k| song_button(&k, c)).map(|d| (m.id(), d));
                        }
                        button.is_none()
                    })
                    .await?;
                match (got, button) {
                    (Some(t), _) => Some(t),
                    (None, Some((msg_id, data))) => {
                        let last = self.client.iter_messages(peer.clone()).limit(1).next().await?.map_or(0, |m| m.id());
                        self.client
                            .invoke(&tl::functions::messages::GetBotCallbackAnswer { game: false, peer: (&peer).into(), msg_id, data: Some(data), password: None })
                            .await?;
                        self.wait_audio(&peer, chat_id, last, 30, |_| true).await?
                    }
                    _ => None,
                }
            }
            _ => return Ok(None),
        };
        // text bots answer with whatever they found, so check the tags too
        let Some(t) = t.filter(|t| kind != "text" || recs::matches(c, &t.performer, &t.title, Some(t.duration))) else { return Ok(None) };
        store.add_track(&t).await?;
        Ok(Some(t))
    }

    /// Polls the chat for an incoming audio newer than `after`, up to `secs`. `keep_waiting` sees every new
    /// incoming message and can stop the wait early by returning false.
    async fn wait_audio(&self, peer: &grammers_session::types::PeerRef, chat_id: i64, after: i32, secs: u64, mut keep_waiting: impl FnMut(&Message) -> bool) -> Result<Option<Track>> {
        let mut seen = after;
        for _ in 0..secs / 3 {
            tokio::time::sleep(Duration::from_secs(3)).await;
            let mut it = self.client.iter_messages(peer.clone()).limit(10);
            let mut fresh = vec![];
            while let Some(m) = it.next().await? {
                if m.id() <= seen {
                    break;
                }
                fresh.push(m);
            }
            // oldest first, so the first audio after our request wins
            for m in fresh.into_iter().rev() {
                seen = m.id();
                if m.outgoing() {
                    continue;
                }
                if let Some(t) = track_of(&m, chat_id) {
                    return Ok(Some(t));
                }
                if !keep_waiting(&m) {
                    return Ok(None);
                }
            }
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

/// What an inline result offers: its card text, real audio tags if it carries a file, a `/start` payload if it links to one.
struct Card {
    artist: String,
    title: String,
    audio: Option<(String, String, u32)>,
    payload: Option<String>,
}

impl Card {
    fn of(r: &tl::enums::BotInlineResult) -> Self {
        use tl::enums::{BotInlineMessage, BotInlineResult, Document, DocumentAttribute};
        let (title, desc, doc, msg) = match r {
            BotInlineResult::Result(x) => (&x.title, &x.description, None, &x.send_message),
            BotInlineResult::BotInlineMediaResult(x) => (&x.title, &x.description, x.document.as_ref(), &x.send_message),
        };
        let audio = match doc {
            Some(Document::Document(d)) => d.attributes.iter().find_map(|a| match a {
                DocumentAttribute::Audio(a) if !a.voice => Some((a.performer.clone().unwrap_or_default(), a.title.clone().unwrap_or_default(), a.duration.max(0) as u32)),
                _ => None,
            }),
            _ => None,
        };
        let markup = match msg {
            BotInlineMessage::Text(m) => m.reply_markup.as_ref(),
            BotInlineMessage::MediaAuto(m) => m.reply_markup.as_ref(),
            _ => None,
        };
        let payload = markup.and_then(|k| {
            buttons(k).into_iter().find_map(|(_, t)| match t {
                tl::enums::InlineButtonType::Url(u) => recs::start_payload(&u.url),
                _ => None,
            })
        });
        let (artist, title) = recs::card_meta(title.as_deref().unwrap_or(""), desc.as_deref().unwrap_or(""));
        Self { artist, title, audio, payload }
    }
}

fn buttons(k: &tl::enums::ReplyMarkup) -> Vec<(String, tl::enums::InlineButtonType)> {
    let tl::enums::ReplyMarkup::ReplyInlineMarkup(m) = k else { return vec![] };
    m.rows
        .iter()
        .flat_map(|tl::enums::KeyboardInlineButtonRow::Row(r)| &r.buttons)
        .map(|tl::enums::KeyboardInlineButton::Button(b)| (b.text.clone(), b.r#type.clone()))
        .collect()
}

/// Callback data of the button whose caption names the song.
fn song_button(k: &tl::enums::ReplyMarkup, c: &Cand) -> Option<Vec<u8>> {
    buttons(k).into_iter().find_map(|(text, t)| match t {
        tl::enums::InlineButtonType::Callback(cb) if !cb.requires_password && recs::button_matches(c, &text) => Some(cb.data),
        _ => None,
    })
}
