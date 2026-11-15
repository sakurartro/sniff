//! Kotlin-facing API (UniFFI). Thin wrapper over sniff-core; one `App` per process.
use std::{collections::HashMap, path::PathBuf, sync::{Arc, Mutex}};
use sniff_core::{artists, keys::ApiKeys, recs::Recs, store::{Store, Track}, stream::{Mode, Streams}, tg::{LoginToken, PasswordToken, SignIn, Tg}};
use tokio::sync::Mutex as AsyncMutex;

uniffi::setup_scaffolding!();

#[derive(Debug, uniffi::Error)]
pub enum FfiError {
    Msg { msg: String },
}
impl std::fmt::Display for FfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let Self::Msg { msg } = self;
        f.write_str(msg)
    }
}
fn e(x: impl std::fmt::Display) -> FfiError {
    FfiError::Msg { msg: x.to_string() }
}
type R<T> = Result<T, FfiError>;

#[derive(Clone, uniffi::Enum)]
pub enum Login {
    Idle,
    Qr { link: String },
    Password,
    Done,
    Failed { msg: String },
}

#[derive(uniffi::Record)]
pub struct Chat {
    pub id: i64,
    pub title: String,
    pub selected: bool,
}

#[derive(uniffi::Record)]
pub struct TrackInfo {
    pub document_id: i64,
    pub title: String,
    pub performer: String,
    pub duration: u32,
    pub size: u64,
    pub chat_id: i64,
    pub message_id: i32,
    /// Individual artists of `performer` (feats split, spelling unified); empty when untagged.
    pub artists: Vec<String>,
    /// ❤: in the library
    pub liked: bool,
    /// came from recommendations (✨)
    pub rec: bool,
}

/// A song a source bot offers in search, not downloaded yet.
#[derive(uniffi::Record)]
pub struct BotHit {
    pub artist: String,
    pub title: String,
    /// seconds, 0 = unknown
    pub duration: u32,
}

#[derive(uniffi::Record)]
pub struct SyncStep {
    pub new_tracks: u32,
    pub done: bool,
}

fn info(t: Track, names: &HashMap<String, String>) -> TrackInfo {
    let artists = artists::split(&t.performer).into_iter().map(|a| names.get(&artists::key(&a)).cloned().unwrap_or(a)).collect();
    TrackInfo { document_id: t.document_id, title: t.title, performer: t.performer, duration: t.duration, size: t.size, chat_id: t.chat_id, message_id: t.message_id, artists, liked: t.liked, rec: t.rec }
}

#[derive(uniffi::Object)]
pub struct App {
    tg: Arc<Tg>,
    store: Arc<Store>,
    streams: Arc<Streams>,
    cache: PathBuf,
    code_token: AsyncMutex<Option<LoginToken>>,
    pw_token: Arc<AsyncMutex<Option<PasswordToken>>>,
    qr: Arc<Mutex<Login>>,
    qr_task: Mutex<Option<tokio::task::JoinHandle<()>>>,
    recs: AsyncMutex<Recs>,
}

/// `data_dir` holds session + library db, `cache_dir` holds downloaded audio.
#[uniffi::export(async_runtime = "tokio")]
pub async fn open_app(data_dir: String, cache_dir: String) -> R<Arc<App>> {
    let (data, cache) = (PathBuf::from(data_dir), PathBuf::from(cache_dir));
    std::fs::create_dir_all(&data).map_err(e)?;
    std::fs::create_dir_all(&cache).map_err(e)?;
    let keys = ApiKeys::resolve(None).map_err(e)?;
    let tg = Tg::connect(data.join("tg.session"), keys).await.map_err(e)?;
    let store = Arc::new(Store::open(data.join("library.sqlite")).await.map_err(e)?);
    let tg = Arc::new(tg);
    Ok(Arc::new(App {
        streams: Streams::new(tg.clone(), store.clone(), cache.clone()),
        tg,
        store,
        cache,
        code_token: AsyncMutex::new(None),
        pw_token: Arc::new(AsyncMutex::new(None)),
        qr: Arc::new(Mutex::new(Login::Idle)),
        qr_task: Mutex::new(None),
        recs: AsyncMutex::new(Recs::default()),
    }))
}

