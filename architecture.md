# sniff — архитектура

Плеер музыки из Telegram-чатов. Rust-ядро + тонкие оболочки (CLI, Android).

```
┌──────────── Android (Kotlin, Compose) ────────────┐
│ MainActivity → Ui.kt (экраны) → Vm.kt (состояние) │
│ Covers.kt (обложки)   PlaybackService.kt (Media3) │
└──────────────┬────────────────────────────────────┘
               │ UniFFI (JNA, libsniff_ffi.so)
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
- `chats(id, title, selected, cursor, top, done, bot, muted, source)` — `cursor`/`done` = обратный скан истории, `top` = самое новое просканированное сообщение (дальше смотрим только выше), `source` = бот, подключённый пользователем.
- `track_places(document_id, chat_id, message_id)` — все места, где трек встречался (пересылки): если одну копию удалили, играет другая.
- `tracks(document_id PK, title, performer, duration, size, chat_id, message_id, date, last_played, rec, disliked)` — дедуп по `document_id`; `rec` = скачано приложением (рекомендация/поиск), не в библиотеке, пока не в плейлисте.
- `tracks_fts` (fts5, ё=е) — префиксный поиск, `LIMIT 200`.
- `playlists`, `playlist_tracks` — плейлисты; id 1 = «Мне нравится» (❤).
- Миграции — `user_version` (сейчас 3).
- Библиотека = треки выбранных чатов (бот-чаты тоже, это часто вся музыка), плюс всё из плейлистов, минус ✕ и `rec`; скачанное самим приложением (рекомендации, поиск) попадает в неё только через плейлист + всё, что лежит в плейлисте, минус ✕ и `rec`.

### `stream.rs` — `Streams`
Трек растёт в `<id>.part` (+ `<id>.map`, бит на чанк 512 КБ), плеер читает через `read`; готовый файл → `<id>.audio`. 4 воркера на играющий трек, фоновая очередь для префетча следующего. Пропущенный трек, скачанный меньше чем наполовину, бросается. Запрос чанка — таймаут 20 с + повторы. LRU-кэш с лимитом; играющий (`set_playing`) и скачиваемые треки не удаляются.

### `recs.rs`
Last.fm (`track.getSimilar`, запасной путь через артистов) выбирает песни, бот-источник (только подключённый через `connect_bot`) доставляет аудио в свой чат. Доставленное аудио должно совпасть по тегам (`matches`, включая версии live/remix/slowed…); один диалог на бот-чат одновременно.

## ffi — `App`
`open_app(data_dir, cache_dir, device_model, system_version)` — один `App` на процесс (Kotlin переиспользует его при пересоздании `Vm`). Futures UniFFI опрашиваются потоком вызывающего, поэтому Kotlin вызывает ядро с `Dispatchers.Default`, а fallback-рантайм async-compat многопоточный. Методы: логин (код, QR, 2FA с повтором пароля), чаты, `sync_step`, `tracks`, плейлисты, `recommend`, `bot_search`/`bot_get`, `stream_start/read/await_download`, `set_playing`, кэш, `cover`.

## Android
- `Playback` — очередь и управление плеером, один на процесс: `load()` делает `set_playing`, затем играет `file://` (если трек скачан) или `sniff://t/<id>` через `TgDataSource` (стриминг из Rust); обложка из тега подменяется, когда файл докачан; следующий трек префетчится. Живёт дольше `Vm`, поэтому после смахивания приложения очередь идёт дальше, а next/prev в уведомлении работают.
- `Vm` — состояние экранов (библиотека, плейлисты, чаты, логин), очередь только отображает из `Playback`. Ядро открывается один раз на процесс; выход из аккаунта удаляет файлы сессии и перезапускает процесс.
- `Shared` — синглтон `ExoPlayer` и ядро. `PlaybackService` (`MediaSessionService`) плеер не освобождает и пускает к сессии только своё приложение и системные контроллеры.
- `Covers` — тег из файла → Telegram thumb → iTunes (с паузой после 403), кэш в памяти по байтам.
- Ui.kt / Playlists.kt / SettingsScreen.kt — Compose-экраны.

## Сборка
GitHub Actions (`.github/workflows/android.yml`): `cargo ndk` → `.so` (arm64-v8a), uniffi → Kotlin, `gradle assembleDebug`, артефакт APK. Ключи — секреты `SNIFF_API_ID/HASH`.

## Поток воспроизведения
```
тап → Vm.load → set_playing → (скачан? file:// : sniff:// → TgDataSource → stream_read) → ExoPlayer
```
Старт — после первого чанка в 512 КБ. APK собирается как release (не debuggable), бэкап приложения выключен: `tg.session` не должен покидать телефон.
