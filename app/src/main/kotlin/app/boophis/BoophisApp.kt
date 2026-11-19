// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import app.boophis.car.CarAppHost
import app.boophis.car.CarDebugConfig
import app.boophis.car.CarEnv
import app.boophis.library.BoophisLibrary
import app.boophis.model.Episode
import app.boophis.model.LayoutMode
import app.boophis.model.Movie
import app.boophis.model.Route
import app.boophis.model.ScanReason
import app.boophis.player.BoophisPlayer
import app.boophis.player.PlaybackService
import app.boophis.player.PlaybackSession
import app.boophis.player.VideoSurfaceView
import app.boophis.ui.BoophisNavigator
import app.boophis.ui.BoophisRoot
import app.boophis.ui.BoophisUiDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Process-wide graph: library, player, media session; also the [CarAppHost] for car/. */
class BoophisApp : Application(), CarAppHost {
    val appScope: CoroutineScope = MainScope()

    lateinit var library: BoophisLibrary
        private set
    lateinit var player: BoophisPlayer
        private set
    lateinit var uiDeps: BoophisUiDeps
        private set
    private lateinit var playbackSession: PlaybackSession
    lateinit var carReady: CarReadyInstaller
        private set

    /** Phone navigation survives activity recreation. */
    val phoneNavigator = BoophisNavigator()

    override fun onCreate() {
        super.onCreate()
        library = BoophisLibrary.create(this, appScope)
        player = BoophisPlayer(this, library, library, appScope)
        carReady = CarReadyInstaller(this, appScope)
        uiDeps = BoophisUiDeps(
            library, player, library, packageManager.getPackageInfo(packageName, 0).versionName ?: "dev",
            carReady = carReady,
        )
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        playbackSession = PlaybackSession(this, player, launch)
        appScope.launch {
            player.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                if (playing) PlaybackService.start(this@BoophisApp, playbackSession.session)
            }
        }
        appScope.launch {
            player.state.map { it.item == null }.distinctUntilChanged().collect { idle ->
                if (idle) PlaybackService.stop(this@BoophisApp)
            }
        }
        if (library.hasStorageAccess()) library.rescan(ScanReason.APP_START)
    }

    // ---- CarAppHost ----

    override val mediaPlayer: Player get() = player.exoPlayer

    override val isPlaying: StateFlow<Boolean> by lazy {
        player.state.map { it.isPlaying }.stateIn(appScope, SharingStarted.Eagerly, false)
    }

    override fun togglePlayPause() = player.togglePlayPause()

    private var carJob: Job? = null

    override fun onCarSessionStarted(env: CarEnv) {
        val nav = carNavigator(env)
        currentCarEnv = env
        if (library.hasStorageAccess()) library.rescan(ScanReason.CAR_SESSION_START)
        carJob?.cancel()
        carJob = appScope.launch {
            env.homeRequests.collect {
                if (nav.current == Route.Player) player.stop()
                nav.home()
            }
        }
        appScope.launch {
            if (player.state.value.isActive) {
                nav.replaceAll(Route.Home, Route.Player)
                return@launch
            }
            // KICKOFF §6: open straight to the in-progress title's detail page, Resume focused. No auto-play.
            when (val item = library.lastInProgress()) {
                is Movie -> nav.replaceAll(Route.Home, Route.MovieDetail(item.id, focusResume = true))
                is Episode -> nav.replaceAll(
                    Route.Home,
                    Route.ShowDetail(item.showId, focusEpisodeId = item.id, focusResume = true),
                )
                null -> Unit
            }
            Log.i("BoophisApp", "car session start → ${nav.current}")
        }
    }

    override fun onCarBack(env: CarEnv): Boolean {
        val nav = carNavigator(env)
        if (nav.current == Route.Player) player.stop()
        return nav.back()
    }

    override fun onCarNightMode(night: Boolean) = app.boophis.ui.BoophisPalette.setCarNight(night)

    override fun onCarSessionEnded() {
        app.boophis.ui.BoophisPalette.setCarNight(null)
        carJob?.cancel()
        carJob = null
        currentCarEnv = null
        player.pauseAndSave()
    }

    @Composable
    override fun CarContent(env: CarEnv) {
        val nav = remember(env) { carNavigator(env) }
        val density = LocalDensity.current
        // Stable area = never covered (action strip included) and doesn't jump when AA auto-hides the strip.
        val area = env.stableArea ?: env.visibleArea
        val padding = with(density) {
            // No insets reported (legacy projection route): small margin clear of AA's rounded corners.
            if (area == null) PaddingValues(12.dp) else PaddingValues(
                start = area.left.toDp(),
                top = area.top.toDp(),
                end = (env.surfaceWidth - area.right).coerceAtLeast(0).toDp(),
                bottom = (env.surfaceHeight - area.bottom).coerceAtLeast(0).toDp(),
            )
        }
        BoophisRoot(
            deps = uiDeps,
            mode = LayoutMode.Car,
            navigator = nav,
            contentPadding = padding,
            videoSurface = { m -> VideoSurface(m, CarDebugConfig.useTextureView) },
            onRequestStorageAccess = null,
            hasStorageAccess = library.hasStorageAccess(),
        )
    }

    private var currentCarEnv: CarEnv? = null

    /** Opens [route] on the car screen if a car session is active (debug/testing helper). */
    fun openCarRoute(route: Route) {
        currentCarEnv?.let { carNavigator(it).open(route) }
    }

    fun openCarHome() {
        currentCarEnv?.let { carNavigator(it).home() }
    }

    private fun carNavigator(env: CarEnv): BoophisNavigator =
        env.retained.getOrPut("navigator") { BoophisNavigator() } as BoophisNavigator

    @Composable
    fun VideoSurface(modifier: Modifier, useTexture: Boolean = false) {
        AndroidView(
            factory = { ctx -> VideoSurfaceView(ctx, useTexture) },
            update = { it.player = player.exoPlayer },
            modifier = modifier,
        )
    }
}
