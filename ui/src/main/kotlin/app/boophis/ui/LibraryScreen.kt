// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.boophis.model.*

@Composable
internal fun LibraryScreen(kind: Route.Library.Kind, actions: Actions) {
    val mode = LocalLayoutMode.current
    val library = actions.deps.library
    val items: List<LibraryItem>? by remember(library, kind) {
        when (kind) {
            Route.Library.Kind.MOVIES -> library.movies()
            Route.Library.Kind.SHOWS -> library.shows()
        }
    }.collectOrNull()
    LibraryContent(kind, items, actions, mode)
}

@Composable
internal fun LibraryContent(kind: Route.Library.Kind, items: List<LibraryItem>?, actions: Actions, mode: LayoutMode) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    AmbientBackground(items?.firstOrNull()?.artwork?.let { it.backdrop ?: it.poster }, strength = if (mode.isCar) 0.35f else 0.5f)
    LazyVerticalGrid(
        columns = if (mode.isCar) {
            GridCells.Fixed(carGridColumns(maxWidth.value, maxHeight.value, mode.gutter.value, 20f))
        } else {
            GridCells.Adaptive(100.dp)
        },
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = mode.gutter, end = mode.gutter, bottom = listBottomPadding(mode)),
        horizontalArrangement = Arrangement.spacedBy(if (mode.isCar) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (mode.isCar) 20.dp else 14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeader(
                if (kind == Route.Library.Kind.MOVIES) "Movies" else "Shows",
                { actions.nav.back() },
            )
        }
        itemsIndexed(items.orEmpty(), key = { _, it -> "${it::class.simpleName}${it.id}" }) { i, it ->
            val year = if (mode.isCar) null else when (it) {
                is Movie -> it.year
                is Show -> it.year
            }
            PosterCard(it, { openItem(actions.nav, it) }, Modifier.entrance(1 + i), subtitle = year?.toString())
        }
    }
    }
}

/**
 * Car grid columns, in plain dp so it can be unit-tested. As many columns as fit at 124 dp, like
 * `GridCells.Adaptive(124.dp)`. On short screens (6-inch units, about 360 dp tall) it adds columns,
 * with cards no narrower than 100 dp, until a whole first row (poster and title) fits under the
 * header without scrolling.
 */
internal fun carGridColumns(widthDp: Float, heightDp: Float, gutterDp: Float, spacingDp: Float): Int {
    val avail = widthDp - 2 * gutterDp + spacingDp
    fun fit(card: Float) = (avail / (card + spacingDp)).toInt().coerceAtLeast(1)
    val maxCard = (heightDp - CAR_GRID_ROW_CHROME_DP) * 2f / 3f
    var n = fit(124f)
    while (n < fit(100f) && avail / n - spacingDp > maxCard) n++
    return n
}

/** Header above the first row plus the title under a poster, in dp (measured on the DHU). */
private const val CAR_GRID_ROW_CHROME_DP = 144f
