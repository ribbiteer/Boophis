// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Builds the single process-wide ExoPlayer: hardware MediaCodec video, platform audio decoders
 * first with the FFmpeg extension as fallback (AC3/EAC3/DTS/TrueHD), USAGE_MEDIA with audio focus.
 */
object PlayerEngine {
    @OptIn(UnstableApi::class)
    fun create(context: Context): ExoPlayer {
        val renderers = DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context.applicationContext, renderers)
            // Local files; MKVs with Tracks after the clusters get a virtual relocation (MatroskaLayoutFix).
            .setMediaSourceFactory(DefaultMediaSourceFactory(MkvFixingDataSource.Factory(context)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
    }
}
