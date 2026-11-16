//! Streaming downloads. A track grows in `<id>.part` (+ `<id>.map`, one bit per 512 KiB chunk) while the
//! player reads it through `Streams::read`; when every chunk is in, it is renamed to `<id>.audio`.
//! Bytes are Telegram's originals, so there is no quality loss.
use crate::{store::Store, tg::Tg};
use anyhow::{anyhow, bail, Result};
use grammers_client::media::Media;
use std::{
    collections::{HashMap, HashSet, VecDeque},
    fs::{File, OpenOptions},
    os::unix::fs::FileExt,
    path::PathBuf,
    sync::{atomic::{AtomicI64, AtomicU64, Ordering}, Arc, Mutex},
    time::Duration,
};
use tokio::sync::Notify;

const CH: u64 = 512 * 1024;
const FG_WORKERS: usize = 4;
const RETRIES: u32 = 6; // pauses 1+2+4+8+16 s, then the stream fails
/// A Telegram request that has not answered in this long is dead (a socket that died silently on a network change
/// never errors by itself): it is retried like any other failure.
const CHUNK_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Clone, Copy)]
pub enum Mode {
    /// The track being played: gets every worker.
    Fg,
    /// Prefetch of the next track: first in the background queue.
    Next,
}

pub enum Open {
    Cached(PathBuf),
    Live(Arc<Dl>),
}

struct St {
    done: Vec<bool>,
    busy: Vec<bool>,
    cursor: usize, // where the player is reading; workers fetch the first missing chunk from here
    err: Option<String>,
    finished: bool,
}

pub struct Dl {
    id: i64,
    pub size: u64,
    file: File,
    media: Mutex<Arc<Media>>,
    st: Mutex<St>,
    notify: Notify,
    map_path: PathBuf,
    part: PathBuf,
    audio: PathBuf,
}

#[derive(Default)]
struct M {
    active: HashMap<i64, Arc<Dl>>,
    fg: Option<i64>,
    bg: VecDeque<i64>,
}

pub struct Streams {
    tg: Arc<Tg>,
    store: Arc<Store>,
    dir: PathBuf,
    m: Mutex<M>,
    sched: Notify,
    limit: AtomicU64, // max bytes of finished `.audio` files
    playing: AtomicI64, // the track the player has loaded (cached ones too): never evicted or cleared
}

fn pack(done: &[bool]) -> Vec<u8> {
    let mut b = vec![0u8; done.len().div_ceil(8)];
    for (i, d) in done.iter().enumerate() {
        b[i / 8] |= (*d as u8) << (i % 8);
    }
    b
}

impl Dl {
    /// Marks the first missing, not-yet-claimed chunk (from the read cursor, wrapping) as busy.
    fn claim(&self) -> Option<usize> {
        let mut s = self.st.lock().unwrap();
        let n = s.done.len();
        let c = (0..n).map(|i| (s.cursor + i) % n).find(|&c| !s.done[c] && !s.busy[c])?;
        s.busy[c] = true;
        Some(c)
    }

    /// Waits until `ready` says so (or the download failed); `ready` runs under the state lock.
    async fn wait<T>(&self, mut ready: impl FnMut(&mut St) -> Option<T>) -> Result<T> {
        loop {
            let n = self.notify.notified();
            tokio::pin!(n);
            n.as_mut().enable(); // register before checking so a wake-up in between is not lost
            {
                let mut s = self.st.lock().unwrap();
                if let Some(e) = &s.err {
                    bail!("{e}");
                }
                if let Some(v) = ready(&mut s) {
                    return Ok(v);
                }
            }
            n.await;
        }
    }
}

impl Streams {
    pub fn new(tg: Arc<Tg>, store: Arc<Store>, dir: PathBuf) -> Arc<Self> {
        Arc::new(Self { tg, store, dir, m: Default::default(), sched: Notify::new(), limit: AtomicU64::new(2 << 30), playing: AtomicI64::new(0) })
    }

    fn audio(&self, id: i64) -> PathBuf {
        self.dir.join(format!("{id}.audio"))
    }

    pub fn cached(&self, id: i64) -> Option<PathBuf> {
        Some(self.audio(id)).filter(|p| p.exists())
    }

