// SPDX-License-Identifier: GPL-3.0-or-later
package app.boophis.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class MatroskaLayoutFixTest {
    private fun id(v: Long, len: Int) = ByteArray(len) { ((v ushr (8 * (len - 1 - it))) and 0xFF).toByte() }
    private fun size8(v: Long) = byteArrayOf(0x01) + ByteArray(7) { ((v ushr (8 * (6 - it))) and 0xFF).toByte() }
    private fun el(idv: Long, idLen: Int, body: ByteArray) = id(idv, idLen) + size8(body.size.toLong()) + body
    private fun src(b: ByteArray) = object : MatroskaLayoutFix.ByteSource {
        override val size = b.size.toLong()
        override fun read(position: Long, length: Int): ByteArray =
            b.copyOfRange(position.toInt(), minOf(b.size.toLong(), position + length).toInt())
    }

    /** EBML | Segment[ SeekHead(Tracks→pos) | Info | Void | Tags | Cluster | Cluster | Tracks ] */
    private fun build(voidPayload: Int, tracksPayload: Int, tracksFirst: Boolean = false): Pair<ByteArray, ByteArray> {
        val ebml = el(0x1A45DFA3, 4, ByteArray(4))
        val info = el(0x1549A966, 4, ByteArray(10))
        val void = el(0xEC, 1, ByteArray(voidPayload))
        val tags = el(0x1254C367, 4, ByteArray(20))
        val cluster = el(0x1F43B675, 4, ByteArray(50))
        val tracks = el(0x1654AE6B, 4, ByteArray(tracksPayload) { 7 })
        fun seekHead(pos: Long) = el(0x114D9B74, 4, el(0x4DBB, 2, el(0x53AB, 2, id(0x1654AE6B, 4)) + el(0x53AC, 2, size8(pos).copyOfRange(1, 8))))
        val probe = seekHead(0)
        val body = if (tracksFirst) probe + info + tracks + void + tags + cluster
        else {
            val tracksPos = (probe.size + info.size + void.size + tags.size + cluster.size * 2).toLong()
            seekHead(tracksPos) + info + void + tags + cluster + cluster + tracks
        }
        val out = ByteArrayOutputStream()
        out.write(ebml); out.write(id(0x18538067, 4)); out.write(size8(body.size.toLong())); out.write(body)
        return out.toByteArray() to tracks
    }

    @Test fun relocatesLateTracksIntoVoidAndTags() {
        val (file, tracks) = build(voidPayload = 100, tracksPayload = 60)
        val patch = MatroskaLayoutFix.analyze(src(file))
        assertNotNull(patch)
        patch!!
        assertArrayEquals(tracks, patch.bytes.copyOfRange(0, tracks.size))
        assertEquals(0xEC.toByte(), patch.bytes[tracks.size]) // Void filler follows
        // Region ends exactly at the first Cluster.
        assertEquals(0x1F.toByte(), file[patch.end.toInt()])
    }

    @Test fun normalLayoutUntouched() {
        val (file, _) = build(voidPayload = 100, tracksPayload = 60, tracksFirst = true)
        assertNull(MatroskaLayoutFix.analyze(src(file)))
    }

    @Test fun noRoomNoPatch() {
        val (file, _) = build(voidPayload = 5, tracksPayload = 400)
        assertNull(MatroskaLayoutFix.analyze(src(file)))
    }

    @Test fun voidSizes() {
        assertNull(MatroskaLayoutFix.voidElement(1))
        assertEquals(2, MatroskaLayoutFix.voidElement(2)!!.size)
        assertEquals(128, MatroskaLayoutFix.voidElement(128)!!.size)
        val big = MatroskaLayoutFix.voidElement(500)!!
        assertEquals(500, big.size)
        assertEquals(500L - 9, MatroskaLayoutFix.vint(big, 1, keepMarker = false)!!.first)
    }

    /** Optional: a real late-Tracks file copied to tmp/media/late-tracks.mkv during diagnosis. */
    @Test fun realLateTracksFileIfPresent() {
        val f = java.io.File("../tmp/media/late-tracks.mkv")
        if (!f.exists()) return
        java.io.RandomAccessFile(f, "r").use { raf ->
            val patch = MatroskaLayoutFix.analyze(object : MatroskaLayoutFix.ByteSource {
                override val size = raf.length()
                override fun read(position: Long, length: Int): ByteArray {
                    val n = minOf(length.toLong(), size - position).toInt(); val b = ByteArray(n); raf.seek(position); raf.readFully(b); return b
                }
            })
            assertNotNull(patch)
            assertEquals(291L, patch!!.start)
            assertEquals(1141L - 291L, patch.bytes.size.toLong())
        }
    }
}
