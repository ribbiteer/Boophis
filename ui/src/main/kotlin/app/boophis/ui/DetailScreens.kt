// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.boophis.model.*
import app.boophis.model.rules.ResumePolicy
import kotlinx.coroutines.launch

/**
 * Shared detail header. Car: back button, poster and all info in one row (info scrolls with the page).
 * Phone: back button on top, poster beside title/meta, body underneath.
 */
@Composable
private fun DetailHeader(
    backdrop: String?,
    poster: String?,
    title: String,
    mode: LayoutMode,
    onBack: () -> Unit,
    meta: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
) {
    BackdropBox(backdrop, title, Modifier.fillMaxWidth().heightIn(min = minHeight)) {
        if (mode.isCar) {
            Row(
                Modifier.fillMaxWidth().padding(mode.gutter),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BackButton(onBack, Modifier.entrance(0))
                ArtImage(poster, title, Modifier.width(mode.posterWidth).aspectRatio(2f / 3f).entrance(1).posterGlow())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.entrance(2), verticalArrangement = Arrangement.spacedBy(6.dp)) { meta() }
                    Column(Modifier.entrance(3), verticalArrangement = Arrangement.spacedBy(6.dp)) { body() }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(mode.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BackButton(onBack, Modifier.entrance(0))
                Spacer(Modifier.height(48.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ArtImage(poster, title, Modifier.width(130.dp).aspectRatio(2f / 3f).entrance(1).posterGlow())
                    Column(
                        Modifier.weight(1f).align(Alignment.Bottom).entrance(2),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) { meta() }
                }
                Column(Modifier.entrance(3), verticalArrangement = Arrangement.spacedBy(12.dp)) { body() }
            }
        }
    }
}

@Composable
private fun TitleBlock(title: String, meta: String, genres: List<String>) {
    Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    if (meta.isNotEmpty()) {
        Text(meta, style = MaterialTheme.typography.bodyLarge, color = BoophisColors.TextPrimary.copy(alpha = 0.8f))
    }
    if (genres.isNotEmpty()) {
        Text(
            genres.joinToString(", "),
            style = MaterialTheme.typography.labelLarge, color = BoophisColors.Accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Plot(plot: String?, mode: LayoutMode) {
    if (plot.isNullOrBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    Text(
        plot,
        style = MaterialTheme.typography.bodyMedium,
        color = BoophisColors.TextPrimary.copy(alpha = 0.86f),
        maxLines = if (mode.isCar || !expanded) 4 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
    )
    if (!mode.isCar && (truncated || expanded)) {
        BoophisTextButton(if (expanded) "Less" else "More", { expanded = !expanded })
    }
}

/** Runs [block] once focusRequester is attached, to give the Resume button initial emphasis. */
@Composable
private fun rememberResumeFocus(enabled: Boolean): FocusRequester {
    val fr = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (enabled) runCatching { fr.requestFocus() }
    }
    return fr
}

// --------------------------------------------------------------------------------------------
// Movie
// --------------------------------------------------------------------------------------------

@Composable
internal fun MovieDetailScreen(route: Route.MovieDetail, actions: Actions, mode: LayoutMode) {
    val library = actions.deps.library
    val movie by remember(library, route.movieId) { library.movie(route.movieId) }.collectOrNull()
    val scope = rememberCoroutineScope()
    MovieDetailContent(
        movie, route.focusResume, actions, mode,
        onToggleWatched = { m -> scope.launch { actions.deps.watch.setWatched(m.ref, !m.watch.watched) } },
    )
}

@Composable
internal fun MovieDetailContent(
    movie: Movie?,
    focusResume: Boolean,
    actions: Actions,
    mode: LayoutMode,
    onToggleWatched: (Movie) -> Unit,
) {
    if (movie == null) {
        Box(Modifier.fillMaxSize()) { BackButton({ actions.nav.back() }, Modifier.padding(mode.gutter)) }
        return
    }
    val resume = ResumePolicy.resumePosition(movie.watch)
    val fr = rememberResumeFocus(focusResume)
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The movie page is a single header: let its backdrop fill the screen (no empty black half).
    val viewport = maxHeight
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DetailHeader(
                minHeight = viewport,
                backdrop = movie.artwork.backdrop, poster = movie.artwork.poster, title = movie.title,
                mode = mode, onBack = { actions.nav.back() },
                meta = {
                    TitleBlock(
                        movie.title,
                        metaLine(movie.year?.toString(), formatRuntime(movie.runtimeMs), movie.contentRating, ratingLabel(movie.rating)),
                        movie.genres,
                    )
                },
                body = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoophisButton(
                            playLabel(movie.watch), { actions.play(movie.ref) },
                            Modifier.focusRequester(fr), icon = Icons.Filled.PlayArrow,
                            emphasized = focusResume && resume != null,
                        )
                        if (resume != null) {
                            BoophisButton("Play from start", { actions.play(movie.ref, fromStart = true) }, primary = false)
                        }
                    }
                    Plot(movie.plot, mode)
                    BoophisTextButton(
                        if (movie.watch.watched) "Mark unwatched" else "Mark watched",
                        { onToggleWatched(movie) },
                    )
                    Spacer(Modifier.height(listBottomPadding(mode)))
                },
            )
        }
    }
    }
}

// --------------------------------------------------------------------------------------------
// Show
// --------------------------------------------------------------------------------------------

