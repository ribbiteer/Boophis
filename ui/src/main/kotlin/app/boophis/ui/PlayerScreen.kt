// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.boophis.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HIDE_AFTER_MS = 4_000L
private const val AUTO_NEXT_SECONDS = 10

@Composable
internal fun PlayerScreen(
    actions: Actions,
    mode: LayoutMode,
    overlayPadding: PaddingValues,
    videoSurface: @Composable (Modifier) -> Unit,
) {
    val player = actions.deps.player
    val state by player.state.collectAsState()
    PlayerContent(state, player, mode, overlayPadding, videoSurface, onExit = actions::leavePlayer)
}

@Composable
internal fun PlayerContent(
    state: PlayerState,
    player: PlayerController,
    mode: LayoutMode,
    overlayPadding: PaddingValues,
    videoSurface: @Composable (Modifier) -> Unit,
    onExit: () -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    fun poke() { tick++ }

    // Auto-hide counts only while video is actually playing: the controls stay up through the
    // opening fade/buffering and whenever playback is paused (a paused car screen shows controls).
    val playing = state.isPlaying && !state.isBuffering
    LaunchedEffect(visible, tick, playing) {
        if (visible && playing) {
            delay(HIDE_AFTER_MS)
            visible = false
        }
    }
    val currentExit by rememberUpdatedState(onExit)
    LaunchedEffect(state.ended) {
        if (state.ended) currentExit()
    }

    // Curtain: the screen opens from black like a cinema, while the first frames decode.
    val curtain = remember { Animatable(1f) }
    LaunchedEffect(Unit) { curtain.animateTo(0f, tween(700, delayMillis = 150, easing = FastOutSlowInEasing)) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { visible = !visible } },
    ) {
        videoSurface(Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = curtain.value }.background(Color.Black))

        AnimatedVisibility(
            state.isBuffering && state.error == null,
            Modifier.align(Alignment.Center),
            enter = fadeIn(tween(250, delayMillis = 200)) + scaleIn(initialScale = 0.7f),
            exit = fadeOut(tween(200)),
        ) {
            GlowSpinner(Modifier.size(if (mode.isCar) 84.dp else 60.dp), stroke = if (mode.isCar) 7.dp else 5.dp)
        }

        val showControls = visible && state.error == null && state.autoNext == null
        AnimatedVisibility(showControls, enter = fadeIn(tween(220)), exit = fadeOut(tween(300))) {
            Controls(state, player, mode, overlayPadding, onExit, ::poke)
        }

        AnimatedVisibility(
            state.autoNext != null,
            Modifier.align(Alignment.Center),
            enter = fadeIn(tween(300)) + scaleIn(spring(dampingRatio = 0.7f), initialScale = 0.85f),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.92f),
        ) {
            // Keep showing the last countdown while the card animates out.
            var last by remember { mutableStateOf(state.autoNext) }
            state.autoNext?.let { last = it }
            last?.let { AutoNextCard(it, player, mode, overlayPadding) }
        }

        state.error?.let { msg ->
            Column(
                Modifier.fillMaxSize().background(Color.Black).padding(overlayPadding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Can't play this video", style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    msg, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                BoophisButton("Back", onExit)
            }
        }
    }
}

/** Round transport button. [big] = gradient play/pause with a warm glow. */
@Composable
private fun RoundIcon(
    icon: ImageVector,
    desc: String,
    size: Dp,
    onClick: () -> Unit,
    big: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size).pressScale(source, 0.86f)
            .then(if (big) Modifier.shadow(24.dp, CircleShape, ambientColor = BoophisColors.Accent2, spotColor = BoophisColors.Accent) else Modifier)
            .clip(CircleShape)
            .then(
                if (big) Modifier.background(BoophisColors.AccentBrush)
                else Modifier.background(Color.Black.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            )
            .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content()
        else Icon(icon, desc, Modifier.size(size * 0.58f), tint = if (big) BoophisColors.OnAccent else Color.White)
    }
}

