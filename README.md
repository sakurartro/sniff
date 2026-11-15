<p align="center"><img src="assets/logo.svg" width="128" alt="tgkiku logo"></p>

# tgkiku

Spotify-like player for music that lives in your Telegram chats.

- `core/` — Rust core: Telegram (grammers), SQLite library with FTS search, track cache.
- `cli/` — thin CLI to drive the core: `login`, `chats`, `select`, `sync`, `songs`, `get`.

Build with your own embedded keys (get them at https://my.telegram.org):

    TGKIKU_API_ID=123 TGKIKU_API_HASH=abc cargo build --release

Or leave them out and pass `TG_API_ID` / `TG_API_HASH` at runtime.