@Composable
internal fun ShowDetailScreen(route: Route.ShowDetail, actions: Actions, mode: LayoutMode) {
    val library = actions.deps.library
    val detail by remember(library, route.showId) { library.showDetail(route.showId) }.collectOrNull()
    ShowDetailContent(detail, route, actions, mode)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShowDetailContent(detail: ShowDetail?, route: Route.ShowDetail, actions: Actions, mode: LayoutMode) {
    if (detail == null) {
        Box(Modifier.fillMaxSize()) { BackButton({ actions.nav.back() }, Modifier.padding(mode.gutter)) }
        return
    }
    val show = detail.show
    val focusEpisode = route.focusEpisodeId?.let { id -> detail.episodes.firstOrNull { it.id == id } }
    val seasons = detail.seasons.sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val seasonNumber = selected
        ?: focusEpisode?.seasonNumber
        ?: detail.nextUp?.seasonNumber
        ?: seasons.firstOrNull()?.number
    val episodes = remember(detail.episodes, seasonNumber) { detail.episodes.filter { it.seasonNumber == seasonNumber } }

    val listState = rememberLazyListState()
    // Items before the episode list: header, tabs.
    LaunchedEffect(focusEpisode?.id, episodes.size) {
        val idx = episodes.indexOfFirst { it.id == focusEpisode?.id }
        if (idx >= 0) listState.scrollToItem(2 + idx)
    }
    val nextUp = detail.nextUp
    val fr = rememberResumeFocus(route.focusResume)

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = listBottomPadding(mode))) {
        item {
            DetailHeader(
                show.artwork.backdrop, show.artwork.poster, show.title, mode, { actions.nav.back() },
                meta = {
                    TitleBlock(
                        show.title,
                        metaLine(
                            show.year?.toString(),
                            if (show.seasonCount > 0) "${show.seasonCount} season" + if (show.seasonCount > 1) "s" else "" else null,
                            show.contentRating, ratingLabel(show.rating),
                        ),
                        show.genres,
                    )
                },
                body = {
                    val target = nextUp ?: detail.episodes.firstOrNull { it.seasonNumber > 0 }
                    if (target != null) {
                        val resume = ResumePolicy.resumePosition(target.watch)
                        val label = when {
                            resume != null -> "Resume ${formatClock(resume)}"
                            nextUp != null -> "Next up"
                            else -> "Watch again"
                        }
                        BoophisButton(
                            label, { actions.play(target.ref) },
                            Modifier.focusRequester(fr), icon = Icons.Filled.PlayArrow,
                            emphasized = route.focusResume && resume != null,
                            subtitle = "${target.code} · ${target.title}",
                        )
                    }
                    Plot(show.plot, mode)
                },
            )
        }
        item {
            if (seasons.size > 1) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = mode.gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    seasons.forEach { s ->
                        SeasonChip(
                            if (s.number == 0) "Specials" else "Season ${s.number}",
                            on = s.number == seasonNumber, mode = mode,
                        ) { selected = s.number }
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
        itemsIndexed(episodes, key = { _, e -> e.id }) { i, e ->
            EpisodeRow(
                e, e.id == focusEpisode?.id, mode,
                Modifier.animateItem(fadeInSpec = tween(320, delayMillis = i.coerceAtMost(8) * 35)).entrance(4 + i),
            ) { actions.play(e.ref) }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, highlight: Boolean, mode: LayoutMode, modifier: Modifier, onClick: () -> Unit) {
    val progress = e.watch.progress.takeIf { ResumePolicy.hasResumePoint(e.watch) }
    Row(
        modifier.fillMaxWidth()
            .padding(horizontal = mode.gutter - 8.dp, vertical = 2.dp)
            .cardClickable(onClick)
            .then(
                if (highlight) Modifier.background(
                    Brush.horizontalGradient(listOf(BoophisColors.Accent.copy(alpha = 0.18f), Color.Transparent)),
                ) else Modifier,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .heightIn(min = mode.target),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.width(if (mode.isCar) 160.dp else 128.dp).aspectRatio(16f / 9f).artFrame(RoundedCornerShape(10.dp))) {
            ArtImage(e.artwork.thumb ?: e.artwork.backdrop ?: e.artwork.poster, e.code, Modifier.fillMaxSize())
            if (progress != null) {
                GlowProgress(progress, Modifier.align(Alignment.BottomCenter), height = 5.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                metaLine(e.code, formatRuntime(e.runtimeMs)),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (highlight) BoophisColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (!mode.isCar && !e.plot.isNullOrBlank()) {
                Text(
                    e.plot!!, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (e.watch.watched) {
            Icon(
                Icons.Filled.CheckCircle, "Watched", tint = BoophisColors.Accent,
                modifier = Modifier.size(if (mode.isCar) 36.dp else 24.dp)
                    .shadow(8.dp, CircleShape, spotColor = BoophisColors.Accent),
            )
        }
    }
}

@Composable
private fun SeasonChip(label: String, on: Boolean, mode: LayoutMode, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val fg by animateColorAsState(if (on) BoophisColors.OnAccent else BoophisColors.TextPrimary, tween(250), label = "chipFg")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.heightIn(min = mode.target).pressScale(source)
            .then(if (on) Modifier.shadow(12.dp, shape, spotColor = BoophisColors.Accent, ambientColor = BoophisColors.Accent2) else Modifier)
            .clip(shape)
            .then(
                if (on) Modifier.background(BoophisColors.AccentBrushH)
                else Modifier.background(BoophisColors.SurfaceHigh.copy(alpha = 0.8f)).border(1.dp, BoophisColors.Edge, shape),
            )
            .clickable(source, LocalIndication.current) { onClick() }
            .padding(horizontal = if (mode.isCar) 28.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Poster with a warm under-glow. */
private fun Modifier.posterGlow(): Modifier = this
    .shadow(24.dp, RoundedCornerShape(14.dp), ambientColor = BoophisColors.Accent2, spotColor = BoophisColors.Accent)
    .artFrame()
