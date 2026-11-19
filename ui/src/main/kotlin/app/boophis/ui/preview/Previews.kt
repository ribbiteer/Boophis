// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.boophis.model.*
import app.boophis.ui.BoophisNavigator
import app.boophis.ui.BoophisRootImpl
import app.boophis.ui.BoophisUiDeps
import app.boophis.ui.LibraryStatusPreviewHost

private val carPadding = PaddingValues(start = 24.dp, top = 64.dp, end = 24.dp, bottom = 16.dp)

@Composable
private fun PreviewHost(
    mode: LayoutMode,
    route: Route,
    hasAccess: Boolean = true,
    empty: Boolean = false,
    playerState: PlayerState? = null,
    scanning: Boolean = false,
    folders: List<LibraryFolder> = FakeData.folders,
) {
    val lib = remember(empty, scanning, folders) {
        if (empty) FakeLibrary(emptyList(), emptyList(), emptyList(), scanning, folders = folders)
        else FakeLibrary(scanning = scanning, folders = folders)
    }
    val player = remember(playerState) { FakePlayer(lib, playerState ?: PlayerState()) }
    val deps = remember(lib, player) { BoophisUiDeps(lib, player, FakeWatch(lib), "0.1.0-preview") }
    val nav = remember(route) { BoophisNavigator(route) }
    BoophisRootImpl(
        deps = deps, mode = mode, navigator = nav,
        contentPadding = if (mode == LayoutMode.Car) carPadding else PaddingValues(),
        videoSurface = { m -> Box(m.background(Brush.linearGradient(listOf(Color(0xFF244B6B), Color(0xFF6B3A24))))) },
        onRequestStorageAccess = if (mode == LayoutMode.Phone) ({}) else null,
        hasStorageAccess = hasAccess, modifier = Modifier, handleBack = false,
    )
}

private val movie = Route.MovieDetail(2, focusResume = true)
private val show = Route.ShowDetail(101, focusEpisodeId = 1004)
private val movieState get() = FakeData.playerState(FakeData.movies[1], 33 * 60_000L)
private val episodeState get() = FakeData.playerState(FakeData.episodes[2], 12 * 60_000L)

private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

// ---- Home
@Preview(name = "Home phone", device = PHONE, showBackground = true) @Composable fun HomePhone() = PreviewHost(LayoutMode.Phone, Route.Home)
@Preview(name = "Home car 800x400", widthDp = 800, heightDp = 400) @Preview(name = "Home car 1280x580", widthDp = 1280, heightDp = 580)
@Composable fun HomeCar() = PreviewHost(LayoutMode.Car, Route.Home)

@Preview(name = "Empty phone", device = PHONE) @Composable fun EmptyPhone() = PreviewHost(LayoutMode.Phone, Route.Home, empty = true)
@Preview(name = "Empty car 800x400", widthDp = 800, heightDp = 400) @Composable fun EmptyCar() = PreviewHost(LayoutMode.Car, Route.Home, empty = true)

// ---- Library
@Preview(name = "Library phone", device = PHONE) @Composable fun LibraryPhone() = PreviewHost(LayoutMode.Phone, Route.Library(Route.Library.Kind.MOVIES))
@Preview(name = "Library car 800x400", widthDp = 800, heightDp = 400) @Preview(name = "Library car 1280x580", widthDp = 1280, heightDp = 580)
@Composable fun LibraryCar() = PreviewHost(LayoutMode.Car, Route.Library(Route.Library.Kind.MOVIES))

// ---- Movie detail
@Preview(name = "Movie phone", device = PHONE) @Composable fun MoviePhone() = PreviewHost(LayoutMode.Phone, movie)
@Preview(name = "Movie car 800x400", widthDp = 800, heightDp = 400) @Preview(name = "Movie car 1280x580", widthDp = 1280, heightDp = 580)
@Composable fun MovieCar() = PreviewHost(LayoutMode.Car, movie)

// ---- Show detail
@Preview(name = "Show phone", device = PHONE) @Composable fun ShowPhone() = PreviewHost(LayoutMode.Phone, show)
@Preview(name = "Show car 800x400", widthDp = 800, heightDp = 400) @Preview(name = "Show car 1280x580", widthDp = 1280, heightDp = 580)
@Composable fun ShowCar() = PreviewHost(LayoutMode.Car, show)

