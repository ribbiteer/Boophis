// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.boophis.model.*

class BoophisUiDeps(
    val library: MediaLibrary,
    val player: PlayerController,
    val watch: WatchStateStore,
    val appVersion: String,
    /** Phone-only "Make Boophis car-ready" prompt; null where the app can't offer it. */
    val carReady: CarReadyPrompt? = null,
    /** Phone-only "Add folder" chooser; null in car mode and in previews. */
    val folderPicker: FolderPicker? = null,
) {
    fun withFolderPicker(picker: FolderPicker?) = BoophisUiDeps(library, player, watch, appVersion, carReady, picker)
}

/** System folder chooser, implemented by the activity. */
interface FolderPicker {
    /**
     * Opens the chooser. [onResult] gets an absolute path, or null when the choice cannot be mapped to a
     * path on the phone or SD card (the UI then says so). It is not called at all when the user cancels.
     */
    fun pick(onResult: (path: String?) -> Unit)
}

/** Reinstall-through-the-stock-installer prompt (Android Auto only lists Play-attributed installs). */
interface CarReadyPrompt {
    val needed: kotlinx.coroutines.flow.StateFlow<Boolean>
    val busy: kotlinx.coroutines.flow.StateFlow<Boolean>
    fun start()
}

internal class Actions(val deps: BoophisUiDeps, val nav: BoophisNavigator) {
    fun play(ref: MediaRef, fromStart: Boolean = false) {
        deps.player.play(ref, fromStart)
        nav.open(Route.Player)
    }

    fun leavePlayer() {
        deps.player.stop()
        nav.back()
    }

    fun rescan() = deps.library.rescan(ScanReason.MANUAL)
}

@Composable
fun BoophisRoot(
    deps: BoophisUiDeps,
    mode: LayoutMode,
    navigator: BoophisNavigator,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    videoSurface: @Composable (Modifier) -> Unit,
    onRequestStorageAccess: (() -> Unit)? = null,
    hasStorageAccess: Boolean = true,
    modifier: Modifier = Modifier,
) = BoophisRootImpl(
    deps, mode, navigator, contentPadding, videoSurface, onRequestStorageAccess,
    hasStorageAccess, modifier, handleBack = mode == LayoutMode.Phone,
)

/** [handleBack] is false in car mode (no OnBackPressedDispatcherOwner) and in IDE previews. */
@Composable
internal fun BoophisRootImpl(
    deps: BoophisUiDeps,
    mode: LayoutMode,
    navigator: BoophisNavigator,
    contentPadding: PaddingValues,
    videoSurface: @Composable (Modifier) -> Unit,
    onRequestStorageAccess: (() -> Unit)?,
    hasStorageAccess: Boolean,
    modifier: Modifier,
    handleBack: Boolean,
) {
    BoophisTheme(mode) {
        val actions = remember(deps, navigator) { Actions(deps, navigator) }
        val route = navigator.current
        // The player is always night: video reads best on dark, whatever the car or phone says.
        // Each root adds and removes only its own count, also when it is disposed mid-playback.
        val inPlayer = route is Route.Player
        DisposableEffect(inPlayer) {
            if (inPlayer) BoophisPalette.playerRoots++
            onDispose { if (inPlayer) BoophisPalette.playerRoots-- }
        }

        if (handleBack) {
            BackHandler(enabled = navigator.backStack.size > 1) {
                if (navigator.current is Route.Player) actions.leavePlayer() else navigator.back()
            }
        }

        val insetModifier = when {
            mode.isCar -> Modifier.padding(contentPadding)
            else -> Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
        }
        val holder = rememberSaveableStateHolder()

        // Counts "the first scan found videos": empty -> non-empty while running (not at start-up).
        var found by remember { mutableIntStateOf(0) }
        LaunchedEffect(deps.library) {
            var wasEmpty: Boolean? = null
            deps.library.home().collect { feed ->
                if (wasEmpty == true && !feed.isLibraryEmpty) {
                    frogFoundAtMs = android.os.SystemClock.uptimeMillis()
                    found++
                }
                wasEmpty = feed.isLibraryEmpty
            }
        }

        // Phone: the setup checklist stands in front of everything until it's finished.
        val setup = if (mode.isCar) null else rememberSetupGate()
        val carNeeded = deps.carReady?.needed?.collectAsState()?.value ?: false
        val gated = setup?.visible(hasStorageAccess, carNeeded) ?: !hasStorageAccess

        // Direction for the browse transition: a longer back stack = forward.
        var lastDepth by remember { mutableIntStateOf(navigator.backStack.size) }
        val forward = navigator.backStack.size >= lastDepth
        SideEffect { lastDepth = navigator.backStack.size }

        CompositionLocalProvider(LocalFrogFound provides found) {
        Box(modifier.fillMaxSize().background(BoophisColors.Background)) {
            // Outer: fade only between first-run / browse / player. The player's SurfaceView must not
            // be scaled or slid (it isn't part of the Compose layer), and a plain fade is quick.
            AnimatedContent(
                targetState = if (gated) null else route,
                contentKey = { r -> when { r == null -> 0; r is Route.Player -> 1; else -> 2 } },
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
                label = "screen",
            ) { r ->
                when {
                    r == null -> Box(insetModifier.fillMaxSize()) {
                        if (setup != null) ProvideEntrance {
                            SetupScreen(
                                setup, hasStorageAccess, onRequestStorageAccess, deps.carReady, deps.library,
                                onChooseFolders = { setup.finish(); navigator.open(Route.LibraryFolders) },
                            )
                        } else FirstRunScreen(mode, onRequestStorageAccess)
                    }
                    r is Route.Player -> PlayerScreen(
                        actions = actions,
                        mode = mode,
                        overlayPadding = if (mode.isCar) contentPadding else WindowInsets.safeDrawing.asPaddingValues(),
                        videoSurface = videoSurface,
                    )
                    else -> AnimatedContent(
                        targetState = r,
                        transitionSpec = {
                            // Short and snappy: car taps already carry projection latency.
                            val dir = if (forward) 1 else -1
                            (fadeIn(tween(280, delayMillis = 40)) +
                                slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { dir * it / 10 } +
                                scaleIn(tween(320), initialScale = 0.97f)) togetherWith
                                (fadeOut(tween(180)) + slideOutHorizontally(tween(320)) { -dir * it / 14 })
                        },
                        label = "browse",
                    ) { br ->
                        Box(insetModifier.fillMaxSize()) {
                            holder.SaveableStateProvider(br.toString()) {
                                ProvideEntrance {
                                    when (br) {
                                        Route.Home -> HomeScreen(actions, mode)
                                        is Route.Library -> LibraryScreen(br.kind, actions)
                                        is Route.MovieDetail -> MovieDetailScreen(br, actions, mode)
                                        is Route.ShowDetail -> ShowDetailScreen(br, actions, mode)
                                        Route.About -> AboutScreen(actions, deps.appVersion)
                                        Route.LibraryFolders -> LibraryFoldersScreen(actions)
                                        Route.Player -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
