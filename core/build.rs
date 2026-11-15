// Embedded Telegram credentials come from the build env, never from the repo.
fn main() {
    println!("cargo:rerun-if-env-changed=SNIFF_API_ID");
    println!("cargo:rerun-if-env-changed=SNIFF_API_HASH");
    println!("cargo:rerun-if-env-changed=LASTFM_API_KEY");
    // XOR with a fixed pad: keeps the hash out of `strings`, not a real secret store.
    let pad = b"sniff";
    let id = std::env::var("SNIFF_API_ID").unwrap_or_default();
    let hash = std::env::var("SNIFF_API_HASH").unwrap_or_default();
    let lastfm = std::env::var("LASTFM_API_KEY").unwrap_or_default();
    let enc = |s: &str| s.bytes().enumerate().map(|(i, b)| (b ^ pad[i % pad.len()]).to_string()).collect::<Vec<_>>().join(",");
    let out = std::env::var("OUT_DIR").unwrap();
    std::fs::write(format!("{out}/keys.rs"), format!("const ID: &[u8] = &[{}];\nconst HASH: &[u8] = &[{}];\nconst LASTFM: &[u8] = &[{}];\n", enc(&id), enc(&hash), enc(&lastfm))).unwrap();
}
