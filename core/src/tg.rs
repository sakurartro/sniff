use crate::keys::ApiKeys;
use crate::store::{Store, Track};
use anyhow::{anyhow, Result};
use grammers_client::{media::Media, Client, SignInError};
use grammers_mtsender::SenderPool;
use grammers_session::{storages::SqliteSession, types::PeerId, Session};
use grammers_mtsender::SenderPoolFatHandle;
use std::{path::Path, sync::Arc, time::Duration};

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
    _pool: tokio::task::JoinHandle<()>,
}

impl Tg {
    pub async fn connect(session_file: impl AsRef<Path>, keys: ApiKeys) -> Result<Self> {
        let session = Arc::new(SqliteSession::open(session_file.as_ref()).await?);
        let SenderPool { runner, handle, .. } = SenderPool::new(Arc::clone(&session), keys.id);
        let client = Client::new(handle.clone());
        let pool = tokio::spawn(async move {
            let _ = runner.run().await;
        });
        Ok(Self { client, keys, session, handle, _pool: pool })
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
            if let Some(Media::Document(d)) = m.media() {
                if !d.mime_type().is_some_and(|t| t.starts_with("audio/")) {
                    continue;
                }
                let t = Track {
                    document_id: d.id(),
                    title: d.audio_title().or(d.name()).unwrap_or("Unknown").to_string(),
                    performer: d.performer().unwrap_or("").to_string(),
                    duration: d.duration().unwrap_or(0.0) as u32,
                    size: d.size().unwrap_or(0) as u64,
                    chat_id,
                    message_id: m.id(),
                };
                new += store.add_track(&t).await? as usize;
            }
            // shortcut: fixed 300ms spacing, replace with real FloodWait handling when syncing many chats
            tokio::time::sleep(Duration::from_millis(300)).await;
        }
        Ok((new, false))
    }

    pub async fn download(&self, store: &Store, document_id: i64, dir: &Path) -> Result<std::path::PathBuf> {
        let t = store.track(document_id).await?.ok_or_else(|| anyhow!("unknown track"))?;
        let path = dir.join(format!("{document_id}.audio"));
        if path.exists() {
            return Ok(path);
        }
        let peer = self.peer_ref(t.chat_id).await?;
        let m = self.client.get_messages_by_id(peer, &[t.message_id]).await?.into_iter().next().flatten().ok_or_else(|| anyhow!("message gone"))?;
        let media = m.media().ok_or_else(|| anyhow!("message has no media"))?;
        let tmp = path.with_extension("part");
        self.client.download_media(&media, &tmp).await?;
        std::fs::rename(&tmp, &path)?;
        Ok(path)
    }

    async fn peer_ref(&self, chat_id: i64) -> Result<grammers_session::types::PeerRef> {
        let id = PeerId::from_bot_api_dialog_id(chat_id).ok_or_else(|| anyhow!("bad chat id"))?;
        // shortcut: linear dialog scan per call, cache PeerRefs when this shows up in profiles
        let mut dialogs = self.client.iter_dialogs();
        while let Some(d) = dialogs.next().await? {
            if d.peer().id() == id {
                return Ok(d.peer_ref());
            }
        }
        Err(anyhow!("chat {chat_id} not found in dialogs"))
    }
}
