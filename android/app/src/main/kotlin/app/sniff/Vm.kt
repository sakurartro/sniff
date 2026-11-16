package app.sniff

import android.app.Application
import android.content.Intent
import androidx.media3.common.MediaMetadata
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.sniff_ffi.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class Step { Phone, Code, Password, Qr }

/** Built-in «Мне нравится» playlist (❤), same id as in the core. */
const val LIKED = 1L

class Vm(a: Application) : AndroidViewModel(a) {
    private var core: App? = null
    var ready by mutableStateOf(false)
    var authorized by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error: String?
        get() = Playback.error
        set(v) { Playback.error = v }
    var step by mutableStateOf(Step.Phone)
    var qrLink by mutableStateOf<String?>(null)
    var chats by mutableStateOf<List<Chat>>(emptyList())
    var tracks by mutableStateOf<List<TrackInfo>>(emptyList())
    var playlists by mutableStateOf<List<PlaylistInfo>>(emptyList())
    /** Bumped on every playlist change, so screens showing playlist tracks reload. */
    var plVersion by mutableIntStateOf(0)
        private set
    var results by mutableStateOf<List<TrackInfo>>(emptyList())
    var syncText by mutableStateOf<String?>(null)

    private val prefs = a.getSharedPreferences("settings", 0)
    var theme by mutableStateOf(AppTheme.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: AppTheme.Default)
        private set
    fun setAppTheme(t: AppTheme) { theme = t; prefs.edit().putString("theme", t.name).apply() }
    var mode by mutableIntStateOf(prefs.getInt("mode", 2)) // 1 light, 2 dark
        private set
    fun setThemeMode(m: Int) { mode = m; prefs.edit().putInt("mode", m).apply() }
    var cacheGb by mutableFloatStateOf(prefs.getFloat("cacheGb", 2f))
        private set
    var cacheUsed by mutableLongStateOf(0L)
        private set
    /** Saves the audio cache limit and lets core evict down to it. */
    fun setCacheLimit(gb: Float) {
        cacheGb = gb; prefs.edit().putFloat("cacheGb", gb).apply()
        viewModelScope.launch(Dispatchers.Default) { cacheUsed = core!!.setCacheLimit((gb * (1 shl 30)).toLong().toULong()).toLong() }
    }
    fun clearCache() { viewModelScope.launch(Dispatchers.Default) { cacheUsed = core!!.clearCache().toLong(); Covers.clearMisc() } }

    var query by mutableStateOf("")

    // the queue and the player live in [Playback] (one per process, outliving this ViewModel); these mirror it for the screens
    val recent get() = Playback.recent
    val queue get() = Playback.queue
    val index get() = Playback.index
    val playing get() = Playback.playing
    val loadingTrack get() = Playback.loadingTrack
    val pos get() = Playback.pos
    val length get() = Playback.length
    val current get() = Playback.current
    val shuffle get() = Playback.shuffle
    val repeat get() = Playback.repeat
    val recsOn get() = Playback.recsOn

    private var qrJob: Job? = null

    init {
        Playback.init(a)
        run {
            // one core per process: the service (and its streams) outlive the activity, a second one would fight over the same files
            val c = if (Shared.hasCore()) Shared.core else openApp(a.filesDir.resolve("tg").path, a.cacheDir.resolve("tracks").path, Build.MODEL, "Android ${Build.VERSION.RELEASE}")
            core = c; Shared.core = c
            Covers.core = c; Covers.tracksDir = a.cacheDir.resolve("tracks")
            setCacheLimit(cacheGb)
            // the check goes to Telegram: offline, trust the remembered login so the cached library still opens
            val known = prefs.getBoolean("authorized", false)
            authorized = try { withTimeoutOrNull(6000) { c.isAuthorized() } ?: known } catch (e: CancellationException) { throw e } catch (e: Exception) { if (known) true else throw e }
            prefs.edit().putBoolean("authorized", authorized).apply()
            ready = true
            if (authorized) loadAll()
        }
    }

    /** Telegram revoked the session (or we logged out): only a fresh login helps. */
    var sessionDead by mutableStateOf(false)
        private set

    private fun msg(e: Exception): String {
        val m = (e as? FfiException.Msg)?.msg ?: e.toString()
        if (m.contains("Сессия Telegram завершена")) sessionDead = true
        return m
    }

    /** Ends the session in Telegram and on the phone; chats of the old account are forgotten, tracks and playlists stay. */
    fun logout() {
        viewModelScope.launch(Dispatchers.Default) {
            try { core?.logOut() } catch (_: Exception) {} // revoked already: wipe anyway
            wipeSession()
        }
    }

    fun relogin() { viewModelScope.launch(Dispatchers.Default) { wipeSession() } }

