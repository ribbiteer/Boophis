// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.player

import android.content.Context
import android.graphics.Color
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView

/**
 * Letterboxed video picture for a Media3 [Player]. Uses a [SurfaceView] by default and a
 * [TextureView] when [useTextureView] is true (fallback for virtual displays that misbehave).
 * Hosted by Compose via AndroidView in ui/ and car/.
 */
class VideoSurfaceView(context: Context, val useTextureView: Boolean = false) : FrameLayout(context) {
    private val videoView: View = if (useTextureView) TextureView(context) else SurfaceView(context)
    private var aspect = 0f

    /** Subtitles (embedded + sidecar) drawn over the picture; a bare SurfaceView renders none. */
    private val subtitles = SubtitleView(context).apply {
        setUserDefaultStyle()
        setUserDefaultTextSize()
        setBottomPaddingFraction(0.08f)
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateAspect(videoSize)
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitles.setCues(cueGroup.cues)
        }
    }

    private var bound: Player? = null

    /** The player to show. Binding follows window attachment so a detached view never holds the surface. */
    var player: Player? = null
        set(value) {
            field = value
            if (isAttachedToWindow) bind(value)
        }

    private fun bind(p: Player?) {
        if (bound === p) return
        bound?.let { old ->
            old.removeListener(listener)
            subtitles.setCues(null)
            when (val v = videoView) {
                is SurfaceView -> old.clearVideoSurfaceView(v)
                is TextureView -> old.clearVideoTextureView(v)
            }
        }
        bound = p
        p?.let {
            when (val v = videoView) {
                is SurfaceView -> it.setVideoSurfaceView(v)
                is TextureView -> it.setVideoTextureView(v)
            }
            it.addListener(listener)
            updateAspect(it.videoSize)
            subtitles.setCues(it.currentCues.cues)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(videoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(subtitles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun updateAspect(size: VideoSize) {
        val a = if (size.width == 0 || size.height == 0) 0f else size.width * size.pixelWidthHeightRatio / size.height
        if (a != aspect) {
            aspect = a
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        var cw = w
        var ch = h
        if (aspect > 0f && w > 0 && h > 0) {
            if (w.toFloat() / h > aspect) cw = (h * aspect).toInt() else ch = (w / aspect).toInt()
        }
        videoView.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        subtitles.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cw = videoView.measuredWidth
        val ch = videoView.measuredHeight
        val l = (width - cw) / 2
        val t = (height - ch) / 2
        videoView.layout(l, t, l + cw, t + ch)
        subtitles.layout(l, t, l + cw, t + ch)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind(player)
    }

    override fun onDetachedFromWindow() {
        // Unbind so the player doesn't render into a dead surface; audio keeps playing.
        bind(null)
        super.onDetachedFromWindow()
    }
}
