// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import app.boophis.model.TrackInfo
import app.boophis.model.TrackType
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Slow per-file probing: duration, size, codec, track languages, embedded art, frame grab. */
internal class MediaProbe(private val artDir: File) {
    class Result(
        val durationMs: Long,
        val width: Int?,
        val height: Int?,
        val codec: String?,
        val tracks: List<TrackInfo>,
        val embeddedArt: String?,
        val frameArt: String?,
    )

    fun probe(path: String, size: Long, mtime: Long, wantArt: Boolean): Result {
        var duration = 0L
        var w: Int? = null
        var h: Int? = null
        var codec: String? = null
        val tracks = ArrayList<TrackInfo>()
        var embedded: String? = null
        var frame: String? = null

        val key = hash("v2|$path|$size|$mtime")
        val embeddedFile = File(artDir, "$key-embedded.jpg")
        val frameFile = File(artDir, "$key-frame2.jpg")

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            if (wantArt) {
                artDir.mkdirs()
                embedded = runCatching {
                    if (embeddedFile.length() > 0) embeddedFile.path
                    else mmr.embeddedPicture?.let { bytes -> embeddedFile.writeBytes(bytes); embeddedFile.path }
                }.getOrNull()
                frame = runCatching {
                    if (frameFile.length() > 0) frameFile.path else grabFrame(mmr, duration, w, h, frameFile)
                }.getOrNull()
            }
        } catch (_: Throwable) {
            // unreadable file: fall through with whatever was gathered
        } finally {
            runCatching { mmr.release() }
        }

        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val lang = f.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() && it != "und" }
                when {
                    mime.startsWith("video/") -> {
                        if (codec == null) codec = mime.removePrefix("video/")
                        if (w == null && f.containsKey(MediaFormat.KEY_WIDTH)) w = f.getInteger(MediaFormat.KEY_WIDTH)
                        if (h == null && f.containsKey(MediaFormat.KEY_HEIGHT)) h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    mime.startsWith("audio/") -> tracks.add(
                        TrackInfo(
                            TrackType.AUDIO, lang, codec = mime.removePrefix("audio/"),
                            channels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null,
                            isDefault = flag(f, "is-default"),
                        ),
                    )
                    mime.startsWith("text/") || mime == "application/x-subrip" || mime.contains("subtitle", true) ->
                        tracks.add(
                            TrackInfo(
                                TrackType.SUBTITLE, lang, codec = mime.substringAfterLast('/'),
                                isDefault = flag(f, "is-default"), isForced = flag(f, "is-forced-subtitle"),
                            ),
                        )
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { ex.release() }
        }
        return Result(duration, w, h, codec, tracks, embedded, frame)
    }

    private fun flag(f: MediaFormat, key: String): Boolean =
        f.containsKey(key) && runCatching { f.getInteger(key) != 0 }.getOrDefault(false)

    private fun grabFrame(mmr: MediaMetadataRetriever, durationMs: Long, w: Int?, h: Int?, out: File): String? {
        var best: Bitmap? = null
        var bestStats: FrameQuality.Stats? = null
        for (fraction in FrameQuality.POSITIONS) {
            val bmp = grabAt(mmr, (durationMs * 1000L * fraction).toLong(), w, h) ?: continue
            val stats = sampleStats(bmp)
            if (bestStats == null || FrameQuality.better(stats, bestStats)) {
                best?.recycle()
                best = bmp
                bestStats = stats
            } else {
                bmp.recycle()
            }
            if (FrameQuality.isAcceptable(stats)) break
        }
        val bmp = best ?: return null
        try {
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } finally {
            bmp.recycle()
        }
        return out.path
    }

    private fun grabAt(mmr: MediaMetadataRetriever, timeUs: Long, w: Int?, h: Int?): Bitmap? {
        val raw: Bitmap? = if (w != null && h != null && w > 0 && h > 0) {
            val tw = minOf(w, MAX_FRAME_WIDTH)
            val th = (h.toLong() * tw / w).toInt().coerceAtLeast(1)
            mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
        } else mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        val bmp = raw ?: return null
        if (bmp.width <= MAX_FRAME_WIDTH) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, MAX_FRAME_WIDTH, (bmp.height.toLong() * MAX_FRAME_WIDTH / bmp.width).toInt(), true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    private fun sampleStats(bmp: Bitmap): FrameQuality.Stats {
        val small = Bitmap.createScaledBitmap(bmp, FrameQuality.SAMPLE_W, FrameQuality.SAMPLE_H, true)
        val px = IntArray(FrameQuality.SAMPLE_W * FrameQuality.SAMPLE_H)
        small.getPixels(px, 0, FrameQuality.SAMPLE_W, 0, 0, FrameQuality.SAMPLE_W, FrameQuality.SAMPLE_H)
        if (small !== bmp) small.recycle()
        return FrameQuality.stats(FloatArray(px.size) { i ->
            val c = px[i]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
        })
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_FRAME_WIDTH = 640
    }
}
