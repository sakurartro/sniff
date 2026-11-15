package app.tgkiku

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.tgkiku_ffi.App
import uniffi.tgkiku_ffi.TrackInfo
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover lookup, cheapest first: tag embedded in the downloaded file, Telegram's thumbnail, iTunes search.
 * Files live in `<tracks cache>/covers/<id>.jpg`; `<id>.none` remembers a definitive miss.
 */
object Covers {
    lateinit var core: App
    lateinit var tracksDir: File // where `<id>.audio` files and `covers/` live
    private val mem = LruCache<Long, Bitmap>(200)
    private val gate = Semaphore(3) // shortcut: fixed 3 Telegram lookups at once, lower if it flood-waits
    private val itunesGate = Semaphore(2) // separate so slow iTunes lookups don't block Telegram thumbs
    // loads outlive the rows that asked for them, so scrolling away doesn't throw the work out
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<Long, Deferred<File?>>()

    private fun dir() = File(tracksDir, "covers").apply { mkdirs() }

    /** Cover file for the track, or null. Safe to call for every visible row. */
    suspend fun file(t: TrackInfo): File? {
        val mine = CompletableDeferred<File?>()
        val d = inflight.putIfAbsent(t.documentId, mine) ?: mine.also { m ->
            scope.launch { try { m.complete(load(t)) } catch (e: Throwable) { m.complete(null) } finally { inflight.remove(t.documentId) } }
        }
        return d.await()
    }

    private suspend fun load(t: TrackInfo): File? = withContext(Dispatchers.IO) {
        File(dir(), "${t.documentId}.emb.jpg").takeIf { it.exists() }?.let { return@withContext it }
        val jpg = File(dir(), "${t.documentId}.jpg")
        if (jpg.exists()) return@withContext jpg
        embedded(t, jpg)?.let { return@withContext it }
        val none = File(dir(), "${t.documentId}.none")
        if (none.exists()) return@withContext null
        gate.withPermit {
            runCatching { core.cover(t.documentId) }.getOrNull()?.let { return@withPermit File(it) }
            // null = network error, so no `.none`: retry next time
            val hit = itunesGate.withPermit { runCatching { itunes(t, jpg) }.getOrNull() } ?: return@withPermit null
            if (!hit) none.createNewFile()
            jpg.takeIf { it.exists() }
        }
    }

    /** Whatever cover is already on disk, without any lookup. */
    fun cached(t: TrackInfo): File? = listOf("emb.jpg", "jpg").map { File(dir(), "${t.documentId}.$it") }.firstOrNull { it.exists() }

    /**
     * For a track that was streamed: once the file is complete, picks up the tag-embedded picture.
     * It goes to a separate `.emb.jpg` so the artwork URI changes and the notification reloads it.
     */
    suspend fun upgrade(t: TrackInfo): File? = withContext(Dispatchers.IO) {
        val emb = File(dir(), "${t.documentId}.emb.jpg")
        if (!emb.exists()) embedded(t, emb)
        mem.remove(t.documentId)
        emb.takeIf { it.exists() }
    }

    suspend fun bitmap(t: TrackInfo): Bitmap? {
        mem.get(t.documentId)?.let { return it }
        val f = file(t) ?: return null
        val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) } ?: return null
        mem.put(t.documentId, bmp)
        return bmp
    }

    private fun embedded(t: TrackInfo, out: File): File? {
        val audio = File(tracksDir, "${t.documentId}.audio").takeIf { it.exists() } ?: return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(audio.path)
            r.embeddedPicture?.let { out.writeBytes(it); out }
        } catch (_: Exception) { null } finally { r.release() }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** true = saved, false = reachable but no confident match, null = network failure (throws). */
    private fun itunes(t: TrackInfo, out: File): Boolean? {
        // shortcut: needs a performer, since a bare title matches the wrong song too often; revisit if many tracks lack one
        if (t.performer.isBlank()) return false
        val q = URLEncoder.encode("${t.performer} ${t.title}", "UTF-8")
        val res = JSONObject(URL("https://itunes.apple.com/search?media=music&entity=song&limit=5&term=$q").readText()).getJSONArray("results")
        val (title, artist) = norm(t.title) to norm(t.performer)
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            val (rt, ra) = norm(r.optString("trackName")) to norm(r.optString("artistName"))
            if (rt.isEmpty() || ra.isEmpty()) continue
            if ((title.contains(rt) || rt.contains(title)) && (artist.contains(ra) || ra.contains(artist))) {
                val img = r.optString("artworkUrl100").replace("100x100bb", "600x600bb")
                out.writeBytes(URL(img).readBytes())
                return true
            }
        }
        return false
    }
}
