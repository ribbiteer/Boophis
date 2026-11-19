// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.boophis.model.*
import app.boophis.model.rules.ResumePolicy

@Composable
internal fun HomeScreen(actions: Actions, mode: LayoutMode) {
    val library = actions.deps.library
    val feed by remember(library) { library.home() }.collectOrNull()
    val scan by library.scanState.collectAsState()
    HomeContent(feed, scan, actions, mode)
}

@Composable
internal fun HomeContent(feed: HomeFeed?, scan: ScanState, actions: Actions, mode: LayoutMode) {
    val scanning = scan is ScanState.Scanning
    val nav = actions.nav

    @Composable
    fun TopBar() {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = mode.gutter).heightIn(min = mode.target),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Frog(FrogArt.Head, Modifier.size(if (mode.isCar) 44.dp else 34.dp), idle = FrogIdle.BlinkOnly, react = LocalFrogFound.current)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Boophis", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(brush = BoophisColors.AccentBrushH),
                )
                IconButton(actions::rescan) { Icon(Icons.Filled.Refresh, "Refresh library") }
                IconButton({ nav.open(Route.About) }) { Icon(Icons.Filled.Info, "About") }
            }
            ScanBar(scanning)
            LibraryStatusLine(scan, feed, Modifier.padding(bottom = 4.dp)) { nav.open(Route.LibraryFolders) }
        }
    }

    if (feed == null) {
        HomeSkeleton(mode)
        return
    }
    if (feed.isLibraryEmpty) {
        Column(Modifier.fillMaxSize()) {
            if (mode.isCar) ScanBar(scanning) else TopBar()
            CarReadyCard(actions.deps.carReady, Modifier.padding(vertical = 8.dp))
            EmptyStateLayout(
                title = if (scanning) "Looking for your videos…" else "No videos found yet",
                body = "Boophis looks in Movies, TV and Shows folders on your phone and SD card.",
            ) {
                if (mode.isCar) {
                    BoophisButton(if (scanning) "Updating…" else "Refresh library", actions::rescan, icon = Icons.Filled.Refresh)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Add folders in Boophis on your phone", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BoophisButton("Choose folders", { nav.open(Route.LibraryFolders) })
                        BoophisButton("Refresh", actions::rescan, primary = false)
                    }
                }
            }
        }
        return
    }

    val wide = mode.wideWidth
    val posterHeight = mode.posterWidth * 3 / 2
    val heroes = remember(feed) { heroItems(feed, actions) }
    var ambient by remember { mutableStateOf(heroes.firstOrNull()?.image) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Car screens are short and wide (800×480 … 1920×720): the spotlight takes ~60% of the visible
    // height so the first row's title still shows below it. Phone: a tall, near-square card.
    val heroHeight = if (mode.isCar) (maxHeight * 0.6f).coerceIn(210.dp, 340.dp)
    else (maxWidth * 0.95f).coerceIn(300.dp, 460.dp)
    AmbientBackground(ambient, strength = if (mode.isCar) 0.45f else 0.5f)
    ProvideEntrance {
    Column(Modifier.fillMaxSize()) {
        // Car: no title bar (the AA rail already shows the app); Rescan/About live at the bottom.
        if (!mode.isCar) TopBar() else ScanBar(scanning)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = if (mode.isCar) 4.dp else 4.dp, bottom = listBottomPadding(mode)),
            verticalArrangement = Arrangement.spacedBy(if (mode.isCar) 24.dp else 22.dp),
        ) {
            if (!mode.isCar && actions.deps.carReady != null) item(key = "carReady") {
                CarReadyCard(actions.deps.carReady, Modifier.entrance(0))
            }
            if (heroes.isNotEmpty()) item(key = "hero") {
                HomeHero(heroes, heroHeight, { ambient = it }, Modifier.entrance(0))
            }
            if (feed.continueWatching.isNotEmpty()) item {
                MediaRow("Continue watching", 1) {
                    items(feed.continueWatching, key = { "cw${it.ref}" }) { p ->
                        val (title, sub) = when (p) {
                            is Episode -> p.showTitle to "${p.code} · ${p.title}"
                            is Movie -> p.title to remainingLabel(p.watch)
                        }
                        WideCard(
                            p.artwork.thumb ?: p.artwork.backdrop ?: p.artwork.poster, title,
                            title, sub, p.watch.progress.takeIf { it > 0f },
                            { actions.play(p.ref) }, Modifier.width(wide),
                        )
                    }
                }
            }
            if (feed.nextUp.isNotEmpty()) item {
                MediaRow("Next up", 2) {
                    items(feed.nextUp, key = { "nu${it.id}" }) { e ->
                        WideCard(
                            e.artwork.thumb ?: e.artwork.backdrop ?: e.artwork.poster, e.showTitle,
                            e.showTitle, "${e.code} · ${e.title}", null,
                            { actions.play(e.ref) }, Modifier.width(wide),
                        )
                    }
                }
            }
            if (feed.recentlyAdded.isNotEmpty()) item {
                MediaRow("Recently added", 3) {
                    items(feed.recentlyAdded, key = { "ra${it::class.simpleName}${it.id}" }) { it ->
                        PosterCard(it, { openItem(nav, it) }, Modifier.width(mode.posterWidth))
                    }
                }
            }
            if (feed.movies.isNotEmpty()) item {
                MediaRow("Movies", 4) {
                    items(feed.movies, key = { "m${it.id}" }) {
                        PosterCard(it, { nav.open(Route.MovieDetail(it.id)) }, Modifier.width(mode.posterWidth))
                    }
                    item { SeeAllTile(mode.posterWidth, posterHeight) { nav.open(Route.Library(Route.Library.Kind.MOVIES)) } }
                }
            }
            if (feed.shows.isNotEmpty()) item {
                MediaRow("Shows", 5) {
                    items(feed.shows, key = { "s${it.id}" }) {
                        PosterCard(it, { nav.open(Route.ShowDetail(it.id)) }, Modifier.width(mode.posterWidth))
                    }
                    item { SeeAllTile(mode.posterWidth, posterHeight) { nav.open(Route.Library(Route.Library.Kind.SHOWS)) } }
                }
            }
            if (mode.isCar) item {
                Column {
                    LibraryStatusLine(scan, feed)
                    Row(Modifier.padding(horizontal = mode.gutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        BoophisTextButton(if (scanning) "Updating…" else "Refresh library", actions::rescan, icon = Icons.Filled.Refresh)
                        BoophisTextButton("About", { nav.open(Route.About) }, icon = Icons.Filled.Info)
                    }
                }
            }
        }
    }
    }
    }
}

