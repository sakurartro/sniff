package app.sniff

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Plays `sniff://t/<document_id>`: bytes come from the Rust downloader, which waits for chunks still in flight. */
@OptIn(UnstableApi::class)
class TgDataSource : BaseDataSource(false) {
    private var uri: Uri? = null
    private var id = 0L
    private var pos = 0L
    private var left = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        id = dataSpec.uri.lastPathSegment?.toLongOrNull() ?: throw IOException("bad uri ${dataSpec.uri}")
        transferInitializing(dataSpec)
        val size = try { runBlocking { Shared.core.streamStart(id, true) }.toLong() } catch (e: Exception) { throw IOException(e) }
        pos = dataSpec.position
        if (pos > size) { uri = null; throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) }
        left = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - pos else minOf(dataSpec.length, size - pos)
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0) return C.RESULT_END_OF_INPUT
        val b = try { runBlocking { Shared.core.streamRead(id, pos.toULong(), minOf(length.toLong(), left).toUInt()) } } catch (e: Exception) { throw IOException(e) }
        if (b.isEmpty()) return C.RESULT_END_OF_INPUT
        System.arraycopy(b, 0, buffer, offset, b.size)
        pos += b.size; left -= b.size
        bytesTransferred(b.size)
        return b.size
    }

    override fun getUri() = uri

    override fun close() {
        if (uri != null) { uri = null; transferEnded() }
    }
}
