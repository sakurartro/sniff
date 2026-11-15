package app.tgkiku

import android.app.Application
import android.content.Intent
import androidx.media3.common.MediaMetadata
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.tgkiku_ffi.*
import java.io.File

enum class Step { Phone, Code, Password, Qr }

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
    var results by mutableStateOf<List<TrackInfo>>(emptyList())
    var syncText by mutableStateOf<String?>(null)

    private val player = Shared.player(a)
    var queue by mutableStateOf<List<TrackInfo>>(emptyList())
    var index by mutableIntStateOf(-1)
    var playing by mutableStateOf(false)
    var loadingTrack by mutableStateOf(false)
    var pos by mutableLongStateOf(0L)
    val current get() = queue.getOrNull(index)

    private var qrJob: Job? = null
    private var playJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) next() }
    }

    init {
        Shared.onNext = ::next
        Shared.onPrev = ::prev
        player.addListener(listener)
        playing = player.isPlaying
        viewModelScope.launch { while (true) { if (playing) pos = player.currentPosition; delay(500) } }
        run {
            val c = openApp(a.filesDir.resolve("tg").path, a.cacheDir.resolve("tracks").path)
            core = c
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
        tracks = c.tracks("")
    }

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
    }

    // --- player ---
    fun play(list: List<TrackInfo>, i: Int) { queue = list; index = i; load() }

    private fun load() {
        val t = current ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            loadingTrack = true; pos = 0
            try {
                val path = core!!.download(t.documentId)
                val cover = Covers.file(t) // after download, so the embedded tag is available
                val meta = MediaMetadata.Builder().setTitle(t.title).setArtist(t.performer.ifBlank { null }).setArtworkUri(cover?.let { Uri.fromFile(it) }).build()
                player.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(File(path))).setMediaMetadata(meta).build())
                player.prepare(); player.play()
                getApplication<Application>().startService(Intent(getApplication(), PlaybackService::class.java))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = msg(e) } finally { loadingTrack = false }
        }
    }

    fun next() { if (index + 1 < queue.size) { index++; load() } }
    fun prev() { if (player.currentPosition > 3000 || index <= 0) { player.seekTo(0); pos = 0 } else { index--; load() } }
    fun toggle() { if (player.isPlaying) player.pause() else player.play() }
    fun seek(ms: Long) { player.seekTo(ms); pos = ms }

    override fun onCleared() {
        player.removeListener(listener)
        Shared.onNext = null; Shared.onPrev = null
    }
}
