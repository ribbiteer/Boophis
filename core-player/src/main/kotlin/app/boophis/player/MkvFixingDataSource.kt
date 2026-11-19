// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * DataSource for local files that transparently applies [MatroskaLayoutFix] to MKVs whose Tracks
 * element sits after the clusters. Everything else is delegated to [DefaultDataSource].
 */
@OptIn(UnstableApi::class)
class MkvFixingDataSource(context: Context) : DataSource {

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = MkvFixingDataSource(context.applicationContext)
    }

    private val delegate = DefaultDataSource(context, /* allowCrossProtocolRedirects = */ false)
    private var file: RandomAccessFile? = null
    private var patch: MatroskaLayoutFix.Patch? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var usingDelegate = false

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val path = dataSpec.uri.takeIf { it.scheme == null || it.scheme == "file" }?.path
        val p = path?.let { patchFor(File(it)) }
        if (p == null) {
            usingDelegate = true
            return delegate.open(dataSpec)
        }
        usingDelegate = false
        patch = p
        val raf = RandomAccessFile(path, "r")
        file = raf
        val length = raf.length()
        if (dataSpec.position > length) throw IOException("position beyond EOF")
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length - position
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (usingDelegate) return delegate.read(buffer, offset, length)
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val raf = file ?: throw IOException("not open")
        val p = patch!!
        var want = minOf(length.toLong(), bytesRemaining).toInt()
        val n: Int
        if (position >= p.start && position < p.end) {
            // Inside the virtual region: serve the relocated Tracks + Void filler.
            want = minOf(want.toLong(), p.end - position).toInt()
            System.arraycopy(p.bytes, (position - p.start).toInt(), buffer, offset, want)
            n = want
        } else {
            // Real file bytes; never read across into the virtual region.
            if (position < p.start) want = minOf(want.toLong(), p.start - position).toInt()
            raf.seek(position)
            n = raf.read(buffer, offset, want)
            if (n < 0) return C.RESULT_END_OF_INPUT
        }
        position += n
        bytesRemaining -= n
        return n
    }

    override fun getUri(): Uri? = if (usingDelegate) delegate.uri else uri

    override fun close() {
        if (usingDelegate) {
            delegate.close()
        } else {
            file?.close()
            file = null
            patch = null
        }
        usingDelegate = false
    }

    companion object {
        private const val TAG = "BoophisPlayer"
        private val cache = LruCache<String, Any>(64)
        private val NONE = Any()

        /** Cached per path + size + mtime; only MKV-family files are inspected. */
        internal fun patchFor(f: File): MatroskaLayoutFix.Patch? {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            if (ext != "mkv" && ext != "mk3d" && ext != "mka" && ext != "webm") return null
            if (!f.isFile) return null
            val key = "${f.path}|${f.length()}|${f.lastModified()}"
            synchronized(cache) {
                cache.get(key)?.let { return it as? MatroskaLayoutFix.Patch }
            }
            val patch = RandomAccessFile(f, "r").use { raf ->
                MatroskaLayoutFix.analyze(object : MatroskaLayoutFix.ByteSource {
                    override val size: Long = raf.length()
                    override fun read(position: Long, length: Int): ByteArray {
                        if (position >= size) return ByteArray(0)
                        val n = minOf(length.toLong(), size - position).toInt()
                        val out = ByteArray(n)
                        raf.seek(position)
                        raf.readFully(out)
                        return out
                    }
                })
            }
            if (patch != null) Log.i(TAG, "MKV with late Tracks: relocating ${patch.bytes.size} B at ${patch.start} for ${f.name}")
            synchronized(cache) { cache.put(key, patch ?: NONE) }
            return patch
        }
    }
}
