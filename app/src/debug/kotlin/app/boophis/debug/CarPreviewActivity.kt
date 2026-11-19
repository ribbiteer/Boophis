// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.boophis.BoophisApp
import app.boophis.model.LayoutMode
import app.boophis.model.Route
import app.boophis.ui.BoophisNavigator
import app.boophis.ui.BoophisPalette
import app.boophis.ui.BoophisRoot

/**
 * Debug-only stand-in for the car screen when the DHU isn't available: renders the shared UI in
 * car mode at a car-like size in dp (default 1024×576 = 1280×720 @ 200 dpi, a typical 8" head unit).
 *
 * adb shell am start -n io.github.ribbiteer.boophis/app.boophis.debug.CarPreviewActivity --ei dpw 800 --ei dph 450
 *   [--ez night false]   day palette, as when the car reports day (default: follow the phone)
 */
class CarPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BoophisApp
        val dpw = intent.getIntExtra("dpw", 1024)
        val dph = intent.getIntExtra("dph", 576)
        if (intent.hasExtra("night")) BoophisPalette.setCarNight(intent.getBooleanExtra("night", true))
        setContent {
            val nav = remember { BoophisNavigator(Route.Home) }
            DisposableEffect(nav) {
                navigator = nav
                onDispose { if (navigator === nav) navigator = null }
            }
            Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
                BoxWithConstraints(Modifier.aspectRatio(dpw.toFloat() / dph)) {
                    val outer = LocalDensity.current
                    val pxWidth = with(outer) { maxWidth.toPx() }
                    CompositionLocalProvider(LocalDensity provides Density(pxWidth / dpw, outer.fontScale)) {
                        BoophisRoot(
                            deps = app.uiDeps,
                            mode = LayoutMode.Car,
                            navigator = nav,
                            videoSurface = { m -> app.VideoSurface(m) },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (intent.hasExtra("night")) BoophisPalette.setCarNight(null)
        super.onDestroy()
    }

    companion object {
        /** The open preview's navigator, so DEBUG_NAV / DEBUG_PLAY can drive it. */
        @Volatile var navigator: BoophisNavigator? = null
    }
}