#[uniffi::export(async_runtime = "tokio")]
impl App {
    pub async fn is_authorized(&self) -> R<bool> {
        self.tg.is_authorized().await.map_err(e)
    }

    pub async fn request_code(&self, phone: String) -> R<()> {
        *self.code_token.lock().await = Some(self.tg.request_code(&phone).await.map_err(e)?);
        Ok(())
    }

    /// Returns Done or Password (then call `submit_password`).
    pub async fn submit_code(&self, code: String) -> R<Login> {
        let guard = self.code_token.lock().await;
        let token = guard.as_ref().ok_or_else(|| e("request a code first"))?;
        match self.tg.sign_in(token, &code).await.map_err(e)? {
            SignIn::Done => Ok(Login::Done),
            SignIn::PasswordRequired(t) => {
                *self.pw_token.lock().await = Some(t);
                Ok(Login::Password)
            }
        }
    }

    pub async fn submit_password(&self, password: String) -> R<()> {
        let t = self.pw_token.lock().await.take().ok_or_else(|| e("no password requested"))?;
        self.tg.check_password(t, &password).await.map_err(e)
    }

    /// Starts QR login in the background; poll `qr_state`. Restarting replaces a previous attempt.
    pub async fn start_qr(&self) {
        self.stop_qr();
        *self.qr.lock().unwrap() = Login::Idle;
        let (tg, st, pw) = (self.tg.clone(), self.qr.clone(), self.pw_token.clone());
        let h = tokio::spawn(async move {
            let st2 = st.clone();
            let r = tg.login_by_token(move |l| *st2.lock().unwrap() = Login::Qr { link: l.into() }).await;
            let next = match r {
                Ok(SignIn::Done) => Login::Done,
                Ok(SignIn::PasswordRequired(t)) => {
                    *pw.lock().await = Some(t);
                    Login::Password
                }
                Err(err) => Login::Failed { msg: err.to_string() },
            };
            *st.lock().unwrap() = next;
        });
        *self.qr_task.lock().unwrap() = Some(h);
    }

    pub async fn refresh_chats(&self) -> R<()> {
        self.tg.refresh_chats(&self.store).await.map_err(e)
    }

    pub async fn chats(&self) -> R<Vec<Chat>> {
        Ok(self.store.chats().await.map_err(e)?.into_iter().map(|(id, title, selected)| Chat { id, title, selected }).collect())
    }

    pub async fn set_selected(&self, id: i64, selected: bool) -> R<()> {
        self.store.set_selected(id, selected).await.map_err(e)?;
        Ok(())
    }

    pub async fn selected_chats(&self) -> R<Vec<i64>> {
        self.store.selected_chats().await.map_err(e)
    }

    /// One backfill page; call repeatedly per chat until `done`.
    pub async fn sync_step(&self, chat_id: i64, limit: u32) -> R<SyncStep> {
        let (n, done) = self.tg.backfill_page(&self.store, chat_id, limit as usize).await.map_err(e)?;
        Ok(SyncStep { new_tracks: n as u32, done })
    }

    pub async fn tracks(&self, query: String) -> R<Vec<TrackInfo>> {
        let names = artists::canonical(self.store.performers().await.map_err(e)?.iter().map(String::as_str));
        Ok(self.store.tracks(&query).await.map_err(e)?.into_iter().map(|t| info(t, &names)).collect())
    }

    /// Starts `@username` and selects it as a recommendation source; errors are user-facing (not found, not a bot).
    pub async fn connect_bot(&self, username: String) -> R<()> {
        self.tg.connect_bot(&self.store, &username).await.map_err(e)?;
        Ok(())
    }

    /// ❤ on/off: liked tracks are the library and seed recommendations. Never touches Telegram.
    pub async fn set_liked(&self, document_id: i64, liked: bool) -> R<()> {
        self.store.set_liked(document_id, liked).await.map_err(e)
    }

    /// Is any selected chat a usable source bot?
    pub async fn has_rec_source(&self) -> R<bool> {
        Ok(!sniff_core::recs::sources(&self.tg, &self.store).await.map_err(e)?.is_empty())
    }

