// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.boophis.BoophisApp
import app.boophis.debugOrientation
import app.boophis.car.CarDebugConfig
import app.boophis.model.Route
import app.boophis.ui.BoophisPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug builds only.
 *  adb shell am broadcast -a app.boophis.DEBUG_CAR_CONFIG -p io.github.ribbiteer.boophis --ez texture true --ei vdflags 8 --ef flingsign -1 --el idleup 150
 *  adb shell am broadcast -a app.boophis.DEBUG_SNAPSHOT -p io.github.ribbiteer.boophis
 *  adb shell am broadcast -a app.boophis.DEBUG_THEME -p io.github.ribbiteer.boophis [--ez night false]
 */
class DebugReceiver : BroadcastReceiver() {
    /**
     * adb shell am broadcast -a app.boophis.DEBUG_PLAY -p io.github.ribbiteer.boophis --es title "Paper Harbor" [--el seek 150000]
     *   [--es episode S01E01] [--ez car true]   (car=true also opens the car Player route)
     *   --es cmd stop|pause|resume|seek  (with --el seek)
     */
    /** adb shell am broadcast -a app.boophis.DEBUG_NAV -p io.github.ribbiteer.boophis --es route show|movie|movies|shows|about|folders|player|home [--es title "..."]
     *  Also drives an open CarPreviewActivity. */
    private fun debugNav(context: Context, intent: Intent) {
        val app = context.applicationContext as BoophisApp
        val title = intent.getStringExtra("title")
        intent.getStringExtra("orient")?.let { o ->
            debugOrientation = when (o) {
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        val pending = goAsync()
        app.appScope.launch {
            try {
                val lib = app.library
                val route: Route? = when (intent.getStringExtra("route")) {
                    "home" -> Route.Home
                    "movies" -> Route.Library(Route.Library.Kind.MOVIES)
                    "shows" -> Route.Library(Route.Library.Kind.SHOWS)
                    "about" -> Route.About
                    "folders" -> Route.LibraryFolders
                    "player" -> Route.Player
                    "movie" -> lib.movies().first().firstOrNull { it.title.equals(title, true) }?.let { Route.MovieDetail(it.id) }
                    "show" -> lib.shows().first().firstOrNull { it.title.equals(title, true) }?.let { Route.ShowDetail(it.id) }
                    else -> null
                }
                if (route == null) { Log.w("BoophisDebug", "DEBUG_NAV: nothing for ${intent.extras}"); return@launch }
                if (route == Route.Home) app.openCarHome() else app.openCarRoute(route)
                if (route == Route.Home) app.phoneNavigator.home() else app.phoneNavigator.open(route)
                CarPreviewActivity.navigator?.let { if (route == Route.Home) it.home() else it.open(route) }
                Log.i("BoophisDebug", "DEBUG_NAV → $route")
            } finally {
                pending.finish()
            }
        }
    }

    private fun debugPlay(context: Context, intent: Intent) {
        val app = context.applicationContext as BoophisApp
        val seek = intent.getLongExtra("seek", -1L)
        when (intent.getStringExtra("cmd")) {
            "stop" -> { app.player.stop(); return }
            "pause" -> { app.player.pause(); return }
            "resume" -> { app.player.resume(); return }
            "seek" -> { app.player.seekTo(seek); return }
        }
        val title = intent.getStringExtra("title") ?: return
        val code = intent.getStringExtra("episode")
        val pending = goAsync()
        app.appScope.launch {
            try {
                val lib = app.library
                val ref = lib.movies().first().firstOrNull { it.title.equals(title, true) }?.ref
                    ?: lib.shows().first().firstOrNull { it.title.equals(title, true) }?.let { show ->
                        val d = lib.showDetail(show.id).first()
                        (d?.episodes?.firstOrNull { it.code.equals(code, true) } ?: d?.nextUp)?.ref
                    }
                if (ref == null) { Log.w("BoophisDebug", "DEBUG_PLAY: '$title' not found"); return@launch }
                app.player.play(ref)
                if (intent.getBooleanExtra("car", false)) {
                    app.openCarRoute(Route.Player)
                    CarPreviewActivity.navigator?.open(Route.Player)
                }
                if (seek >= 0) {
                    app.player.state.first { it.item?.ref == ref && it.durationMs > 0 && !it.isBuffering }
                    app.player.seekTo(seek)
                }
                Log.i("BoophisDebug", "DEBUG_PLAY $ref seek=$seek")
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "app.boophis.DEBUG_CAR_CONFIG" -> {
                CarDebugConfig.load(context)
                if (intent.hasExtra("texture")) CarDebugConfig.useTextureView = intent.getBooleanExtra("texture", false)
                if (intent.hasExtra("vdflags")) CarDebugConfig.virtualDisplayFlags = intent.getIntExtra("vdflags", CarDebugConfig.DEFAULT_VD_FLAGS)
                if (intent.hasExtra("flingsign")) CarDebugConfig.flingSign = intent.getFloatExtra("flingsign", 1f)
                if (intent.hasExtra("idleup")) CarDebugConfig.scrollIdleUpMs = intent.getLongExtra("idleup", 150L)
                CarDebugConfig.save(context)
                Log.i("BoophisDebug", "car config: $CarDebugConfig (applies on next surface)")
            }
            "app.boophis.DEBUG_SNAPSHOT" -> DebugSnapshot.capture(context)
            "app.boophis.DEBUG_PLAY" -> debugPlay(context, intent)
            "app.boophis.DEBUG_NAV" -> debugNav(context, intent)
            // Day/night for screenshots without touching the phone's dark-mode setting:
            // --ez night true|false forces a palette (as the car's day/night signal does); no extra = follow again.
            "app.boophis.DEBUG_THEME" -> {
                val night = if (intent.hasExtra("night")) intent.getBooleanExtra("night", true) else null
                BoophisPalette.setCarNight(night)
                Log.i("BoophisDebug", "palette forced: $night")
            }
        }
    }
}
