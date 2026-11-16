package app.sniff

import android.app.Application
import android.content.Intent
import androidx.media3.common.MediaMetadata
import android.net.Uri
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
import uniffi.sniff_ffi.*
import java.io.File

enum class Step { Phone, Code, Password, Qr }

/** Built-in «Мне нравится» playlist (❤), same id as in the core. */
const val LIKED = 1L

class Vm(a: Application) : AndroidViewModel(a) {
    private var core: App? = null
    var ready by mutableStateOf(false)
    var authorized by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
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

    var query by mutableStateOf("")
    var recent by mutableStateOf(prefs.getString("recent", "")!!.split(',').mapNotNull { it.toLongOrNull() })
        private set

    private val player = Shared.player(a)
    var queue by mutableStateOf<List<TrackInfo>>(emptyList())
    var index by mutableIntStateOf(-1)
    var playing by mutableStateOf(false)
    var loadingTrack by mutableStateOf(false)
    var pos by mutableLongStateOf(0L)
    val current get() = queue.getOrNull(index)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(0) // 0 off, 1 all, 2 one
    private var orig: List<TrackInfo> = emptyList()
    var recsOn by mutableStateOf(prefs.getBoolean("recs", true))
        private set
    private var recJob: Job? = null

    private var qrJob: Job? = null
    private var playJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onPlaybackStateChanged(state: Int) {
            loadingTrack = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) { if (repeat == 2) { player.seekTo(0); player.play() } else next() }
        }
        override fun onPlayerError(e: PlaybackException) { error = e.cause?.cause?.message ?: e.message }
    }

    init {
        Shared.onNext = ::next
        Shared.onPrev = ::prev
        player.addListener(listener)
        playing = player.isPlaying
        viewModelScope.launch { while (true) { if (playing && !loadingTrack) pos = player.currentPosition; delay(500) } }
        run {
            val c = openApp(a.filesDir.resolve("tg").path, a.cacheDir.resolve("tracks").path)
            core = c; Shared.core = c
            Covers.core = c; Covers.tracksDir = a.cacheDir.resolve("tracks")
            authorized = c.isAuthorized()
            ready = true
            if (authorized) loadAll()
        }
    }

    private fun msg(e: Exception) = (e as? FfiException.Msg)?.msg ?: e.toString()

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true; error = null
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e); ready = true } finally { busy = false }
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

    private suspend fun finishLogin() { authorized = true; loadAll() }

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

    fun sync() {
        run {
            val c = core!!
            val sel = c.selectedChats()
            if (sel.isEmpty()) { syncText = "Выберите чаты"; return@run }
            var total = 0
            for (id in sel) {
                do {
                    val s = c.syncStep(id, 50u)
                    total += s.newTracks.toInt()
                    syncText = "Найдено треков: $total"
                    tracks = c.tracks("")
                } while (!s.done)
            }
            syncText = "Готово: +$total"
        }
    }

    fun search(q: String) {
        viewModelScope.launch { try { results = if (q.isBlank()) emptyList() else core!!.tracks(q) } catch (e: Exception) { error = msg(e) } }
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

    // --- player ---
    fun play(list: List<TrackInfo>, i: Int) { orig = list; queue = if (shuffle) list.take(i + 1) + list.drop(i + 1).filter { !it.disliked }.shuffled() else list; index = i; load() }
    /** 🔀 button of a list: shuffle on, random start, ✕ tracks left out. */
    fun playShuffled(list: List<TrackInfo>) {
        val l = list.filter { !it.disliked }.ifEmpty { return }
        shuffle = true; orig = l; queue = l.shuffled(); index = 0; load()
    }
    fun toggleShuffle() {
        val c = current
        shuffle = !shuffle
        if (shuffle) { orig = queue; queue = queue.take(index + 1) + queue.drop(index + 1).filter { !it.disliked }.shuffled() }
        else { queue = orig; index = queue.indexOf(c).coerceAtLeast(0) }
    }
    fun cycleRepeat() { repeat = (repeat + 1) % 3 }
    fun toggleRecs() {
        recsOn = !recsOn; prefs.edit().putBoolean("recs", recsOn).apply()
        if (!recsOn) { recJob?.cancel(); return }
        prepareRec()
        viewModelScope.launch { try { if (!core!!.hasRecSource()) error = "Подключите бота в Настройки → Музыкальные боты" } catch (e: CancellationException) { throw e } catch (_: Exception) {} }
    }

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

    /** Keeps one recommendation ahead: 3 own tracks, then a new one. A bot download is slow, so it is fetched
     *  while earlier tracks play; if it is late it simply lands later. */
    private fun prepareRec() {
        if (!recsOn || recJob?.isActive == true || queue.drop(index + 1).any { it.rec }) return
        val seeds = queue.take(index + 1).takeLast(10).map { it.documentId } // core keeps the last 3 own (non-rec, non-✕) ones
        if (seeds.isEmpty()) return
        recJob = viewModelScope.launch {
            val r = try { core!!.recommend(seeds) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return@launch
            val lastRec = queue.take(index + 1).indexOfLast { it.rec }
            val at = maxOf(index + 1, (if (lastRec >= 0) lastRec else index) + 4).coerceAtMost(queue.size)
            queue = queue.toMutableList().also { it.add(at, r) }
            orig = if (shuffle) orig + r else queue
        }
    }

    private fun load() {
        val t = current ?: return
        prepareRec()
        recent = (listOf(t.documentId) + recent.filter { it != t.documentId }).take(20)
        prefs.edit().putString("recent", recent.joinToString(",")).apply()
        playJob?.cancel()
        playJob = viewModelScope.launch {
            loadingTrack = true; pos = 0
            try {
                val c = core!!
                val cachedPath = c.cachedPath(t.documentId)
                // not downloaded yet: play straight from the downloader (TgDataSource) while it fills the file
                val uri = cachedPath?.let { Uri.fromFile(File(it)) } ?: Uri.parse("sniff://t/${t.documentId}")
                if (cachedPath == null) c.streamStart(t.documentId, true)
                val cover = Covers.cached(t)
                player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(meta(t, cover)).build())
                player.prepare(); player.play()
                getApplication<Application>().startService(Intent(getApplication(), PlaybackService::class.java))
                if (cover == null) launch { Covers.file(t)?.let { setCover(t, it) } }
                if (cachedPath != null) prefetchNext(t) else launch {
                    // the file is complete: switch to the tag cover and start loading the next track
                    try {
                        c.awaitDownload(t.documentId)
                        Covers.upgrade(t)?.let { setCover(t, it) }
                        prefetchNext(t)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {} // a failed download surfaces through the player
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e); loadingTrack = false }
        }
    }

    private fun meta(t: TrackInfo, cover: File?) =
        MediaMetadata.Builder().setTitle(t.title).setArtist(t.performer.ifBlank { null }).setArtworkUri(cover?.let { Uri.fromFile(it) }).build()

    /** Metadata-only replace: ExoPlayer keeps the position and does not restart the track. */
    private fun setCover(t: TrackInfo, cover: File) {
        if (current?.documentId != t.documentId) return
        val item = player.currentMediaItem ?: return
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(meta(t, cover)).build())
    }

    private suspend fun prefetchNext(t: TrackInfo) {
        if (current?.documentId != t.documentId) return
        val n = queue.getOrNull(index + 1) ?: return
        try { core!!.streamStart(n.documentId, false) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    /** ❤: the track in or out of «Мне нравится». Telegram is never touched. */
    fun toggleLike() { current?.let { t -> setIn(LIKED, t, !t.liked) } }

    /** Track just marked ✕, offered back for a few seconds. */
    var undo by mutableStateOf<TrackInfo?>(null)
    /** ✕ «Не нравится»: out of the library, recs and shuffle (playlists keep it), then skipped like `next`. */
    fun dismiss() {
        val t = current ?: return
        setDisliked(t, true); undo = t
        viewModelScope.launch { delay(4000); if (undo == t) undo = null }
        next()
    }
    fun restore() { undo?.let { setDisliked(it, false) }; undo = null }

    private fun patch(id: Long, f: (TrackInfo) -> TrackInfo) {
        val upd = { l: List<TrackInfo> -> l.map { if (it.documentId == id) f(it) else it } }
        queue = upd(queue); orig = upd(orig); results = upd(results)
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
        run { val id = core!!.createPlaylist(name); if (first != null) { setIn(id, first, true) } else reloadLib(); done(id) }
    }
    fun renamePlaylist(id: Long, name: String) { if (name.isNotBlank()) run { core!!.renamePlaylist(id, name); reloadLib() } }
    fun deletePlaylist(id: Long) { run { playlists.firstOrNull { it.id == id }?.cover?.let { File(it).delete() }; core!!.deletePlaylist(id); reloadLib() } }
    fun savePlaylistOrder(id: Long, ids: List<Long>) { run { core!!.setPlaylistOrder(id, ids); reloadLib() } }
    /** Copies the picked image into app storage (a content:// grant does not survive); null resets to the collage. */
    fun setPlaylistCover(id: Long, uri: Uri?) {
        run {
            val app = getApplication<Application>()
            playlists.firstOrNull { it.id == id }?.cover?.let { File(it).delete() }
            val path = uri?.let {
                // new name each time so screens keyed on the path redraw
                val f = File(app.filesDir, "covers").apply { mkdirs() }.resolve("pl_${id}_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { app.contentResolver.openInputStream(it)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
                f.path
            }
            core!!.setPlaylistCover(id, path); reloadLib()
        }
    }

    fun next() {
        if (index + 1 < queue.size) { index++; load() } else if (repeat == 1 && queue.isNotEmpty()) { index = 0; load() } }
    fun prev() { if (player.currentPosition > 3000 || index <= 0) { player.seekTo(0); pos = 0 } else { index--; load() } }
    // --- queue editing (only upcoming tracks, i > index, so the current track stays valid) ---
    fun jumpTo(i: Int) { if (i in queue.indices) { index = i; load() } }
    fun removeAt(i: Int) {
        if (i <= index || i !in queue.indices) return
        val t = queue[i]
        queue = queue.toMutableList().also { it.removeAt(i) }
        orig = if (shuffle) orig.toMutableList().also { it.remove(t) } else queue // keep shuffle-off from resurrecting removed tracks
    }
    fun moveTrack(from: Int, to: Int) {
        if (from <= index || to <= index || from !in queue.indices || to !in queue.indices) return
        queue = queue.toMutableList().also { it.add(to, it.removeAt(from)) }
        if (!shuffle) orig = queue
    }
    fun toggle() { if (player.isPlaying) player.pause() else player.play() }
    fun seek(ms: Long) { player.seekTo(ms); pos = ms }

    override fun onCleared() {
        player.removeListener(listener)
        Shared.onNext = null; Shared.onPrev = null
    }
}