    /// The player started this track: it counts as used now and its files are safe from cache cleanup, even when it
    /// plays from an already complete file (nothing is "active" then).
    pub async fn set_playing(&self, id: i64) {
        self.playing.store(id, Ordering::Relaxed);
        let _ = self.store.touch(id).await;
    }

    /// Starts (or joins) the download of a track, marks it as used, and applies the scheduling mode.
    pub async fn open(self: &Arc<Self>, id: i64, mode: Mode) -> Result<Open> {
        // prefetching the next track is not listening to it
        if matches!(mode, Mode::Fg) {
            self.store.touch(id).await?;
        }
        if let Some(p) = self.cached(id) {
            return Ok(Open::Cached(p));
        }
        let existing = self.m.lock().unwrap().active.get(&id).cloned();
        let dl = match existing {
            Some(d) => d,
            None => match self.create(id).await? {
                Open::Live(d) => d,
                done => return Ok(done),
            },
        };
        {
            let mut m = self.m.lock().unwrap();
            match mode {
                Mode::Fg if m.fg != Some(id) => {
                    // the previous foreground track keeps downloading, in the background queue
                    if let Some(old) = m.fg.replace(id).filter(|o| m.active.contains_key(o) && !m.bg.contains(o)) {
                        m.bg.push_back(old);
                    }
                    m.bg.retain(|x| *x != id);
                }
                Mode::Next if m.fg != Some(id) && !m.bg.contains(&id) => m.bg.push_front(id),
                _ => {}
            }
        }
        self.sched.notify_waiters();
        Ok(Open::Live(dl))
    }

