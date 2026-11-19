// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Motion + effects shared by every screen. All continuous animations read their values inside
// graphicsLayer/draw lambdas so they only redraw, never recompose.

// --------------------------------------------------------------------------------------------
// Entrance: staggered fade + rise for content composed while a screen is first appearing.
// --------------------------------------------------------------------------------------------

internal class EntranceClock(val startNanos: Long = System.nanoTime())

/** Pressed state of the button hosting the current content (EyeBadge blinks on press); null outside buttons. */
internal val LocalHostPressed = compositionLocalOf<androidx.compose.runtime.State<Boolean>?> { null }

internal val LocalEntrance = compositionLocalOf<EntranceClock?> { null }

/** Starts a fresh entrance window for the screen's content (call once per screen). */
@Composable
internal fun ProvideEntrance(content: @Composable () -> Unit) {
    val clock = remember { EntranceClock() }
    CompositionLocalProvider(LocalEntrance provides clock, content = content)
}

private const val ENTRANCE_WINDOW_NS = 900_000_000L

/**
 * Fade + rise in, staggered by [index]. Only applies to items first composed within the screen's
 * entrance window, so lazy items scrolled into view later just appear (no replay while scrolling).
 */
@Composable
internal fun Modifier.entrance(index: Int = 0, rise: Dp = 28.dp): Modifier {
    val clock = LocalEntrance.current ?: return this
    val animate = remember { System.nanoTime() - clock.startNanos < ENTRANCE_WINDOW_NS }
    if (!animate) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, 10) * 55L)
        progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow))
    }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * risePx
    }
}

// --------------------------------------------------------------------------------------------
// Press feedback: springy squash, held briefly because car taps are synthetic and very short.
// --------------------------------------------------------------------------------------------

@Composable
internal fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.94f): Modifier {
    val pressed by source.collectIsPressedAsState()
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) held = true else { delay(140); held = false }
    }
    val scale by animateFloatAsState(
        if (held) pressedScale else 1f,
        spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

// --------------------------------------------------------------------------------------------
// Ken Burns: slow drift + zoom for backdrops.
// --------------------------------------------------------------------------------------------

@Composable
internal fun Modifier.kenBurns(seed: Int = 0, periodMs: Int = 20_000): Modifier {
    // Car: half the travel at half the speed. Very slow motion is cheap for AA's video encoder and
    // never pulls the eye away from the text and buttons.
    val car = LocalLayoutMode.current.isCar
    val amp = if (car) 0.5f else 1f
    val t = rememberInfiniteTransition(label = "kb")
    val p by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (car) periodMs * 2 else periodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "kbp",
    )
    val dir = if (seed % 2 == 0) 1f else -1f
    return clipToBounds().graphicsLayer {
        val e = FastOutSlowInEasing.transform(p)
        val s = 1.05f + 0.09f * e * amp
        scaleX = s
        scaleY = s
        translationX = dir * (e - 0.5f) * size.width * 0.045f * amp
        translationY = (0.5f - e) * size.height * 0.025f * amp
    }
}

// --------------------------------------------------------------------------------------------
// Shimmer skeleton.
// --------------------------------------------------------------------------------------------

@Composable
internal fun Modifier.shimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        -0.6f, 1.6f,
        infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "shx",
    )
    return drawBehind {
        drawRect(BoophisColors.Surface)
        val w = size.width
        val c = x * w
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                start = Offset(c - w * 0.4f, 0f),
                end = Offset(c + w * 0.4f, size.height),
            ),
        )
    }
}

// --------------------------------------------------------------------------------------------
// Ambient background: a heavily blurred, dimmed copy of the current artwork plus drifting glows.
// --------------------------------------------------------------------------------------------

@Composable
internal fun AmbientBackground(imagePath: String?, modifier: Modifier = Modifier, strength: Float = 0.6f) {
    Box(modifier.fillMaxSize().background(BoophisColors.Background)) {
        Crossfade(imagePath, animationSpec = tween(1100), label = "ambient") { path ->
            // A tiny decode upscaled with bilinear filtering is already a soft blur; blur() smooths
            // the rest on API 31+ (no-op below). The image layer is static between crossfades.
            val layer = Modifier.fillMaxSize().blur(28.dp).graphicsLayer { alpha = strength }
            if (path != null) {
                val ctx = LocalContext.current
                val req = remember(path) { ImageRequest.Builder(ctx).data(File(path)).size(96, 54).build() }
                AsyncImage(req, null, layer, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            }
            // No artwork: just the canopy glows below (the old amber backdrop image no longer fits).
        }
        Aurora(Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to BoophisColors.Background.copy(alpha = 0.3f),
                    0.55f to BoophisColors.Background.copy(alpha = 0.7f),
                    1f to BoophisColors.Background,
                ),
            ),
        )
    }
}

/**
 * Two slow radial glows (amber + teal) orbiting the top of the screen. Static in car mode: Android
 * Auto streams the screen as H.264, and a full-screen moving gradient spends bitrate that should
 * keep text crisp.
 */
@Composable
internal fun Aurora(modifier: Modifier = Modifier) {
    val car = LocalLayoutMode.current.isCar
    val angle = if (car) null else {
        val t = rememberInfiniteTransition(label = "aurora")
        t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "aa")
    }
    Canvas(modifier) {
        val a = angle?.value ?: 0.8f
        val r = size.maxDimension * 0.55f
        val c1 = Offset(size.width * (0.72f + 0.12f * cos(a)), size.height * (0.12f + 0.08f * sin(a)))
        val c2 = Offset(size.width * (0.2f + 0.1f * sin(a * 2f)), size.height * (0.05f + 0.06f * cos(a)))
        drawCircle(
            Brush.radialGradient(listOf(BoophisColors.Accent.copy(alpha = 0.16f), Color.Transparent), c1, r),
            r, c1,
        )
        drawCircle(
            Brush.radialGradient(listOf(BoophisColors.Teal.copy(alpha = 0.10f), Color.Transparent), c2, r * 0.8f),
            r * 0.8f, c2,
        )
    }
}

