// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.boophis.model.*
import kotlinx.coroutines.delay

/** One spotlight entry on Home. */
internal data class HeroItem(
    val key: String,
    val eyebrow: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val progress: Float?,
    val buttonLabel: String,
    val onPlay: () -> Unit,
    val onOpen: () -> Unit,
    /** False when the button opens a page instead of playing (no eye then). */
    val plays: Boolean = true,
)

/**
 * Spotlight order follows Boophis's purpose: what you were watching first (the car connects →
 * you resume), then the next episode of a show you're in, then what's new.
 */
internal fun heroItems(feed: HomeFeed, actions: Actions): List<HeroItem> {
    val nav = actions.nav
    val out = LinkedHashMap<String, HeroItem>()
    feed.continueWatching.take(3).forEach { p ->
        when (p) {
            is Movie -> out["m${p.id}"] = HeroItem(
                "m${p.id}", "Continue watching", p.title,
                metaLine(p.year?.toString(), formatRuntime(p.watch.durationMs - p.watch.positionMs)?.let { "$it left" }),
                p.artwork.backdrop ?: p.artwork.thumb ?: p.artwork.poster, p.watch.progress,
                playLabel(p.watch), { actions.play(p.ref) }, { nav.open(Route.MovieDetail(p.id, focusResume = true)) },
            )
            is Episode -> out["s${p.showId}"] = HeroItem(
                "s${p.showId}", "Continue watching", p.showTitle, "${p.code} · ${p.title}",
                p.artwork.backdrop ?: p.artwork.thumb ?: p.artwork.poster, p.watch.progress,
                playLabel(p.watch), { actions.play(p.ref) },
                { nav.open(Route.ShowDetail(p.showId, focusEpisodeId = p.id, focusResume = true)) },
            )
        }
    }
    feed.nextUp.take(2).forEach { e ->
        out.getOrPut("s${e.showId}") {
            HeroItem(
                "s${e.showId}", "Next up", e.showTitle, "${e.code} · ${e.title}",
                e.artwork.backdrop ?: e.artwork.thumb ?: e.artwork.poster, null,
                "Play ${e.code}", { actions.play(e.ref) },
                { nav.open(Route.ShowDetail(e.showId, focusEpisodeId = e.id)) },
            )
        }
    }
    feed.recentlyAdded.take(4).forEach { item ->
        when (item) {
            is Movie -> out.getOrPut("m${item.id}") {
                HeroItem(
                    "m${item.id}", "New in your library", item.title,
                    metaLine(item.year?.toString(), formatRuntime(item.runtimeMs), item.genres.firstOrNull()),
                    item.artwork.backdrop ?: item.artwork.poster, null,
                    playLabel(item.watch), { actions.play(item.ref) }, { nav.open(Route.MovieDetail(item.id)) },
                )
            }
            is Show -> out.getOrPut("s${item.id}") {
                HeroItem(
                    "s${item.id}", "New in your library", item.title,
                    metaLine(
                        item.year?.toString(),
                        if (item.seasonCount > 0) "${item.seasonCount} season" + if (item.seasonCount > 1) "s" else "" else null,
                    ),
                    item.artwork.backdrop ?: item.artwork.poster, null,
                    "Open", { nav.open(Route.ShowDetail(item.id)) }, { nav.open(Route.ShowDetail(item.id)) }, plays = false,
                )
            }
        }
    }
    return out.values.take(5)
}

private const val ROTATE_MS = 8_000L

/**
 * Home spotlight. Phone: rotates through [items] with a crossfade. Car: pinned to the first item
 * (the resume target) — no rotation, no horizontal paging; one big button, whole card tappable.
 */
@Composable
internal fun HomeHero(
    items: List<HeroItem>,
    height: Dp,
    onCurrentImage: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val mode = LocalLayoutMode.current
    val car = mode.isCar
    var index by rememberSaveable { mutableIntStateOf(0) }
    if (index >= items.size) index = 0
    val rotate = !car && items.size > 1
    LaunchedEffect(rotate, items.size) {
        while (rotate) {
            delay(ROTATE_MS)
            index = (index + 1) % items.size
        }
    }
    val current = items[index]
    LaunchedEffect(current.image) { onCurrentImage(current.image) }

    val shape = RoundedCornerShape(if (car) 22.dp else 20.dp)
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = mode.gutter).height(height)
            .artFrame(shape).cardClickable(current.onOpen),
    ) {
        val narrow = maxWidth < 560.dp
        Crossfade(current, animationSpec = tween(900), label = "heroArt") { item ->
            Box(Modifier.fillMaxSize().kenBurns(item.key.hashCode())) {
                ArtImage(item.image, item.title, Modifier.fillMaxSize(), showTitle = false)
            }
        }
        // Readability scrims: strong on the text side, so the text is legible at arm's length.
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to BoophisColors.Background.copy(alpha = 0.92f),
                    (if (narrow) 0.75f else 0.55f) to BoophisColors.Background.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to BoophisColors.Background.copy(alpha = 0.85f)),
            ),
        )

        AnimatedContent(
            current,
            transitionSpec = {
                (fadeIn(tween(450, delayMillis = 200)) + slideInVertically(tween(450, delayMillis = 200)) { it / 6 }) togetherWith
                    fadeOut(tween(250))
            },
            contentKey = { it.key },
            label = "heroText",
            modifier = Modifier.fillMaxHeight().fillMaxWidth(if (narrow) 1f else 0.62f),
        ) { item ->
            Column(
                Modifier.fillMaxSize().padding(if (car) 24.dp else 20.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    item.eyebrow,
                    style = MaterialTheme.typography.titleSmall,
                    color = BoophisColors.Accent,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.title,
                    style = if (car) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (!item.subtitle.isNullOrBlank()) {
                    Text(
                        item.subtitle, style = MaterialTheme.typography.bodyLarge,
                        color = BoophisColors.TextPrimary.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(if (car) 14.dp else 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The page's one orchestrated moment: the eye's ring draws in to the watch progress.
                    BoophisButton(
                        item.buttonLabel, item.onPlay,
                        icon = if (item.plays) null else Icons.Filled.ChevronRight,
                        leading = if (!item.plays) null else {
                            {
                                EyeBadge(
                                    item.progress?.takeIf { it > 0f } ?: 0f,
                                    Modifier.size(if (car) 48.dp else 34.dp), reveal = true,
                                )
                            }
                        },
                    )
                    // Phone only: car keeps one big action (the whole card already opens details).
                    if (!car) InfoButton(item.onOpen)
                }
            }
        }

        if (rotate) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(18.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.indices.forEach { i ->
                    val w by animateDpAsState(if (i == index) 22.dp else 7.dp, tween(350), label = "dot")
                    Box(
                        Modifier.height(7.dp).width(w).background(
                            if (i == index) BoophisColors.AccentBrushH else Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.35f)),
                            ),
                            RoundedCornerShape(50),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoButton(onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.size(52.dp).pressScale(source, 0.88f).clip(androidx.compose.foundation.shape.CircleShape)
            .background(BoophisColors.SurfaceHigh.copy(alpha = 0.78f))
            .border(1.dp, BoophisColors.Edge, androidx.compose.foundation.shape.CircleShape)
            .clickable(source, androidx.compose.foundation.LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(Icons.Filled.Info, "Details", tint = BoophisColors.TextPrimary)
    }
}

private fun Modifier.clipPill(): Modifier = clip(RoundedCornerShape(50))
