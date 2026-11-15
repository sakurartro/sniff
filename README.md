<p align="center"><img src="assets/logo.svg" width="128" alt="sniff logo"></p>

# sniff

Spotify-like player for music that lives in your Telegram chats.

- `core/` — Rust core: Telegram (grammers), SQLite library with FTS search, track cache.
- `cli/` — thin CLI to drive the core: `login`, `chats`, `select`, `sync`, `songs`, `get`.

Build with your own embedded keys (get them at https://my.telegram.org):

    SNIFF_API_ID=123 SNIFF_API_HASH=abc cargo build --release

Or leave them out and pass `TG_API_ID` / `TG_API_HASH` at runtime.

## License

Copyright © 2026 sakurartro.

This project is licensed under the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.