// --------------------------------------------------------------------------------------------
// Brand bits.
// --------------------------------------------------------------------------------------------

/** The transparent boophis glyph (static; the animated frog is [Frog]). */
@Composable
internal fun BoophisMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.boophis_mark), null, modifier.size(size))
}

/** Gradient sweep spinner (replaces the stock circular indicator). */
@Composable
internal fun GlowSpinner(modifier: Modifier = Modifier, stroke: Dp = 5.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(950, easing = LinearEasing)), label = "sa")
    val sweep by t.animateFloat(
        40f, 260f,
        infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sw",
    )
    Canvas(modifier) {
        val w = stroke.toPx()
        val brush = Brush.sweepGradient(
            listOf(Color.Transparent, BoophisColors.Accent, BoophisColors.Accent2, Color.Transparent),
        )
        drawCircle(Color.White.copy(alpha = 0.08f), style = Stroke(w), radius = size.minDimension / 2 - w / 2)
        drawArc(
            brush, angle, sweep, useCenter = false,
            topLeft = Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

/** One coral skin spot, the mark before section titles (the frog's spots, not a generic pill). */
@Composable
internal fun SpotMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(
            Brush.radialGradient(
                listOf(BoophisColors.Coral, BoophisColors.Coral.copy(alpha = 0.85f), Color(0xFFB8361F)),
                center = Offset(r * 0.8f, r * 0.75f), radius = r * 1.2f,
            ),
            r,
        )
    }
}

/**
 * The eye: Boophis's one signature motif, drawn after the mascot's eye. A bronze iris with a dark
 * horizontal pupil carries the play glyph, and the electric-blue outer ring fills with watch progress
 * ([progress] null = full ring). [reveal] draws the ring in once, for the single page-load moment.
 */
@Composable
internal fun EyeBadge(progress: Float?, modifier: Modifier = Modifier, reveal: Boolean = false) {
    // Blink once when the host button is pressed (phone only; the car and reduce-motion stay still).
    val pressed = LocalHostPressed.current
    val blink = remember { Animatable(0f) }
    val canBlink = pressed != null && !LocalLayoutMode.current.isCar && !rememberReduceMotion() &&
        !androidx.compose.ui.platform.LocalInspectionMode.current
    if (canBlink) {
        LaunchedEffect(pressed) {
            androidx.compose.runtime.snapshotFlow { pressed!!.value }.collect { down ->
                if (down) {
                    blink.animateTo(1f, tween(90, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                    delay(60)
                    blink.animateTo(0f, tween(160, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                }
            }
        }
    }
    val target = (progress ?: 1f).coerceIn(0f, 1f)
    val shown = remember { Animatable(if (reveal) 0f else target) }
    LaunchedEffect(target) { shown.animateTo(target, tween(if (reveal) 900 else 300, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val d = size.minDimension
        val ring = d * 0.12f
        val c = Offset(size.width / 2, size.height / 2)
        val outer = d / 2 - ring / 2
        // Ring track, then the progress arc from 12 o'clock.
        drawCircle(BoophisColors.RingBlue.copy(alpha = 0.28f), outer, c, style = Stroke(ring))
        drawArc(
            BoophisColors.RingBlue, -90f, 360f * shown.value, useCenter = false,
            topLeft = Offset(c.x - outer, c.y - outer), size = androidx.compose.ui.geometry.Size(outer * 2, outer * 2),
            style = Stroke(ring, cap = StrokeCap.Round),
        )
        // Iris: warm bronze, lighter at the centre like the mascot's.
        val iris = d / 2 - ring * 1.1f
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFF6B27A), BoophisColors.Iris, Color(0xFF9A4E1E)), c, iris),
            iris, c,
        )
        // Play glyph in pupil-dark, nudged right for optical centring.
        val s = iris * 0.62f
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(c.x - s * 0.38f, c.y - s * 0.55f)
            lineTo(c.x + s * 0.62f, c.y)
            lineTo(c.x - s * 0.38f, c.y + s * 0.55f)
            close()
        }
        drawPath(p, Color(0xFF120804))
        // Catchlight.
        drawCircle(Color.White.copy(alpha = 0.75f), iris * 0.14f, Offset(c.x - iris * 0.45f, c.y - iris * 0.45f))
        // Eyelid: frog-skin mint wiping down over the iris, with a darker crease at its edge.
        val b = blink.value
        if (b > 0f) {
            val y = c.y - iris + 2 * iris * b
            val disc = androidx.compose.ui.graphics.Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(c.x - iris, c.y - iris, c.x + iris, c.y + iris))
            }
            withTransform({ clipPath(disc) }) {
                drawRect(Color(0xFF7FE0C4), Offset(c.x - iris, c.y - iris), androidx.compose.ui.geometry.Size(2 * iris, y - (c.y - iris)))
                val crease = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - iris, y)
                    quadraticTo(c.x, y + iris * 0.36f, c.x + iris, y)
                }
                drawPath(crease, Color(0xFF3FA88A), style = Stroke(ring * 0.35f, cap = StrokeCap.Round))
            }
        }
    }
}
