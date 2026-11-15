//! Recommendations: taste from recorded plays -> Deezer related artists/top tracks -> candidates not in the library yet.
use crate::{artists, store::{Signal, Store}};
use anyhow::{bail, Result};
use serde_json::Value;
use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, PartialEq)]
pub struct Cand {
    pub artist: String,
    pub title: String,
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

/// Does a bot's audio (performer/title as tagged, duration if known) match the wanted candidate?
pub fn matches(c: &Cand, performer: &str, title: &str, duration: Option<u32>) -> bool {
    let ck = artists::key(&c.artist);
    let mut t = title.to_lowercase();
    let artist_ok = if performer.is_empty() {
        // untagged: "Artist - Title" in the title itself
        let ok = t.contains(&ck);
        t = t.replacen(&ck, "", 1).trim_start_matches([' ', '-', '–', '—']).to_string();
        ok
    } else {
        artists::split(performer).iter().any(|a| artists::key(a) == ck)
    };
    // shortcut: fixed +-5s window, radio edits and extended mixes get rejected on purpose
    let dur_ok = duration.is_none_or(|d| c.duration == 0 || d.abs_diff(c.duration) <= 5);
    artist_ok && tkey(&t) == tkey(&c.title) && dur_ok
}

/// Artists by taste score (likes up, skips down), best first; only positive scores.
pub fn top_artists(plays: impl IntoIterator<Item = (String, Signal)>, n: usize) -> Vec<String> {
    let mut score: HashMap<String, (i32, String)> = HashMap::new();
    for (performer, kind) in plays {
        for a in artists::split(&performer) {
            score.entry(artists::key(&a)).or_insert((0, a)).0 += kind.weight();
        }
    }
    let mut v: Vec<_> = score.into_values().filter(|(s, _)| *s > 0).collect();
    v.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    v.into_iter().take(n).map(|(_, a)| a).collect()
}

fn get(path: &str, query: &[(&str, &str)]) -> Result<Value> {
    let mut r = ureq::get(format!("https://api.deezer.com{path}")).header("User-Agent", "tgkiku/0.1").query_pairs(query.iter().copied()).call()?;
    let v: Value = r.body_mut().read_json()?;
    // Deezer reports errors (quota etc.) with HTTP 200
    if let Some(e) = v.get("error") {
        bail!("deezer: {e}");
    }
    Ok(v)
}

fn data(v: &Value) -> &[Value] {
    v["data"].as_array().map_or(&[], Vec::as_slice)
}

fn tracks_of(v: &Value) -> Vec<Cand> {
    data(v)
        .iter()
        .filter_map(|t| Some(Cand { artist: t["artist"]["name"].as_str()?.into(), title: t["title"].as_str()?.into(), duration: t["duration"].as_u64().unwrap_or(0) as u32 }))
        .collect()
}

fn top(artist_id: i64, n: usize) -> Result<Vec<Cand>> {
    Ok(tracks_of(&get(&format!("/artist/{artist_id}/top"), &[("limit", &n.to_string())])?))
}

/// New-to-you tracks: top songs of the artists you like and of their related artists, minus what the library already has.
/// No plays recorded yet -> the most common library artists act as the taste.
pub async fn suggest(store: &Store, seeds: usize, per_artist: usize) -> Result<Vec<Cand>> {
    let mut plays = store.plays().await?;
    if plays.is_empty() {
        plays = store.performers().await?.into_iter().map(|p| (p, Signal::Play)).collect();
    }
    let seeds = top_artists(plays, seeds);
    let owned: HashSet<(String, String)> = store
        .tracks("")
        .await?
        .iter()
        .flat_map(|t| artists::split(&t.performer).into_iter().map(move |a| (artists::key(&a), tkey(&t.title))))
        .collect();
    tokio::task::spawn_blocking(move || {
        let (mut out, mut seen) = (vec![], owned);
        for name in seeds {
            let found = get("/search/artist", &[("q", &name), ("limit", "1")])?;
            let Some(id) = data(&found).first().and_then(|a| a["id"].as_i64()) else { continue };
            let mut cands = top(id, per_artist)?;
            for rel in data(&get(&format!("/artist/{id}/related"), &[("limit", "3")])?) {
                if let Some(rid) = rel["id"].as_i64() {
                    cands.extend(top(rid, per_artist)?);
                }
            }
            for c in cands {
                if seen.insert((artists::key(&c.artist), tkey(&c.title))) {
                    out.push(c);
                }
            }
        }
        Ok(out)
    })
    .await?
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
    fn bot_audio_matching() {
        let w = c("Imagine Dragons", "Believer", 204);
        assert!(matches(&w, "Imagine Dragons", "Believer", Some(204)));
        assert!(matches(&w, "imagine dragons", "Believer (Remastered)", Some(206)));
        assert!(matches(&w, "", "Imagine Dragons - Believer", None));
        assert!(!matches(&w, "Imagine Dragons", "Believer", Some(260))); // extended mix
        assert!(!matches(&w, "Imagine Dragons", "Thunder", Some(204)));
        assert!(!matches(&w, "Other Band", "Believer", Some(204)));
    }

    #[test]
    fn taste_ranking() {
        let p = |a: &str, k| (a.to_string(), k);
        let top = top_artists([p("A", Signal::Play), p("B", Signal::Like), p("a", Signal::Play), p("C", Signal::Skip), p("B feat. A", Signal::Skip)], 5);
        // A: +1 +1 -2 (as feat.) = 0, B: +3 -2 = 1, C: -2
        assert_eq!(top, vec!["B"]);
    }

    #[test]
    fn deezer_json() {
        let v: Value = serde_json::from_str(r#"{"data":[{"title":"Believer","duration":204,"artist":{"name":"Imagine Dragons"}},{"nope":1}]}"#).unwrap();
        assert_eq!(tracks_of(&v), vec![c("Imagine Dragons", "Believer", 204)]);
        assert!(data(&Value::Null).is_empty());
    }
}