/** A "+30" / "−10" chip that floats up and fades after a seek tap. */
@Composable
private fun SeekBurst(trigger: Int, label: String, modifier: Modifier = Modifier) {
    val anim = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(700, easing = LinearEasing))
        }
    }
    Text(
        label,
        modifier.graphicsLayer {
            val p = anim.value
            alpha = if (p >= 1f) 0f else (1f - p) * 1.4f
            translationY = -p * 40.dp.toPx()
            scaleX = 0.9f + 0.3f * (1f - (1f - p) * (1f - p))
            scaleY = scaleX
        },
        style = MaterialTheme.typography.titleMedium.copy(brush = BoophisColors.AccentBrushH, fontWeight = FontWeight.Black),
    )
}

@Composable
private fun AnimatedVisibilityScope.Controls(
    state: PlayerState,
    player: PlayerController,
    mode: LayoutMode,
    padding: PaddingValues,
    onExit: () -> Unit,
    poke: () -> Unit,
) {
    val car = mode.isCar
    val item = state.item
    var back by remember { mutableIntStateOf(0) }
    var fwd by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.78f),
                0.3f to Color.Black.copy(alpha = 0.35f),
                0.65f to Color.Black.copy(alpha = 0.35f),
                1f to Color.Black.copy(alpha = 0.82f),
            ),
        ).padding(padding).padding(horizontal = mode.gutter, vertical = 8.dp),
    ) {
        // Top: back, title, next
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .animateEnterExit(enter = slideInVertically(tween(300)) { -it / 2 }, exit = slideOutVertically(tween(300)) { -it / 2 }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackButton(onExit)
            Column(Modifier.weight(1f)) {
                val title = (item as? Episode)?.showTitle ?: item?.title.orEmpty()
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item is Episode) {
                    Text(
                        "${item.code} · ${item.title}", style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.hasNext) {
                BoophisButton("Next episode", { poke(); player.skipToNext() }, primary = false, icon = Icons.Filled.SkipNext)
            }
        }

        // Centre transport
        Row(
            Modifier.align(Alignment.Center)
                .animateEnterExit(enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.8f), exit = scaleOut(targetScale = 0.9f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (car) 44.dp else 30.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                RoundIcon(Icons.Filled.Replay10, "Back 10 seconds", if (car) 80.dp else 58.dp, { poke(); back++; player.seekBy(-10_000) })
                SeekBurst(back, "−10", Modifier.offset(y = if (car) (-64).dp else (-48).dp))
            }
            RoundIcon(
                Icons.Filled.PlayArrow, if (state.isPlaying) "Pause" else "Play",
                if (car) 104.dp else 78.dp, { poke(); player.togglePlayPause() }, big = true,
            ) {
                AnimatedContent(
                    state.isPlaying,
                    transitionSpec = {
                        (scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f), initialScale = 0.4f) + fadeIn(tween(120))) togetherWith
                            (scaleOut(tween(120), targetScale = 0.4f) + fadeOut(tween(120)))
                    },
                    label = "pp",
                ) { playing ->
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                        Modifier.size(if (car) 60.dp else 46.dp), tint = BoophisColors.OnAccent,
                    )
                }
            }
            Box(contentAlignment = Alignment.Center) {
                RoundIcon(Icons.Filled.Forward30, "Forward 30 seconds", if (car) 80.dp else 58.dp, { poke(); fwd++; player.seekBy(30_000) })
                SeekBurst(fwd, "+30", Modifier.offset(y = if (car) (-64).dp else (-48).dp))
            }
        }

        // Bottom: elapsed, scrubber, remaining
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .animateEnterExit(enter = slideInVertically(tween(300)) { it / 2 }, exit = slideOutVertically(tween(300)) { it / 2 }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(formatClock(state.positionMs), style = MaterialTheme.typography.bodyLarge)
            Scrubber(
                state.positionMs, state.durationMs, car,
                onSeek = { poke(); player.seekTo(it) },
                modifier = Modifier.weight(1f),
            )
            Text(
                "-" + formatClock((state.durationMs - state.positionMs).coerceAtLeast(0)),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

/** Car: tap-to-seek bar (no dragging needed). Phone: standard Slider. */
@Composable
private fun Scrubber(positionMs: Long, durationMs: Long, car: Boolean, onSeek: (Long) -> Unit, modifier: Modifier) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    if (car) {
        // Glide to tapped positions instead of jumping.
        val shown by animateFloatAsState(fraction, tween(350, easing = FastOutSlowInEasing), label = "scrub")
        Box(
            modifier.height(76.dp).pointerInput(durationMs) {
                detectTapGestures { off ->
                    if (durationMs > 0) onSeek((off.x / size.width * durationMs).toLong().coerceIn(0, durationMs))
                }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.22f)))
            Box(
                Modifier.fillMaxWidth(shown).height(12.dp)
                    .shadow(10.dp, RoundedCornerShape(6.dp), spotColor = BoophisColors.Accent, ambientColor = BoophisColors.Accent2)
                    .clip(RoundedCornerShape(6.dp)).background(BoophisColors.AccentBrushH),
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.offset(x = (maxWidth - 30.dp) * shown).size(30.dp)
                        .shadow(10.dp, CircleShape, spotColor = BoophisColors.Accent)
                        .clip(CircleShape).background(Color.White)
                        .padding(7.dp).clip(CircleShape).background(BoophisColors.AccentBrush),
                )
            }
        }
    } else {
        var dragging by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            modifier = modifier,
            colors = SliderDefaults.colors(
                thumbColor = BoophisColors.Accent, activeTrackColor = BoophisColors.Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun AutoNextCard(an: AutoNextCountdown, player: PlayerController, mode: LayoutMode, padding: PaddingValues) {
    val e = an.next
    val car = mode.isCar
    // Smooth countdown bar: snaps to each second's value and glides linearly to the next one.
    val bar = remember { Animatable(an.secondsLeft / AUTO_NEXT_SECONDS.toFloat()) }
    LaunchedEffect(an.secondsLeft) {
        val from = an.secondsLeft / AUTO_NEXT_SECONDS.toFloat()
        bar.snapTo(from)
        launch { bar.animateTo(((an.secondsLeft - 1) / AUTO_NEXT_SECONDS.toFloat()).coerceAtLeast(0f), tween(1000, easing = LinearEasing)) }
    }
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.padding(padding).padding(16.dp).widthIn(max = if (car) 640.dp else 520.dp)
            .shadow(32.dp, shape, ambientColor = BoophisColors.Accent2, spotColor = BoophisColors.Accent)
            .clip(shape)
            .background(BoophisColors.Surface.copy(alpha = 0.96f))
            .border(1.dp, BoophisColors.Edge, shape)
            .clickable { player.cancelAutoNext() },
    ) {
        Row(
            Modifier.padding(if (car) 20.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (car) 20.dp else 14.dp),
        ) {
            Box(Modifier.width(if (car) 200.dp else 140.dp).aspectRatio(16f / 9f).artFrame(RoundedCornerShape(12.dp))) {
                ArtImage(e.artwork.thumb ?: e.artwork.backdrop ?: e.artwork.poster, e.code, Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.Center).size(if (car) 56.dp else 40.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        an.secondsLeft,
                        transitionSpec = {
                            (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                        },
                        label = "secs",
                    ) { s ->
                        Text(
                            s.toString(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.labelLarge.copy(brush = BoophisColors.AccentBrushH, fontWeight = FontWeight.Black),
                )
                Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(e.code, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.padding(horizontal = if (car) 20.dp else 16.dp).padding(bottom = if (car) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BoophisButton("Play now", { player.skipToNext() }, icon = Icons.Filled.PlayArrow)
            Text(
                "Tap to cancel", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.fillMaxWidth().height(if (car) 6.dp else 4.dp).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(bar.value).fillMaxHeight().background(BoophisColors.AccentBrushH))
        }
    }
}
