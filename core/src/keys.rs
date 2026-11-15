//! API credentials: user override (config) wins over the key embedded at build time.
include!(concat!(env!("OUT_DIR"), "/keys.rs"));

#[derive(Clone)]
pub struct ApiKeys {
    pub id: i32,
    pub hash: String,
}

fn dec(b: &[u8]) -> String {
    let pad = b"sniff";
    String::from_utf8(b.iter().enumerate().map(|(i, c)| c ^ pad[i % pad.len()]).collect()).unwrap_or_default()
}

impl ApiKeys {
    pub fn embedded() -> Option<Self> {
        let (id, hash) = (dec(ID).parse().ok()?, dec(HASH));
        (!hash.is_empty()).then_some(Self { id, hash })
    }

    pub fn resolve(user: Option<ApiKeys>) -> anyhow::Result<Self> {
        user.or_else(Self::embedded)
            .ok_or_else(|| anyhow::anyhow!("no api_id/api_hash: build with SNIFF_API_ID/SNIFF_API_HASH or pass your own"))
    }
}

/// Last.fm API key: LASTFM_API_KEY at runtime, else the one embedded at build time.
pub fn lastfm() -> Option<String> {
    std::env::var("LASTFM_API_KEY").ok().or_else(|| Some(dec(LASTFM))).filter(|k| !k.is_empty())
}

#[cfg(test)]
mod tests {
    #[test]
    fn roundtrip() {
        let pad = b"sniff";
        let enc: Vec<u8> = "abc123".bytes().enumerate().map(|(i, b)| b ^ pad[i % pad.len()]).collect();
        assert_eq!(super::dec(&enc), "abc123");
    }
}
