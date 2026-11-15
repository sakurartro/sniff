//! Recommendations: Last.fm says what is similar, a source bot delivers the audio.
use crate::{artists, store::{Store, Track}, tg::Tg};
use anyhow::{anyhow, bail, Result};
use serde_json::Value;
use std::collections::{HashMap, HashSet};

/// A song we want: from Last.fm, not in the library yet.
#[derive(Debug, Clone, PartialEq)]
pub struct Cand {
    pub artist: String,
    pub title: String,
    /// seconds, 0 = unknown
    pub duration: u32,
}

/// Title without "(feat. X)", "[Remastered]" and " - Radio Edit" parts, lowercase: same song, different tagging.
pub fn tkey(title: &str) -> String {
    let (mut out, mut depth) = (String::new(), 0);
    for c in title.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = (depth - 1).max(0),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    let out = out.split(" - ").next().unwrap_or("").split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    if out.is_empty() { title.trim().to_lowercase() } else { out }
}

/// Is (performer, title, duration) the wanted song? An empty performer means "Artist - Title" is in the title.
pub fn matches(c: &Cand, performer: &str, title: &str, duration: Option<u32>) -> bool {
    let ck = artists::key(&c.artist);
    let (artist_ok, title) = if performer.trim().is_empty() {
        match title.split_once(" - ") {
            Some((a, t)) => (artists::split(a).iter().any(|a| artists::key(a) == ck), t),
            None => (false, title),
        }
    } else {
        (artists::split(performer).iter().any(|a| artists::key(a) == ck), title)
    };
    // shortcut: fixed +-10s window, extended mixes get rejected on purpose
    let dur_ok = duration.is_none_or(|d| d == 0 || c.duration == 0 || d.abs_diff(c.duration) <= 10);
    artist_ok && tkey(title) == tkey(&c.title) && dur_ok
}

/// Last.fm track.getSimilar; many hits have no similar tracks, then top tracks of similar artists stand in.
pub fn similar(api_key: &str, artist: &str, title: &str, limit: usize) -> Result<Vec<Cand>> {
    let n = limit.to_string();
    let v = lastfm(api_key, &[("method", "track.getsimilar"), ("artist", artist), ("track", title), ("autocorrect", "1"), ("limit", &n)])?;
    let out = cands(&v["similartracks"]["track"]);
    if !out.is_empty() {
        return Ok(out);
    }
    let v = lastfm(api_key, &[("method", "artist.getsimilar"), ("artist", artist), ("autocorrect", "1"), ("limit", "5")])?;
    let mut out = vec![];
    for a in v["similarartists"]["artist"].as_array().map_or(&[][..], Vec::as_slice).iter().filter_map(|a| a["name"].as_str()) {
        out.extend(cands(&lastfm(api_key, &[("method", "artist.gettoptracks"), ("artist", a), ("limit", "3")])?["toptracks"]["track"]));
    }
    Ok(out)
}

fn lastfm(api_key: &str, q: &[(&str, &str)]) -> Result<Value> {
    let mut r = ureq::get("https://ws.audioscrobbler.com/2.0/")
        .header("User-Agent", "tgkiku/0.1")
        .query_pairs(q.iter().copied().chain([("api_key", api_key), ("format", "json")]))
        .config()
        .http_status_as_error(false)
        .build()
        .call()?;
    let v: Value = r.body_mut().read_json()?;
    if let Some(e) = v.get("message").filter(|_| v.get("error").is_some()) {
        bail!("last.fm: {e}");
    }
    Ok(v)
}

/// Tracks array of track.getSimilar / artist.getTopTracks.
fn cands(tracks: &Value) -> Vec<Cand> {
    tracks
        .as_array()
        .map_or(&[][..], Vec::as_slice)
        .iter()
        .filter_map(|t| {
            let artist = t["artist"]["name"].as_str()?.to_string();
            let title = t["name"].as_str()?.to_string();
            // Last.fm sends duration as a number or a string, in seconds
            let duration = t["duration"].as_u64().or_else(|| t["duration"].as_str()?.parse().ok()).unwrap_or(0) as u32;
            Some(Cand { artist, title, duration })
        })
        .collect()
}

/// `?start=` payload of a t.me deep link ("https://t.me/somebot?start=deezertrack1" -> "deezertrack1").
pub fn start_payload(url: &str) -> Option<String> {
    let (host, q) = url.split_once('?')?;
    if !host.contains("t.me/") {
        return None;
    }
    q.split('&').find_map(|kv| kv.strip_prefix("start=")).filter(|p| !p.is_empty()).map(str::to_string)
}

/// (artist, title) of an inline result card: the title is the song, the description holds the artist,
/// either as an "Artist: X" line or as the whole first line.
pub fn card_meta(title: &str, description: &str) -> (String, String) {
    let artist = description
        .lines()
        .find_map(|l| l.strip_prefix("Artist:").or_else(|| l.strip_prefix("Исполнитель:")))
        .or_else(|| description.lines().next())
        .unwrap_or("")
        .trim()
        .to_string();
    (artist, title.trim().to_string())
}

/// Does a callback button's caption name the wanted song? Bots write "Artist - Title", "Title — Artist", with durations etc.
pub fn button_matches(c: &Cand, caption: &str) -> bool {
    let cap = caption.to_lowercase();
    cap.contains(&artists::key(&c.artist)) && cap.contains(&tkey(&c.title))
}