    /** Drops the session files and restarts the process: the session is open in Rust and cannot be swapped in place. */
    private suspend fun wipeSession() {
        withContext(Dispatchers.Main) { Playback.stop() }
        getApplication<Application>().filesDir.resolve("tg").listFiles()?.filter { it.name.startsWith("tg.session") }?.forEach { it.delete() }
        prefs.edit().putBoolean("authorized", false).commit()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private val running = AtomicInteger()

    /** Rust futures are polled by the thread that awaits them: on Main, SQLite work would freeze the UI. */
    private fun run(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.Default) {
        // a quick tap (❤) must not clear a long job's busy flag or the error it just reported
        if (running.getAndIncrement() == 0) error = null
        busy = true
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e); ready = true } finally { busy = running.decrementAndGet() > 0 }
    }

    // --- login ---
    fun sendPhone(p: String) { run { core!!.requestCode(p.trim()); step = Step.Code } }
    fun sendCode(c: String) { run { when (core!!.submitCode(c.trim())) { is Login.Done -> finishLogin(); is Login.Password -> step = Step.Password; else -> {} } } }
    fun sendPassword(p: String) { run { core!!.submitPassword(p); finishLogin() } }

    fun startQr() {
        step = Step.Qr; qrLink = null; error = null
        qrJob?.cancel()
        qrJob = viewModelScope.launch {
            try {
                core!!.startQr()
                while (true) {
                    when (val s = core!!.qrState()) {
                        is Login.Qr -> qrLink = s.link
                        is Login.Done -> { finishLogin(); return@launch }
                        is Login.Password -> { step = Step.Password; return@launch }
                        is Login.Failed -> { error = s.msg; step = Step.Phone; return@launch }
                        else -> {}
                    }
                    delay(1000)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e); step = Step.Phone }
        }
    }

    fun backToPhone() { qrJob?.cancel(); core?.stopQr(); step = Step.Phone }

    private suspend fun finishLogin() { authorized = true; prefs.edit().putBoolean("authorized", true).apply(); loadAll() }

    private suspend fun loadAll() {
        val c = core!!
        if (c.chats().isEmpty()) c.refreshChats()
        chats = c.chats()
        reloadLib()
    }

    private suspend fun reloadLib() { tracks = core!!.tracks(""); playlists = core!!.playlists(); plVersion++ }

    // --- library ---
    fun refreshChats() { run { core!!.refreshChats(); chats = core!!.chats() } }

    fun toggle(c: Chat) { run { core!!.setSelected(c.id, !c.selected); chats = core!!.chats() } }

    @Volatile private var syncing = false

    fun sync() {
        if (syncing) return
        syncing = true
        run {
            try {
                val c = core!!
                val sel = c.selectedChats()
                if (sel.isEmpty()) { syncText = "Выберите чаты"; return@run }
                var total = 0
                var failed: String? = null
                for (id in sel) {
                    // one chat failing (left it, flood) must not stop the others
                    try {
                        do {
                            val s = c.syncStep(id, 50u)
                            total += s.newTracks.toInt()
                            syncText = "Найдено треков: $total"
                            if (s.newTracks > 0u) tracks = c.tracks("")
                        } while (!s.done)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = msg(e) }
                }
                tracks = c.tracks("")
                syncText = "Готово: +$total"
                failed?.let { error = it }
            } finally { syncing = false }
        }
    }

    private var searchJob: Job? = null

    fun search(q: String) {
        searchJob?.cancel() // a slow answer for "ab" must not overwrite the one for "abba"
        searchJob = viewModelScope.launch(Dispatchers.Default) { try { results = if (q.isBlank()) emptyList() else core!!.tracks(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e) } }
        botJob?.cancel(); botHits = emptyList(); botSearching = false
        if (q.isBlank() || KNOWN_BOTS.none { it in botSources }) return
        botJob = viewModelScope.launch {
            delay(400) // typing pause: one inline query per query, not per letter
            botSearching = true
            botHits = try { core!!.botSearch(q.trim(), 15u) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            botSearching = false
        }
    }

    /** "Из Telegram" section of search: songs the source bot offers, fetched on tap. */
    var botHits by mutableStateOf<List<BotHit>>(emptyList())
        private set
    var botSearching by mutableStateOf(false)
        private set
    var fetching by mutableStateOf<BotHit?>(null)
        private set
    private var botJob: Job? = null
    fun playHit(h: BotHit) {
        if (fetching != null) return
        fetching = h
        viewModelScope.launch {
            try {
                val t = core!!.botGet(h)
                if (t == null) error = "Бот не нашёл этот трек" else play(listOf(t), 0)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e) } finally { fetching = null }
        }
    }

    // --- player (all of it is [Playback]) ---
    fun play(list: List<TrackInfo>, i: Int) = Playback.play(list, i)
    fun playShuffled(list: List<TrackInfo>) = Playback.playShuffled(list)
    fun toggleShuffle() = Playback.toggleShuffle()
    fun cycleRepeat() = Playback.cycleRepeat()
    fun toggleRecs() = Playback.toggleRecs()
    fun next() = Playback.next()
    fun prev() = Playback.prev()
    fun jumpTo(i: Int) = Playback.jumpTo(i)
    fun removeAt(i: Int) = Playback.removeAt(i)
    fun moveTrack(from: Int, to: Int) = Playback.moveTrack(from, to)
    fun toggle() = Playback.toggle()
    fun seek(ms: Long) = Playback.seek(ms)

    /** Lowercase usernames connected from the settings screen (for the "connected" mark). */
    var botSources by mutableStateOf(prefs.getStringSet("bots", emptySet())!!.toSet())
        private set
    fun connectBot(username: String) {
        val name = username.trim().removePrefix("@")
        if (name.isEmpty()) return
        run {
            core!!.connectBot(name)
            botSources = botSources + name.lowercase(); prefs.edit().putStringSet("bots", botSources).apply()
            chats = core!!.chats()
        }
    }

    /** ❤: the track in or out of «Мне нравится». Telegram is never touched. */
    fun toggleLike() { current?.let { t -> setIn(LIKED, t, !t.liked) } }

    /** Track just marked ✕, offered back for a few seconds. */
    var undo by mutableStateOf<TrackInfo?>(null)
    /** ✕ «Не нравится»: out of the library, recs and shuffle (playlists keep it), then skipped like `next`. */
    private var lastDismiss = 0L
    fun dismiss() {
        val t = current ?: return
        // a double tap would ✕ the next track too
        val now = System.currentTimeMillis()
        if (now - lastDismiss < 700) return
        lastDismiss = now
        setDisliked(t, true); undo = t
        viewModelScope.launch { delay(4000); if (undo == t) undo = null }
        if (index + 1 < queue.size || repeat == 1) next() else Playback.pause() // nothing to skip to: do not keep playing a ✕ track
    }
    fun restore() { undo?.let { setDisliked(it, false) }; undo = null }

    private fun patch(id: Long, f: (TrackInfo) -> TrackInfo) {
        val upd = { l: List<TrackInfo> -> l.map { if (it.documentId == id) f(it) else it } }
        Playback.patch(id, f); results = upd(results)
    }

    private fun setDisliked(t: TrackInfo, on: Boolean) {
        run { core!!.setDisliked(t.documentId, on); patch(t.documentId) { it.copy(disliked = on) }; reloadLib() }
    }

    // --- playlists (local db only) ---
    suspend fun playlistTracks(id: Long): List<TrackInfo> = try { core!!.playlistTracks(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e); emptyList() }
    suspend fun playlistsWith(t: TrackInfo): List<Long> = try { core!!.playlistsWith(t.documentId) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

    /** Adding adopts the track: no longer ✨, no longer ✕ (the core does the same). */
    fun setIn(pl: Long, t: TrackInfo, on: Boolean) {
        run {
            core!!.setInPlaylist(pl, t.documentId, on)
            patch(t.documentId) { if (on) it.copy(liked = it.liked || pl == LIKED, rec = false, disliked = false) else if (pl == LIKED) it.copy(liked = false) else it }
            reloadLib()
        }
    }
    /** New playlist, optionally with a first track; `done` gets its id. */
    fun createPlaylist(name: String, first: TrackInfo? = null, done: (Long) -> Unit = {}) {
        if (name.isBlank()) return
        run { val id = core!!.createPlaylist(name); if (first != null) { setIn(id, first, true) } else reloadLib(); withContext(Dispatchers.Main) { done(id) } }
    }
    fun renamePlaylist(id: Long, name: String) { if (name.isNotBlank()) run { core!!.renamePlaylist(id, name); reloadLib() } }
    fun deletePlaylist(id: Long) { run { playlists.firstOrNull { it.id == id }?.cover?.let { File(it).delete() }; core!!.deletePlaylist(id); reloadLib() } }
    fun savePlaylistOrder(id: Long, ids: List<Long>) { run { core!!.setPlaylistOrder(id, ids); reloadLib() } }
    /** Copies the picked image into app storage (a content:// grant does not survive); null resets to the collage. */
    fun setPlaylistCover(id: Long, uri: Uri?) {
        run {
            val app = getApplication<Application>()
            val old = playlists.firstOrNull { it.id == id }?.cover
            val path = uri?.let {
                // new name each time so screens keyed on the path redraw
                val f = File(app.filesDir, "covers").apply { mkdirs() }.resolve("pl_${id}_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { app.contentResolver.openInputStream(it)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
                f.path
            }
            core!!.setPlaylistCover(id, path); reloadLib()
            old?.let { File(it).delete() }
        }
    }

    override fun onCleared() {
        core?.stopQr()
    }
}
