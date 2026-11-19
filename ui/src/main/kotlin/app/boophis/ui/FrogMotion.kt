// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// The mascot's "2.5D rig": the static art plus three pixel-aligned patches (eyelids, throat) that are
// drawn on top of it only while they move, so the resting frog is exactly the original image.

internal enum class FrogArt(
    val base: Int, val lidNear: Int, val lidFar: Int, val throat: Int,
    val spec: RigSpec, val aspect: Float,
) {
    Mark(R.drawable.boophis_mark, R.drawable.rig_mark_lid_near, R.drawable.rig_mark_lid_far, R.drawable.rig_mark_throat, FrogRig.Mark, 1f),
    Head(R.drawable.boophis_mark_head, R.drawable.rig_head_lid_near, R.drawable.rig_head_lid_far, R.drawable.rig_head_throat, FrogRig.Head, 1f),
    Empty(R.drawable.empty_library, R.drawable.rig_empty_lid_near, R.drawable.rig_empty_lid_far, R.drawable.rig_empty_throat, FrogRig.Empty, 4f / 3f),
}

internal enum class FrogIdle { None, BlinkOnly, Full }

/** Incremented once when the first scan turns an empty library into one with videos. */
internal val LocalFrogFound = compositionLocalOf { 0 }

/**
 * When [LocalFrogFound] last changed (uptime ms). Home swaps its empty layout for the library at that
 * very moment, so its title frog is composed fresh with the new value; a frog composed this soon after
 * the event still reacts.
 */
@Volatile internal var frogFoundAtMs: Long = Long.MIN_VALUE
private const val FOUND_FRESH_MS = 2000L

/** The system "Remove animations" switch sets the animator duration scale to 0. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private suspend fun Animatable<Float, *>.blinkOnce() {
    animateTo(1f, tween(90, easing = FastOutLinearInEasing))
    delay(60)
    animateTo(0f, tween(160, easing = LinearOutSlowInEasing))
}

private suspend fun Animatable<Float, *>.blink(double: Boolean) {
    blinkOnce()
    if (double) {
        delay(120)
        blinkOnce()
    }
}

@Composable
internal fun Frog(
    art: FrogArt,
    modifier: Modifier = Modifier,
    idle: FrogIdle = FrogIdle.Full,
    react: Int = 0,
) {
    val allowed = !LocalLayoutMode.current.isCar && !rememberReduceMotion() && !LocalInspectionMode.current
    val base = ImageBitmap.imageResource(art.base)
    val lidNear = ImageBitmap.imageResource(art.lidNear)
    val lidFar = ImageBitmap.imageResource(art.lidFar)
    val throatImg = ImageBitmap.imageResource(art.throat)
    val spec = art.spec

    if (!allowed) {
        Spacer(modifier.drawWithCache { onDrawBehind { drawRig(base, lidNear, lidFar, throatImg, spec, 0f, 0f, 0f) } })
        return
    }

    val blink = remember { Animatable(0f) }
    val throat = remember { Animatable(0f) }
    val glint = remember { Animatable(0f) }
    val hopSx = remember { Animatable(1f) }
    val hopSy = remember { Animatable(1f) }
    val hopTy = remember { Animatable(0f) }

    LaunchedEffect(idle) {
        if (idle == FrogIdle.None) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(6000, 12001))
            val r = Random.nextFloat()
            val double = Random.nextFloat() < 0.2f
            when {
                idle == FrogIdle.BlinkOnly || r < 0.55f -> blink.blink(double)
                r < 0.80f -> {
                    val e = FastOutSlowInEasing
                    repeat(2) {
                        throat.animateTo(1f, tween(380, easing = e))
                        throat.animateTo(0f, tween(520, easing = e))
                    }
                }
                else -> {
                    glint.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
                    glint.animateTo(0f, tween(550, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(react) {
        if (first[0]) {
            first[0] = false
            val fresh = react > 0 && android.os.SystemClock.uptimeMillis() - frogFoundAtMs < FOUND_FRESH_MS
            if (!fresh) return@LaunchedEffect
        }
        coroutineScope {
            suspend fun phase(sx: Float, sy: Float, ty: Float, ms: Int, e: androidx.compose.animation.core.Easing) = coroutineScope {
                launch { hopSx.animateTo(sx, tween(ms, easing = e)) }
                launch { hopSy.animateTo(sy, tween(ms, easing = e)) }
                launch { hopTy.animateTo(ty, tween(ms, easing = e)) }
            }
            phase(1.04f, 0.93f, 0f, 110, FastOutSlowInEasing)
            phase(0.98f, 1.05f, -0.12f, 220, LinearOutSlowInEasing)
            phase(1.03f, 0.95f, 0f, 180, FastOutLinearInEasing)
            phase(1f, 1f, 0f, 180, FastOutSlowInEasing)
            blink.blink(true)
        }
    }

    Spacer(
        modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = hopSx.value
                scaleY = hopSy.value
                translationY = hopTy.value * size.height
            }
            .drawWithCache {
                onDrawBehind {
                    drawRig(base, lidNear, lidFar, throatImg, spec, blink.value, throat.value, glint.value)
                }
            },
    )
}

private fun DrawScope.drawRig(
    base: ImageBitmap, lidNear: ImageBitmap, lidFar: ImageBitmap, throatImg: ImageBitmap, spec: RigSpec,
    blink: Float, throat: Float, glint: Float,
) {
    val bw = base.width.toFloat()
    val bh = base.height.toFloat()
    val s = min(size.width / bw, size.height / bh)
    if (s <= 0f || s.isNaN()) return
    val left = (size.width - bw * s) / 2f
    val top = (size.height - bh * s) / 2f
    withTransform({
        translate(left, top)
        scale(s, s, Offset.Zero)
    }) {
        drawImage(base, IntOffset.Zero, IntSize(base.width, base.height), IntOffset.Zero, IntSize(base.width, base.height), filterQuality = FilterQuality.High)

        fun patch(img: ImageBitmap, r: RigRect) {
            drawImage(
                img, IntOffset.Zero, IntSize(img.width, img.height),
                IntOffset((r.l * bw).roundToInt(), (r.t * bh).roundToInt()),
                IntSize((r.w * bw).roundToInt(), (r.h * bh).roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }

        if (throat > 0f) {
            val r = spec.throat
            val px = (r.l + r.w / 2f) * bw
            val py = r.t * bh + spec.throatPivotY * r.h * bh
            withTransform({ scale(1f + 0.012f * throat, 1f + 0.035f * throat, Offset(px, py)) }) { patch(throatImg, r) }
        }
        if (blink > 0f) {
            fun lid(img: ImageBitmap, r: RigRect) {
                val l = r.l * bw
                val t = r.t * bh
                clipRect(l, t, l + r.w * bw, t + r.h * bh * blink) { patch(img, r) }
            }
            spec.lidFar?.let { lid(lidFar, it) }
            lid(lidNear, spec.lidNear)
        }
        if (glint > 0f) {
            val rad = spec.glintR * bw
            val c = Offset(spec.glintX * bw + 1.2f * rad * glint, spec.glintY * bh)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.55f * glint), Color.Transparent),
                    center = c, radius = 2.2f * rad,
                ),
                radius = 2.2f * rad, center = c,
            )
        }
    }
}
