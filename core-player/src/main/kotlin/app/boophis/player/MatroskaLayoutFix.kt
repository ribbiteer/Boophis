// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.player

/**
 * Some MKV muxers write the Tracks element at the END of the file (after the clusters and cues),
 * relying on the SeekHead to find it. Media3's MatroskaExtractor needs Tracks before the first
 * Cluster, so such files never get tracks and playback hangs.
 *
 * If the bytes right before the first Cluster are a contiguous run of Void/Tags elements large enough
 * to hold the Tracks element, we can present a *virtual* file in which that run is replaced by the
 * Tracks element plus a Void filler. Nothing moves: cluster and cue offsets stay valid. Tags (which
 * the player ignores) are dropped from the view. The file on disk is never modified.
 *
 * Pure Kotlin so it is unit-testable; [ByteSource] abstracts random access.
 */
object MatroskaLayoutFix {

    interface ByteSource {
        val size: Long
        /** Reads up to [length] bytes at [position]; may return fewer at EOF. */
        fun read(position: Long, length: Int): ByteArray
    }

    /** Replace bytes [start, start + bytes.size) of the file with [bytes]. */
    data class Patch(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
    }

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_VOID = 0xECL
    private const val ID_TAGS = 0x1254C367L

    private const val MAX_HEADER_SCAN = 4L * 1024 * 1024
    private const val MAX_TRACKS_SIZE = 1L * 1024 * 1024

    private class Element(val id: Long, val start: Long, val dataStart: Long, val dataSize: Long) {
        val end: Long get() = dataStart + dataSize
    }

    /** Returns a patch when the file needs (and allows) the relocation; null otherwise. */
    fun analyze(src: ByteSource): Patch? = try {
        analyzeOrThrow(src)
    } catch (_: Exception) {
        null
    }

    private fun analyzeOrThrow(src: ByteSource): Patch? {
        val ebml = readElement(src, 0) ?: return null
        if (ebml.id != ID_EBML) return null
        val segment = readElement(src, ebml.end) ?: return null
        if (segment.id != ID_SEGMENT) return null
        val segData = segment.dataStart

        // Top-level children up to the first Cluster.
        val children = mutableListOf<Element>()
        var pos = segData
        var firstCluster: Element? = null
        while (pos < src.size && pos - segData < MAX_HEADER_SCAN) {
            val e = readElement(src, pos) ?: return null
            if (e.id == ID_CLUSTER) {
                firstCluster = e
                break
            }
            if (e.id == ID_TRACKS) return null // Normal layout.
            children += e
            pos = e.end
        }
        val cluster = firstCluster ?: return null

        // Locate Tracks via the SeekHead.
        val seekHead = children.firstOrNull { it.id == ID_SEEK_HEAD } ?: return null
        val tracksPos = findSeekPosition(src, seekHead, ID_TRACKS) ?: return null
        val tracks = readElement(src, segData + tracksPos) ?: return null
        if (tracks.id != ID_TRACKS || tracks.start < cluster.start) return null
        val tracksLen = tracks.end - tracks.start
        if (tracksLen > MAX_TRACKS_SIZE) return null

        // Contiguous run of Void/Tags ending exactly at the first Cluster.
        var runStart = cluster.start
        for (e in children.asReversed()) {
            if (e.end != runStart || (e.id != ID_VOID && e.id != ID_TAGS)) break
            runStart = e.start
        }
        val runLen = cluster.start - runStart
        val filler = runLen - tracksLen
        if (filler < 0) return null
        val voidBytes = if (filler == 0L) ByteArray(0) else voidElement(filler.toInt()) ?: return null

        val tracksBytes = src.read(tracks.start, tracksLen.toInt())
        if (tracksBytes.size.toLong() != tracksLen) return null
        return Patch(runStart, tracksBytes + voidBytes)
    }

    /** A Void element of exactly [total] bytes (ID + size vint + zero payload), or null if impossible. */
    internal fun voidElement(total: Int): ByteArray? {
        return when {
            total >= 2 && total - 2 <= 126 -> ByteArray(total).also {
                it[0] = ID_VOID.toByte()
                it[1] = (0x80 or (total - 2)).toByte()
            }
            total >= 9 -> ByteArray(total).also {
                val size = (total - 9).toLong()
                it[0] = ID_VOID.toByte()
                it[1] = 0x01
                for (k in 0 until 7) it[2 + k] = ((size ushr (8 * (6 - k))) and 0xFF).toByte()
            }
            else -> null
        }
    }

    private fun findSeekPosition(src: ByteSource, seekHead: Element, wantedId: Long): Long? {
        val data = src.read(seekHead.dataStart, seekHead.dataSize.coerceAtMost(64 * 1024).toInt())
        var i = 0
        while (i < data.size) {
            val (id, idLen) = vint(data, i, keepMarker = true) ?: return null
            val (size, sizeLen) = vint(data, i + idLen, keepMarker = false) ?: return null
            val body = i + idLen + sizeLen
            if (id == ID_SEEK) {
                var j = body
                var seekId: Long? = null
                var seekPos: Long? = null
                while (j < body + size && j < data.size) {
                    val (cid, cidLen) = vint(data, j, keepMarker = true) ?: break
                    val (csize, csizeLen) = vint(data, j + cidLen, keepMarker = false) ?: break
                    val cbody = j + cidLen + csizeLen
                    when (cid) {
                        ID_SEEK_ID -> seekId = uint(data, cbody, csize.toInt())
                        ID_SEEK_POSITION -> seekPos = uint(data, cbody, csize.toInt())
                    }
                    j = cbody + csize.toInt()
                }
                if (seekId == wantedId && seekPos != null) return seekPos
            }
            i = body + size.toInt()
        }
        return null
    }

    private fun readElement(src: ByteSource, pos: Long): Element? {
        val head = src.read(pos, 12)
        val (id, idLen) = vint(head, 0, keepMarker = true) ?: return null
        val (size, sizeLen) = vint(head, idLen, keepMarker = false) ?: return null
        val dataStart = pos + idLen + sizeLen
        // Unknown size (all ones) only makes sense for Segment/Cluster; treat as "to EOF".
        val unknown = size == (1L shl (7 * sizeLen)) - 1
        val dataSize = if (unknown) src.size - dataStart else size
        return Element(id, pos, dataStart, dataSize)
    }

    /** EBML variable-length integer at [i]: (value, length). */
    internal fun vint(b: ByteArray, i: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (i >= b.size) return null
        val first = b[i].toInt() and 0xFF
        if (first == 0) return null
        var len = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask ushr 1
            len++
        }
        if (i + len > b.size) return null
        var v = (if (keepMarker) first else first and (mask - 1)).toLong()
        for (k in 1 until len) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v to len
    }

    private fun uint(b: ByteArray, i: Int, len: Int): Long {
        var v = 0L
        for (k in 0 until len) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }
}
