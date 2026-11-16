package app.sniff

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
import uniffi.sniff_ffi.App
import uniffi.sniff_ffi.BotHit
import uniffi.sniff_ffi.TrackInfo
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
    private val hitMem = LruCache<String, Bitmap>(50)
    /** Bumped when a track's cover file changes (tag art replaced the thumb), so shown covers reload. */
    var rev by mutableIntStateOf(0)
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
        val emb = File(dir(), "${t.documentId}.emb.jpg")
        if (emb.exists()) return@withContext emb
        // full-size tag art beats Telegram's small thumb once the file is downloaded; `.noemb` = file has none
        val noEmb = File(dir(), "${t.documentId}.noemb")
        if (!noEmb.exists() && File(tracksDir, "${t.documentId}.audio").exists()) {
            embedded(t, emb)?.let { return@withContext it } ?: noEmb.createNewFile()
        }
        val jpg = File(dir(), "${t.documentId}.jpg")
        if (jpg.exists()) return@withContext jpg
        val none = File(dir(), "${t.documentId}.none")
        if (none.exists()) return@withContext null
        // the two gates are taken one after the other, never nested: a hung iTunes request must not eat Telegram's slots
        val tg = gate.withPermit { runCatching { core.cover(t.documentId) } }
        // Telegram failed (flood, network): say nothing now, ask again later, and above all no `.none`
        if (tg.isFailure) return@withContext null
        tg.getOrNull()?.let { return@withContext File(it) }
        // null = network error, so no `.none`: retry next time
        val hit = itunesGate.withPermit { runCatching { itunes(t.performer, t.title, jpg) }.getOrNull() } ?: return@withContext null
        if (!hit) none.createNewFile()
        jpg.takeIf { it.exists() }
    }

    /** Whatever cover is already on disk, without any lookup. */
    fun cached(t: TrackInfo): File? = listOf("emb.jpg", "jpg").map { File(dir(), "${t.documentId}.$it") }.firstOrNull { it.exists() }

    /**
     * For a track that was streamed: once the file is complete, picks up the tag-embedded picture.
     * It goes to a separate `.emb.jpg` so the artwork URI changes and the notification reloads it.
     */
    suspend fun upgrade(t: TrackInfo): File? = withContext(Dispatchers.IO) {
        val emb = File(dir(), "${t.documentId}.emb.jpg")
        val fresh = !emb.exists() && embedded(t, emb) != null
        if (fresh) { mem.remove(t.documentId); withContext(Dispatchers.Main) { rev++ } }
        emb.takeIf { it.exists() }
    }

    /** Already decoded cover, for drawing without a blank frame. */
    fun memory(t: TrackInfo): Bitmap? = mem.get(t.documentId)

    suspend fun bitmap(t: TrackInfo): Bitmap? {
        mem.get(t.documentId)?.let { return it }
        val f = file(t) ?: return null
        val bmp = withContext(Dispatchers.IO) { decode(f) } ?: return null
        mem.put(t.documentId, bmp)
        return bmp
    }

    /** Cover for a bot search result (nothing downloaded yet): iTunes only, cached as `hit_<hash>.jpg`. */
    suspend fun hitBitmap(h: BotHit): Bitmap? {
        val key = "${norm(h.artist)}_${norm(h.title)}"
        hitMem.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            val name = "hit_${key.hashCode().toUInt()}"
            val (jpg, none) = File(dir(), "$name.jpg") to File(dir(), "$name.none")
            if (!jpg.exists() && !none.exists()) {
                val hit = itunesGate.withPermit { runCatching { itunes(h.artist, h.title, jpg) }.getOrNull() }
                if (hit == false) none.createNewFile()
            }
            jpg.takeIf { it.exists() }?.let(::decode)
        } ?: return null
        hitMem.put(key, bmp)
        return bmp
    }

    /** Decoded at most ~600px: tag art can be 3000px, and 200 of those in [mem] would run out of memory. */
    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 600) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun embedded(t: TrackInfo, out: File): File? {
        val audio = File(tracksDir, "${t.documentId}.audio").takeIf { it.exists() } ?: return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(audio.path)
            r.embeddedPicture?.let { out.writeBytes(it); out }
        } catch (_: Exception) { null } finally { r.release() }
    }

    /** URL.readText() has no timeouts on Android: a connection that dies silently would hang forever. */
    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 10000 }
        return c.getInputStream().use { it.readBytes() }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Title without "(Remix)", "[Live]", "feat. X" etc., so versions compare equal but different songs don't. */
    private val extras = Regex("\\(.*?\\)|\\[.*?\\]|\\s(feat|ft)\\.?\\s.*", RegexOption.IGNORE_CASE)
    private fun bareTitle(s: String) = norm(s.replace(extras, ""))

    /** true = saved, false = reachable but no confident match, null = network failure (throws). */
    private fun itunes(performer: String, title: String, out: File): Boolean? {
        // shortcut: needs a performer, since a bare title matches the wrong song too often; revisit if many tracks lack one
        val (bare, artist) = bareTitle(title) to norm(performer)
        if (bare.isEmpty() || artist.isEmpty()) return false
        val q = URLEncoder.encode("$performer $title", "UTF-8")
        val res = JSONObject(get("https://itunes.apple.com/search?media=music&entity=song&limit=5&term=$q").toString(Charsets.UTF_8)).getJSONArray("results")
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            val (rt, ra) = bareTitle(r.optString("trackName")) to norm(r.optString("artistName"))
            if (ra.isEmpty()) continue
            // exact song title (loose "contains" gave "Love" the cover of "Love Story"); the artist may list extra names
            if (rt == bare && (artist.contains(ra) || ra.contains(artist))) {
                val img = r.optString("artworkUrl100").replace("100x100bb", "600x600bb")
                out.writeBytes(get(img))
                return true
            }
        }
        return false
    }
}