/// Selected chats usable as source bots, (chat id, kind); chats not checked yet are checked once and remembered.
pub async fn sources(tg: &Tg, store: &Store) -> Result<Vec<(i64, String)>> {
    let mut bots = vec![];
    for (id, kind) in store.selected_bots().await? {
        let kind = match kind {
            Some(k) => k,
            None => {
                let k = tg.detect_bot(id).await?;
                store.set_bot(id, &k).await?;
                k
            }
        };
        if !kind.is_empty() {
            bots.push((id, kind));
        }
    }
    Ok(bots)
}

/// Per-session state: songs already tried, and bots that keep missing.
#[derive(Default)]
pub struct Recs {
    tried: HashSet<(String, String)>,
    misses: HashMap<i64, u32>,
}

impl Recs {
    /// One new song similar to the last 3 liked of `seeds` (most recent last), fetched and marked as an
    /// unliked recommendation; None if nothing worked. Only ❤ tracks seed, so taste does not drift rec -> rec.
    pub async fn next(&mut self, tg: &Tg, store: &Store, seeds: &[Track]) -> Result<Option<Track>> {
        let own: Vec<&Track> = seeds.iter().filter(|t| t.liked).collect();
        let seeds = &own[own.len().saturating_sub(3)..];
        let key = crate::keys::lastfm().ok_or_else(|| anyhow!("no Last.fm key: build with LASTFM_API_KEY"))?;
        let mut bots = sources(tg, store).await?;
        if bots.is_empty() {
            return Ok(None);
        }
        // bots that missed a lot go last
        bots.sort_by_key(|(id, _)| self.misses.get(id).copied().unwrap_or(0) >= 3);
        let owned: HashSet<_> = store.all_tracks().await?.iter().flat_map(|t| artists::split(&t.performer).into_iter().map(move |a| (artists::key(&a), tkey(&t.title)))).collect();
        // shortcut: at most 4 Last.fm candidates per call so one slot never stalls for minutes
        let mut attempts = 0;
        for seed in seeds.iter().rev() {
            let Some(artist) = artists::split(&seed.performer).into_iter().next() else { continue };
            let (k, a, t) = (key.clone(), artist.clone(), seed.title.clone());
            let cands = tokio::task::spawn_blocking(move || similar(&k, &a, &t, 30)).await??;
            for c in cands {
                let id = (artists::key(&c.artist), tkey(&c.title));
                if owned.contains(&id) || !self.tried.insert(id) {
                    continue;
                }
                for (chat, kind) in &bots {
                    match tg.fetch(store, *chat, kind, &c).await {
                        Ok(Some(t)) => {
                            store.mark_rec(t.document_id).await?;
                            self.misses.remove(chat);
                            return Ok(Some(Track { liked: false, rec: true, ..t }));
                        }
                        _ => *self.misses.entry(*chat).or_default() += 1,
                    }
                }
                attempts += 1;
                if attempts >= 4 {
                    return Ok(None);
                }
            }
        }
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(a: &str, t: &str, d: u32) -> Cand {
        Cand { artist: a.into(), title: t.into(), duration: d }
    }

    #[test]
    fn title_key() {
        assert_eq!(tkey("Believer (feat. X) [Remastered 2020]"), "believer");
        assert_eq!(tkey("Believer - Radio Edit"), "believer");
        assert_eq!(tkey("(Intro)"), "(intro)");
    }

    #[test]
    fn matching() {
        let w = c("Imagine Dragons", "Believer", 204);
        assert!(matches(&w, "Imagine Dragons", "Believer", Some(202)));
        assert!(matches(&w, "imagine dragons feat. X", "Believer (Remastered)", None));
        assert!(matches(&w, "", "Imagine Dragons - Believer", Some(0)));
        assert!(!matches(&w, "Imagine Dragons", "Believer", Some(260))); // extended mix
        assert!(!matches(&w, "Imagine Dragons", "Thunder", Some(204)));
        assert!(!matches(&w, "Other Band", "Believer", Some(204)));
        assert!(!matches(&w, "", "Believer", None));
    }

    #[test]
    fn lastfm_json() {
        let v: Value = serde_json::from_str(r#"{"similartracks":{"track":[{"name":"Thunder","duration":187,"artist":{"name":"Imagine Dragons"}},{"name":"X","duration":"200","artist":{"name":"Y"}},{"nope":1}]}}"#).unwrap();
        assert_eq!(cands(&v["similartracks"]["track"]), vec![c("Imagine Dragons", "Thunder", 187), c("Y", "X", 200)]);
        assert!(cands(&Value::Null).is_empty());
    }

    #[test]
    fn deep_links() {
        assert_eq!(start_payload("https://t.me/deezload2bot?start=deezertrack528330441").as_deref(), Some("deezertrack528330441"));
        assert_eq!(start_payload("https://t.me/bot?x=1&start=abc"), Some("abc".into()));
        assert_eq!(start_payload("https://www.deezer.com/track/1?start=x"), None);
        assert_eq!(start_payload("https://t.me/bot?start="), None);
    }

    #[test]
    fn cards_and_buttons() {
        assert_eq!(card_meta("Believer", "Artist: Imagine Dragons\nAlbum: Evolve"), ("Imagine Dragons".into(), "Believer".into()));
        assert_eq!(card_meta("Believer", "Imagine Dragons"), ("Imagine Dragons".into(), "Believer".into()));
        let w = c("Imagine Dragons", "Believer", 0);
        assert!(button_matches(&w, "🎵 Imagine Dragons - Believer (3:24)"));
        assert!(!button_matches(&w, "Imagine Dragons - Thunder"));
    }
}
