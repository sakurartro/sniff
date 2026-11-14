// Embedded Telegram credentials come from the build env, never from the repo.
fn main() {
    println!("cargo:rerun-if-env-changed=TGKIKU_API_ID");
    println!("cargo:rerun-if-env-changed=TGKIKU_API_HASH");
    // XOR with a fixed pad: keeps the hash out of `strings`, not a real secret store.
    let pad = b"tgkiku";
    let id = std::env::var("TGKIKU_API_ID").unwrap_or_default();
    let hash = std::env::var("TGKIKU_API_HASH").unwrap_or_default();
    let enc = |s: &str| s.bytes().enumerate().map(|(i, b)| (b ^ pad[i % pad.len()]).to_string()).collect::<Vec<_>>().join(",");
    let out = std::env::var("OUT_DIR").unwrap();
    std::fs::write(format!("{out}/keys.rs"), format!("const ID: &[u8] = &[{}];\nconst HASH: &[u8] = &[{}];\n", enc(&id), enc(&hash))).unwrap();
}
