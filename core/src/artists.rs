use std::collections::HashMap;

/// Names that contain a separator and must stay in one piece.
const KEEP: &[&str] = &["tyler, the creator"];

// shortcut: `&` is not a separator ("Simon & Garfunkel" is one act), so "A & B" stays one artist; upgrade with a per-name list if that bites
/// "A feat. B", "A (ft. B)", "A, B", "A; B", "A x B" -> ["A", "B"]; no duplicates by case.
pub fn split(performer: &str) -> Vec<String> {
    let mut s = performer.to_string();
    for k in KEEP {
        while let Some(i) = s.to_ascii_lowercase().find(k) {
            s.replace_range(i..i + k.len(), &s[i..i + k.len()].replace(", ", "\u{1}"));
        }
    }
    // ASCII lowercasing keeps byte offsets, so indexes found in the copy are valid in `s`
    for sep in ["(feat. ", "[feat. ", "(ft. ", "[ft. ", "(featuring ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", ", ", "; ", " x "] {
        while let Some(i) = s.to_ascii_lowercase().find(sep) {
            s.replace_range(i..i + sep.len(), "\n");
        }
    }
    let mut out: Vec<String> = vec![];
    for p in s.split('\n') {
        let p = p.replace('\u{1}', ", ");
        let p = p.trim().trim_end_matches([')', ']']).trim();
        if !p.is_empty() && !out.iter().any(|o| key(o) == key(p)) {
            out.push(p.to_string());
        }
    }
    out
}

pub fn key(name: &str) -> String {
    name.trim().to_lowercase()
}

/// key -> display name: the most frequent spelling across the library (ties prefer mixed case, then alphabetical).
pub fn canonical<'a>(performers: impl IntoIterator<Item = &'a str>) -> HashMap<String, String> {
    let mut seen: HashMap<String, HashMap<String, u32>> = HashMap::new();
    for p in performers {
        for a in split(p) {
            *seen.entry(key(&a)).or_default().entry(a).or_default() += 1;
        }
    }
    seen.into_iter()
        .map(|(k, v)| {
            let best = v.into_iter().max_by(|(a, x), (b, y)| x.cmp(y).then((a != &a.to_lowercase()).cmp(&(b != &b.to_lowercase()))).then(b.cmp(a))).unwrap().0;
            (k, best)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splitting() {
        assert_eq!(split("Charli XCX feat. Ariana Grande"), ["Charli XCX", "Ariana Grande"]);
        assert_eq!(split("Charli XCX (ft. Ariana Grande)"), ["Charli XCX", "Ariana Grande"]);
        assert_eq!(split("A, B; C x D"), ["A", "B", "C", "D"]);
        assert_eq!(split("charli xcx, Charli XCX"), ["charli xcx"]);
        assert_eq!(split("Simon & Garfunkel"), ["Simon & Garfunkel"]);
        assert_eq!(split("Tyler, The Creator feat. Kali Uchis"), ["Tyler, The Creator", "Kali Uchis"]);
        assert!(split("  ").is_empty());
    }

    #[test]
    fn canonical_spelling() {
        let m = canonical(["Charli XCX", "charli xcx", "Charli XCX feat. Ariana Grande", "Ariana Grande"]);
        assert_eq!(m["charli xcx"], "Charli XCX");
        assert_eq!(m["ariana grande"], "Ariana Grande");
    }
}