    async fn create(self: &Arc<Self>, id: i64) -> Result<Open> {
        let media = Arc::new(tokio::time::timeout(CHUNK_TIMEOUT, self.tg.media_of(&self.store, id)).await.map_err(|_| anyhow!("Telegram did not answer in time"))??);
        let Media::Document(d) = &*media else { bail!("not a document") };
        let size = d.size().unwrap_or(0) as u64;
        if size == 0 {
            bail!("unknown file size");
        }
        let n = size.div_ceil(CH) as usize;
        let (part, map_path, audio) = (self.dir.join(format!("{id}.part")), self.dir.join(format!("{id}.map")), self.audio(id));
        let file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&part)?;
        let mut done = vec![false; n];
        if file.metadata()?.len() == size {
            // resume: trust the map only if the preallocated file has the expected size
            if let Ok(b) = std::fs::read(&map_path).map_err(drop).and_then(|b| if b.len() == n.div_ceil(8) { Ok(b) } else { Err(()) }) {
                done.iter_mut().enumerate().for_each(|(i, d)| *d = b[i / 8] >> (i % 8) & 1 == 1);
            }
        } else {
            file.set_len(size)?;
        }
        let complete = done.iter().all(|d| *d);
        let dl = Arc::new(Dl {
            id,
            size,
            file,
            media: Mutex::new(media),
            st: Mutex::new(St { done, busy: vec![false; n], cursor: 0, err: None, finished: false }),
            notify: Notify::new(),
            map_path,
            part,
            audio,
        });
        {
            let mut m = self.m.lock().unwrap();
            if let Some(d) = m.active.get(&id) {
                return Ok(Open::Live(d.clone())); // lost a race with a parallel open
            }
            m.active.insert(id, dl.clone());
        }
        if complete {
            dl.st.lock().unwrap().finished = true;
            self.finish(&dl).await;
            return Ok(Open::Cached(self.audio(id)));
        }
        for w in 0..FG_WORKERS {
            tokio::spawn(self.clone().worker(dl.clone(), w));
        }
        Ok(Open::Live(dl))
    }

    /// Foreground track: every worker. Background: only worker 0 of the head of the queue.
    fn may_run(&self, id: i64, w: usize) -> bool {
        let m = self.m.lock().unwrap();
        m.fg == Some(id) || (w == 0 && m.bg.front() == Some(&id))
    }

    async fn worker(self: Arc<Self>, dl: Arc<Dl>, w: usize) {
        let mut fails = 0u32;
        loop {
            let n = self.sched.notified();
            tokio::pin!(n);
            n.as_mut().enable();
            {
                let s = dl.st.lock().unwrap();
                if s.err.is_some() || s.finished {
                    return;
                }
            }
            if !self.may_run(dl.id, w) {
                n.await;
                continue;
            }
            let Some(c) = dl.claim() else { return };
            let media = dl.media.lock().unwrap().clone();
            let want = (dl.size - c as u64 * CH).min(CH) as usize;
            let r = async {
                let b = tokio::time::timeout(CHUNK_TIMEOUT, self.tg.chunk(&media, c as i32)).await.map_err(|_| anyhow!("chunk {c}: Telegram did not answer in time"))??;
                if b.len() != want {
                    bail!("chunk {c}: got {} bytes, expected {want}", b.len());
                }
                dl.file.write_all_at(&b, c as u64 * CH)?;
                Ok(())
            }
            .await;
            match r {
                Ok(()) => {
                    fails = 0;
                    self.complete(&dl, c).await;
                }
                Err(e) => {
                    dl.st.lock().unwrap().busy[c] = false;
                    // FLOOD_WAIT_N: Telegram says exactly how long to wait; that is not a failure (grammers already sleeps itself for short ones)
                    if let Some(grammers_client::InvocationError::Rpc(r)) = e.downcast_ref::<grammers_client::InvocationError>() {
                        if r.name == "FLOOD_WAIT" {
                            tokio::time::sleep(Duration::from_secs(r.value.unwrap_or(5).min(600) as u64 + 1)).await; // shortcut: waits cap at 10 min, then retries as usual
                            continue;
                        }
                    }
                    fails += 1;
                    if fails >= RETRIES {
                        self.fail(&dl, e.to_string());
                        return;
                    }
                    tokio::time::sleep(Duration::from_secs(1 << (fails - 1))).await;
                    // the file reference may have expired: re-read the message
                    if let Ok(Ok(m)) = tokio::time::timeout(CHUNK_TIMEOUT, self.tg.media_of(&self.store, dl.id)).await {
                        *dl.media.lock().unwrap() = Arc::new(m);
                    }
                }
            }
        }
    }

    async fn complete(&self, dl: &Arc<Dl>, c: usize) {
        let all = {
            let mut s = dl.st.lock().unwrap();
            s.done[c] = true;
            s.busy[c] = false;
            // under the lock, so a late write cannot resurrect the map after `finish` removed it
            let _ = std::fs::write(&dl.map_path, pack(&s.done));
            let all = s.done.iter().all(|d| *d) && !s.finished;
            s.finished |= all;
            all
        };
        if all {
            self.finish(dl).await;
        }
        dl.notify.notify_waiters();
    }

    async fn finish(&self, dl: &Arc<Dl>) {
        if let Err(e) = std::fs::rename(&dl.part, &dl.audio) {
            dl.st.lock().unwrap().err = Some(format!("rename failed: {e}"));
        }
        let _ = std::fs::remove_file(&dl.map_path);
        self.forget(dl.id);
        self.evict().await;
        dl.notify.notify_waiters();
    }

    fn fail(&self, dl: &Arc<Dl>, e: String) {
        dl.st.lock().unwrap().err.get_or_insert(e); // .part/.map stay, the next open resumes
        self.forget(dl.id);
        dl.notify.notify_waiters();
    }

    fn forget(&self, id: i64) {
        let mut m = self.m.lock().unwrap();
        m.active.remove(&id);
        m.bg.retain(|x| *x != id);
        drop(m);
        self.sched.notify_waiters(); // the next queued track may start now
    }

    /// Bytes `[offset, offset+len)` of the track, shorter if the file ends or only part of it has arrived.
    /// Waits for the chunk under `offset`; returns empty at EOF.
    pub async fn read(self: &Arc<Self>, id: i64, offset: u64, len: usize) -> Result<Vec<u8>> {
        let live = self.m.lock().unwrap().active.get(&id).cloned();
        let dl = match live {
            Some(d) => d,
            None => {
                if let Some(p) = self.cached(id) {
                    return Self::read_file(p, offset, len);
                }
                match self.open(id, Mode::Fg).await? {
                    Open::Live(d) => d,
                    Open::Cached(p) => return Self::read_file(p, offset, len),
                }
            }
        };
        if offset >= dl.size {
            return Ok(vec![]);
        }
        let c = (offset / CH) as usize;
        let end = dl
            .wait(|s| {
                s.cursor = c; // a seek: workers jump here
                if !s.done[c] {
                    return None;
                }
                let k = s.done[c..].iter().take_while(|d| **d).count() + c;
                Some(dl.size.min(k as u64 * CH).min(offset + len as u64))
            })
            .await?;
        let mut buf = vec![0; (end - offset) as usize];
        dl.file.read_exact_at(&mut buf, offset)?;
        Ok(buf)
    }

    fn read_file(p: PathBuf, offset: u64, len: usize) -> Result<Vec<u8>> {
        let f = File::open(p)?;
        let end = f.metadata()?.len().min(offset + len as u64);
        let mut buf = vec![0; end.saturating_sub(offset) as usize];
        f.read_exact_at(&mut buf, offset)?;
        Ok(buf)
    }

    /// Total size of the track in bytes (starts the download if needed).
    pub async fn size(self: &Arc<Self>, id: i64, mode: Mode) -> Result<u64> {
        Ok(match self.open(id, mode).await? {
            Open::Cached(p) => std::fs::metadata(p)?.len(),
            Open::Live(d) => d.size,
        })
    }

    /// Resolves with the final file path once the track is fully downloaded.
    pub async fn finished(self: &Arc<Self>, id: i64) -> Result<PathBuf> {
        let live = self.m.lock().unwrap().active.get(&id).cloned();
        match live {
            None => self.cached(id).ok_or_else(|| anyhow!("track is not downloading")),
            Some(d) => {
                d.wait(|s| s.finished.then_some(())).await?;
                self.cached(id).ok_or_else(|| anyhow!("finished file is missing"))
            }
        }
    }

    /// Sets the cache limit and evicts down to it; returns the bytes the cache now holds.
    pub async fn set_limit(&self, bytes: u64) -> u64 {
        self.limit.store(bytes, Ordering::Relaxed);
        self.evict().await
    }

    /// Deletes every downloaded and partial track except the ones playing or downloading; returns bytes left.
    pub async fn clear(&self) -> u64 {
        let keep: HashSet<i64> = {
            let m = self.m.lock().unwrap();
            m.active.keys().copied().chain(m.fg).chain(Some(self.playing.load(Ordering::Relaxed))).collect()
        };
        for e in std::fs::read_dir(&self.dir).into_iter().flatten().flatten() {
            let p = e.path();
            let id = p.file_name().and_then(|n| n.to_str()).and_then(|n| n.split_once('.')).filter(|(_, ext)| matches!(*ext, "audio" | "part" | "map")).and_then(|(id, _)| id.parse::<i64>().ok());
            if id.is_some_and(|id| !keep.contains(&id)) {
                let _ = std::fs::remove_file(p);
            }
        }
        self.evict().await
    }

    /// Deletes least recently played `.audio` files until the cache fits the limit; active tracks are kept.
    /// Returns the size left.
    /// shortcut: abandoned `.part`/`.map` files are not counted or evicted, add it if they pile up.
    async fn evict(&self) -> u64 {
        let Ok(rd) = std::fs::read_dir(&self.dir) else { return 0 };
        let limit = self.limit.load(Ordering::Relaxed);
        let played = self.store.last_played().await.unwrap_or_default();
        let keep: HashSet<i64> = {
            let m = self.m.lock().unwrap();
            m.active.keys().copied().chain(m.fg).chain(Some(self.playing.load(Ordering::Relaxed))).collect()
        };
        let mut files: Vec<(i64, i64, u64, PathBuf)> = rd
            .flatten()
            .filter_map(|e| {
                let p = e.path();
                let id: i64 = p.file_name()?.to_str()?.strip_suffix(".audio")?.parse().ok()?;
                Some((played.get(&id).copied().unwrap_or(0), id, e.metadata().ok()?.len(), p))
            })
            .collect();
        let mut total: u64 = files.iter().map(|f| f.2).sum();
        files.sort();
        for (_, id, len, p) in files {
            if total <= limit {
                break;
            }
            if !keep.contains(&id) && std::fs::remove_file(p).is_ok() {
                total -= len;
            }
        }
        total
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn map_roundtrip_and_claim_wraps() {
        let done = [true, false, true, true, false, false, false, false, true];
        let b = pack(&done);
        assert_eq!(b, vec![0b0000_1101, 0b1]);
        let st = St { done: vec![true, true, false], busy: vec![false; 3], cursor: 1, err: None, finished: false };
        let n = st.done.len();
        let first = (0..n).map(|i| (st.cursor + i) % n).find(|&c| !st.done[c] && !st.busy[c]);
        assert_eq!(first, Some(2));
    }
}