/** Thin animated gradient bar while a scan runs (keeps its height so content doesn't jump). */
@Composable
internal fun ScanBar(scanning: Boolean) {
    Box(Modifier.fillMaxWidth().height(3.dp)) {
        androidx.compose.animation.AnimatedVisibility(
            scanning, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
        ) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(3.dp),
                color = BoophisColors.Accent, trackColor = BoophisColors.Accent2.copy(alpha = 0.2f),
            )
        }
    }
}

/** Shimmering placeholders shaped like the real Home while the first feed loads. */
@Composable
private fun HomeSkeleton(mode: LayoutMode) {
    BoxWithConstraints(Modifier.fillMaxSize().background(BoophisColors.Background)) {
        val heroHeight = if (mode.isCar) (maxHeight * 0.6f).coerceIn(210.dp, 340.dp)
        else (maxWidth * 0.95f).coerceIn(300.dp, 460.dp)
        Column(
            Modifier.fillMaxSize().padding(top = if (mode.isCar) 8.dp else mode.target + 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                Modifier.padding(horizontal = mode.gutter).fillMaxWidth().height(heroHeight)
                    .clip(RoundedCornerShape(20.dp)).shimmer(),
            )
            Box(Modifier.padding(horizontal = mode.gutter).width(180.dp).height(24.dp).clip(RoundedCornerShape(50)).shimmer())
            Row(Modifier.padding(horizontal = mode.gutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(6) {
                    Box(Modifier.width(mode.posterWidth).aspectRatio(2f / 3f).clip(CardShape).shimmer())
                }
            }
        }
    }
}

internal fun openItem(nav: BoophisNavigator, item: LibraryItem) {
    when (item) {
        is Movie -> nav.open(Route.MovieDetail(item.id))
        is Show -> nav.open(Route.ShowDetail(item.id))
    }
}

private fun remainingLabel(w: WatchState): String? {
    val left = w.durationMs - w.positionMs
    if (w.durationMs <= 0 || ResumePolicy.resumePosition(w) == null) return null
    return formatRuntime(left)?.let { "$it left" }
}
