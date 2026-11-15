# tgkiku — архитектура

Плеер музыки из Telegram-чатов. Rust-ядро + тонкие оболочки (CLI, Android).

```
┌──────────── Android (Kotlin, Compose) ────────────┐
│ MainActivity → Ui.kt (экраны) → Vm.kt (состояние) │
│ Covers.kt (обложки)   PlaybackService.kt (Media3) │
└──────────────┬────────────────────────────────────┘
               │ UniFFI (JNA, libtgkiku_ffi.so)
┌──────────────▼──────────┐   ┌──────────────┐
│ ffi/  App (обёртка)     │   │ cli/  main.rs │
└──────────────┬──────────┘   └──────┬───────┘
               └───────┬─────────────┘
┌──────────────────────▼──────────────────────┐
│ core/  tg.rs (grammers, MTProto)            │
│        store.rs (libsql: chats, tracks, FTS)│
│        keys.rs (API id/hash, вшиты в build) │
└─────────────────────────────────────────────┘
```

## Крейты

| Крейт | Роль |
|---|---|
| `core` | Вся логика. `Tg` — клиент Telegram (grammers 0.12), `Store` — SQLite-библиотека, `ApiKeys` — ключи (XOR-вшиты через `build.rs` или из env). |
| `ffi` | UniFFI-обёртка: один `App` на процесс, async-методы на tokio. Ошибки → `FfiError::Msg`. |
| `cli` | Отладочный CLI: `login`, `chats`, `select`, `sync`, `songs`, `get`, `cover`. |
| `bindgen` | Запуск `uniffi-bindgen` для генерации Kotlin-биндингов. |

## core

### `tg.rs` — `Tg`
- `connect` — `SenderPool` + `Client`, сессия в `tg.session` (SQLite). Явные `ConnectionParams` (иначе Telegram не шлёт коды).
- Логин: код (`request_code`/`sign_in`), QR/ссылка (`login_by_token`, с миграцией DC), 2FA (`check_password`).
- `refresh_chats` — диалоги → таблица `chats`.
- `backfill_page` — `search_messages` с фильтром Music, от курсора вниз; курсор сохраняется на каждое сообщение; пауза 300 мс.
- `download(document_id, dir)` — `media_of` (перечитывает сообщение по `chat_id/message_id`, т.к. file_reference протухает) → `download_media` в `<id>.part` → rename в `<id>.audio`. Если файл есть — сразу путь.
  - grammers: файлы > `BIG_FILE_SIZE` качаются **параллельно несколькими воркерами** чанками по 512 КБ вне порядка (файл предаллоцирован `set_len`), т.е. `.part` нельзя читать последовательно во время закачки.
  - Есть `iter_download` + `skip_chunks` — последовательные чанки с произвольного смещения.
- `cover` — лучший thumb документа → `<id>.jpg`.
- `peer_ref` — кэш `PeerRef` в памяти, один скан диалогов на чат за запуск.

### `store.rs` — `Store`
- `chats(id, title, selected, cursor)`
- `tracks(document_id PK, title, performer, duration, size, chat_id, message_id)` — дедуп по `document_id` (форварды схлопываются).
- `tracks_fts` (fts5) — префиксный поиск по title/performer.

## ffi — `App`
`open_app(data_dir, cache_dir)`; `data_dir/tg/` — сессия + `library.sqlite`, `cache_dir/tracks/` — `<id>.audio`, `covers/`.
Методы: логин, `refresh_chats`, `chats`, `set_selected`, `selected_chats`, `sync_step`, `tracks(query)`, `download(id) -> path`, `cover(id)`, `qr_state`, `stop_qr`.

## Android
- `Vm` — всё состояние UI и очередь. `play(list, i)` → `load()`: **ждёт полной закачки** `core.download()`, затем `Covers.file`, затем `MediaItem(file://…)` в ExoPlayer, старт `PlaybackService`.
- `Shared` — синглтон `ExoPlayer` (audio focus, noisy, wake lock) + колбэки next/prev.
- `PlaybackService` — `MediaSessionService`, `ForwardingPlayer` добавляет next/prev в уведомление (очередь живёт в Vm — ограничение).
- `Covers` — обложка: тег из файла → Telegram thumb → iTunes; LRU в памяти, `.none` = окончательный промах.
- Ui.kt — Compose-экраны (логин, чаты, библиотека, поиск, плеер).

## Сборка
GitHub Actions (`.github/workflows/android.yml`): `cargo ndk` → `.so` (arm64-v8a), uniffi → Kotlin, `gradle assembleDebug`, артефакт APK. Ключи — секреты `TGKIKU_API_ID/HASH`.

## Поток воспроизведения сейчас
```
тап по треку → Vm.load → App.download (весь файл) → Covers.file → ExoPlayer(file://) → play
```
Задержка старта = время полной закачки файла.