// ---- Player
@Preview(name = "Player phone", device = PHONE) @Composable fun PlayerPhone() = PreviewHost(LayoutMode.Phone, Route.Player, playerState = episodeState)
@Preview(name = "Player car 800x400", widthDp = 800, heightDp = 400) @Preview(name = "Player car 1280x580", widthDp = 1280, heightDp = 580)
@Composable fun PlayerCar() = PreviewHost(LayoutMode.Car, Route.Player, playerState = movieState)

@Preview(name = "Auto-next car", widthDp = 800, heightDp = 400)
@Composable fun AutoNextCar() = PreviewHost(
    LayoutMode.Car, Route.Player,
    playerState = episodeState.copy(autoNext = AutoNextCountdown(FakeData.episodes[3], 7)),
)

// ---- About
@Preview(name = "About phone", device = PHONE) @Composable fun AboutPhone() = PreviewHost(LayoutMode.Phone, Route.About)
@Preview(name = "About car 800x400", widthDp = 800, heightDp = 400) @Preview(name = "About car 1280x580", widthDp = 1280, heightDp = 580)
@Composable fun AboutCar() = PreviewHost(LayoutMode.Car, Route.About)

// ---- First run
@Preview(name = "First run phone", device = PHONE) @Composable fun FirstRunPhone() = PreviewHost(LayoutMode.Phone, Route.Home, hasAccess = false)
@Preview(name = "First run car 800x400", widthDp = 800, heightDp = 400) @Composable fun FirstRunCar() = PreviewHost(LayoutMode.Car, Route.Home, hasAccess = false)

// ---- Library folders (phone only) and the status line
@Preview(name = "Folders phone", device = PHONE)
@Composable fun FoldersPhone() = PreviewHost(LayoutMode.Phone, Route.LibraryFolders)

@Preview(name = "Folders phone, card removed + off", device = PHONE)
@Composable fun FoldersPhoneUnavailable() = PreviewHost(LayoutMode.Phone, Route.LibraryFolders, folders = FakeData.foldersWithMissingCard)

@Preview(name = "Folders phone, scanning", device = PHONE)
@Composable fun FoldersPhoneScanning() = PreviewHost(LayoutMode.Phone, Route.LibraryFolders, scanning = true)

@Preview(name = "Home phone, scanning", device = PHONE)
@Composable fun HomePhoneScanning() = PreviewHost(LayoutMode.Phone, Route.Home, scanning = true)

@Preview(name = "Home car 800x400, scanning", widthDp = 800, heightDp = 400)
@Composable fun HomeCarScanning() = PreviewHost(LayoutMode.Car, Route.Home, scanning = true)

@Preview(name = "Empty phone, scanning", device = PHONE)
@Composable fun EmptyPhoneScanning() = PreviewHost(LayoutMode.Phone, Route.Home, empty = true, scanning = true)

@Preview(name = "Status line", widthDp = 411, heightDp = 160, showBackground = true, backgroundColor = 0xFF07070C)
@Composable fun StatusLinePhone() = LibraryStatusPreviewHost(LayoutMode.Phone)

@Preview(name = "Status line car", widthDp = 800, heightDp = 160, showBackground = true, backgroundColor = 0xFF07070C)
@Composable fun StatusLineCar() = LibraryStatusPreviewHost(LayoutMode.Car)

// Frog rig art, static (previews never animate).
@Composable
private fun FrogPreview(art: app.boophis.ui.FrogArt) = app.boophis.ui.BoophisTheme(LayoutMode.Phone) {
    Box(Modifier.background(Color(0xFF0B0F10)).padding(16.dp)) {
        app.boophis.ui.Frog(art, Modifier.size(if (art == app.boophis.ui.FrogArt.Empty) 240.dp else 160.dp).aspectRatio(art.aspect))
    }
}
@Preview(name = "Frog mark", showBackground = true) @Composable fun FrogMarkPreview() = FrogPreview(app.boophis.ui.FrogArt.Mark)
@Preview(name = "Frog head", showBackground = true) @Composable fun FrogHeadPreview() = FrogPreview(app.boophis.ui.FrogArt.Head)
@Preview(name = "Frog empty", showBackground = true) @Composable fun FrogEmptyPreview() = FrogPreview(app.boophis.ui.FrogArt.Empty)