    /// One new song similar to `seeds` (document ids, most recent last), fetched from a selected source bot
    /// into the library; None if nothing matched. Slow (a bot download), call ahead of time.
    pub async fn recommend(&self, seeds: Vec<i64>) -> R<Option<TrackInfo>> {
        let mut tracks = vec![];
        for id in seeds {
            tracks.extend(self.store.track(id).await.map_err(e)?);
        }
        let Some(t) = self.recs.lock().await.next(&self.tg, &self.store, &tracks).await.map_err(e)? else { return Ok(None) };
        let names = artists::canonical(self.store.performers().await.map_err(e)?.iter().map(String::as_str));
        Ok(Some(info(t, &names)))
    }

    /// Up to `n` songs the first inline/deeplink source bot finds for `query`; empty if there is no such bot.
    pub async fn bot_search(&self, query: String, n: u32) -> R<Vec<BotHit>> {
        let bots = sniff_core::recs::sources(&self.tg, &self.store).await.map_err(e)?;
        let Some((chat, _)) = bots.iter().find(|(_, k)| k != "text") else { return Ok(vec![]) };
        let hits = self.tg.search_bot(*chat, &query, n as usize).await.map_err(e)?;
        Ok(hits.into_iter().map(|c| BotHit { artist: c.artist, title: c.title, duration: c.duration }).collect())
    }

    /// Fetches a search hit from the source bots into the db, not liked (❤ adds it to the library). Slow.
    pub async fn bot_get(&self, hit: BotHit) -> R<Option<TrackInfo>> {
        let c = sniff_core::recs::Cand { artist: hit.artist, title: hit.title, duration: hit.duration };
        for (chat, kind) in sniff_core::recs::sources(&self.tg, &self.store).await.map_err(e)? {
            if let Ok(Some(t)) = self.tg.fetch(&self.store, chat, &kind, &c).await {
                self.store.set_liked(t.document_id, false).await.map_err(e)?;
                let names = artists::canonical(self.store.performers().await.map_err(e)?.iter().map(String::as_str));
                return Ok(Some(info(Track { liked: false, ..t }, &names)));
            }
        }
        Ok(None)
    }

    /// Starts downloading the track (`foreground` = it is the one playing, else it is a prefetch) and returns its size in bytes.
    pub async fn stream_start(&self, document_id: i64, foreground: bool) -> R<u64> {
        self.streams.size(document_id, if foreground { Mode::Fg } else { Mode::Next }).await.map_err(e)
    }

    /// Bytes `[offset, offset+len)` of the track, waiting for them to arrive; may be shorter than `len`, empty at the end.
    pub async fn stream_read(&self, document_id: i64, offset: u64, len: u32) -> R<Vec<u8>> {
        self.streams.read(document_id, offset, len as usize).await.map_err(e)
    }

    /// Resolves with the local file path once the track is completely downloaded.
    pub async fn await_download(&self, document_id: i64) -> R<String> {
        Ok(self.streams.finished(document_id).await.map_err(e)?.to_string_lossy().into_owned())
    }

    /// Telegram's thumbnail for the track, saved as `<cache>/covers/<id>.jpg`; None if it has none.
    pub async fn cover(&self, document_id: i64) -> R<Option<String>> {
        let dir = self.cache.join("covers");
        std::fs::create_dir_all(&dir).map_err(e)?;
        let p = self.tg.cover(&self.store, document_id, &dir).await.map_err(e)?;
        Ok(p.map(|p| p.to_string_lossy().into_owned()))
    }
}

#[uniffi::export]
impl App {
    /// Path of the fully downloaded file, if the track is already in the cache.
    pub fn cached_path(&self, document_id: i64) -> Option<String> {
        self.streams.cached(document_id).map(|p| p.to_string_lossy().into_owned())
    }

    pub fn qr_state(&self) -> Login {
        self.qr.lock().unwrap().clone()
    }

    pub fn stop_qr(&self) {
        if let Some(h) = self.qr_task.lock().unwrap().take() {
            h.abort();
        }
    }
}
