package app.sniff

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.sniff_ffi.TrackInfo
import java.io.File

/**
 * The queue and everything that drives the player. One per process, NOT per ViewModel: when the activity is swiped
 * away the service keeps playing, and the notification's next/previous and the end of a track must still walk the queue.
 * [Vm] only mirrors this state for the screens.
 */
object Playback {
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    /** Shown as the red toast; the screens' own errors use it too. */
    var error by mutableStateOf<String?>(null)
    var queue by mutableStateOf<List<TrackInfo>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    var playing by mutableStateOf(false)
        private set
    var loadingTrack by mutableStateOf(false)
        private set
    var pos by mutableLongStateOf(0L)
        private set
    /** Player-measured length in ms, for tracks whose Telegram metadata has no duration (0 until known). */
    var length by mutableLongStateOf(0L)
        private set
    val current get() = queue.getOrNull(index)
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableIntStateOf(0) // 0 off, 1 all, 2 one
        private set
    var recsOn by mutableStateOf(true)
        private set
    var recent by mutableStateOf<List<Long>>(emptyList())
        private set

    private var orig: List<TrackInfo> = emptyList()
    private var recJob: Job? = null
    private var playJob: Job? = null
    private var posJob: Job? = null
    /** Tracks that failed in a row: a dead track skips to the next, but a dead network must not skip the whole queue. */
    private var failures = 0

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            if (isPlaying) failures = 0
            trackPosition()
        }
        override fun onPlaybackStateChanged(state: Int) {
            loadingTrack = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) { if (repeat == 2) { player.seekTo(0); player.play() } else next() }
        }
        override fun onPlayerError(e: PlaybackException) {
            error = e.cause?.cause?.message ?: e.message
            if (++failures <= 3) next()
        }
    }

    /** Safe to call again (a new ViewModel after the activity was recreated). */
    fun init(a: Application) {
        if (started) return
        started = true
        app = a
        prefs = a.getSharedPreferences("settings", 0)
        player = Shared.player(a)
        recent = prefs.getString("recent", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        recsOn = prefs.getBoolean("recs", true)
        player.addListener(listener)
        playing = player.isPlaying
        trackPosition()
    }

    /** The service ended (swiped away while paused): nothing is playing, so there is no queue either. */
    fun reset() {
        playJob?.cancel(); recJob?.cancel()
        queue = emptyList(); orig = emptyList(); index = -1; playing = false; loadingTrack = false; pos = 0; length = 0
    }

    /** Position ticks only while playing: no wake-ups in the background when paused. */
    private fun trackPosition() {
        posJob?.cancel()
        if (!playing) return
        posJob = scope.launch { while (true) { if (!loadingTrack) { pos = player.currentPosition; length = player.duration.coerceAtLeast(0) }; delay(500) } }
    }

    // --- starting playback ---
    fun play(list: List<TrackInfo>, i: Int) {
        if (i !in list.indices) return
        recJob?.cancel() // a recommendation made for the previous queue does not belong in this one
        orig = list
        // shuffle: the tapped track first, everything else (before it too) in random order
        if (shuffle) { queue = listOf(list[i]) + list.filterIndexed { j, t -> j != i && !t.disliked }.shuffled(); index = 0 } else { queue = list; index = i }
        load()
    }

    /** 🔀 button of a list: shuffle on, random start, ✕ tracks left out. */
    fun playShuffled(list: List<TrackInfo>) {
        val l = list.filter { !it.disliked }.ifEmpty { return }
        recJob?.cancel()
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
        scope.launch { try { if (!Shared.core.hasRecSource()) error = "Подключите бота в Настройки → Музыкальные боты" } catch (e: CancellationException) { throw e } catch (_: Exception) {} }
    }

    // --- walking the queue ---
    fun next() {
        if (index + 1 < queue.size) { index++; load() } else if (repeat == 1 && queue.isNotEmpty()) { index = 0; load() }
    }

    fun prev() { if (player.currentPosition > 3000 || index <= 0) { player.seekTo(0); pos = 0 } else { index--; load() } }

    fun jumpTo(i: Int) { if (i in queue.indices) { index = i; load() } }

    // queue editing: only upcoming tracks (i > index), so the current track stays valid
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

    fun toggle() {
        if (player.isPlaying) { player.pause(); return }
        if (player.playbackState == Player.STATE_IDLE) player.prepare() // after a playback error play() alone does nothing
        player.play()
    }

    fun pause() = player.pause()
    fun seek(ms: Long) { player.seekTo(ms); pos = ms }
    fun stop() { player.stop(); player.clearMediaItems() }

    /** A flag of a track (❤, ✕, adopted) changed: the queue shows it too. */
    fun patch(id: Long, f: (TrackInfo) -> TrackInfo) {
        val upd = { l: List<TrackInfo> -> l.map { if (it.documentId == id) f(it) else it } }
        queue = upd(queue); orig = upd(orig)
    }

    /** Keeps one recommendation ahead: 3 own tracks, then a new one. A bot download is slow, so it is fetched
     *  while earlier tracks play; if it is late it simply lands later. */
    private fun prepareRec() {
        if (!recsOn || recJob?.isActive == true || queue.drop(index + 1).any { it.rec }) return
        // own tracks only (no ✨, no ✕); the core keeps the last 3
        val seeds = queue.take(index + 1).filter { !it.rec && !it.disliked }.takeLast(10).map { it.documentId }
        if (seeds.isEmpty()) return
        recJob = scope.launch {
            val r = try { Shared.core.recommend(seeds) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return@launch
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
        playJob = scope.launch {
            loadingTrack = true; pos = 0; length = 0
            try {
                val c = Shared.core
                // cached or streamed: it counts as listened and cache cleanup leaves it alone
                c.setPlaying(t.documentId)
                val cachedPath = c.cachedPath(t.documentId)
                // not downloaded yet: play straight from the downloader (TgDataSource) while it fills the file
                val uri = cachedPath?.let { Uri.fromFile(File(it)) } ?: Uri.parse("sniff://t/${t.documentId}")
                if (cachedPath == null) c.streamStart(t.documentId, true)
                val cover = Covers.cached(t)
                player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(meta(t, cover)).build())
                player.prepare(); player.play()
                app.startService(Intent(app, PlaybackService::class.java))
                if (cover == null) launch { Covers.file(t)?.let { setCover(t, it) } }
                if (cachedPath != null) {
                    launch { Covers.upgrade(t)?.let { setCover(t, it) } }
                    prefetchNext(t)
                } else launch {
                    // the file is complete: switch to the tag cover and start loading the next track
                    try {
                        c.awaitDownload(t.documentId)
                        Covers.upgrade(t)?.let { setCover(t, it) }
                        prefetchNext(t)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {} // a failed download surfaces through the player
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = (e as? uniffi.sniff_ffi.FfiException.Msg)?.msg ?: e.toString(); loadingTrack = false }
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
        try { Shared.core.streamStart(n.documentId, false) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }
}
