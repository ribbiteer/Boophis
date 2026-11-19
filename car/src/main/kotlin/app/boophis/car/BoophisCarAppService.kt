// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.car

import android.content.Intent
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Navigation-category Car App: the only category allowed to draw custom UI (onto the map surface).
 * Boophis does no navigation; it uses the surface for its own video UI.
 */
class BoophisCarAppService : CarAppService() {
    // Sideloaded personal app: accept any host (Android Auto, DHU). Logged in DECISIONS.md.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = BoophisCarSession()
}

internal class BoophisCarSession : Session() {
    private val env = CarEnv()

    override fun onCreateScreen(intent: Intent): Screen {
        val host = CarAppHost.from(carContext)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                host.onCarSessionEnded()
            }
        })
        host.onCarSessionStarted(env)
        host.onCarNightMode(carContext.isDarkMode)
        return BoophisCarScreen(carContext, host, env)
    }

    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        CarAppHost.from(carContext).onCarNightMode(carContext.isDarkMode)
    }
}

internal class BoophisCarScreen(
    carContext: CarContext,
    private val host: CarAppHost,
    private val env: CarEnv,
) : Screen(carContext) {

    private val renderer = CarSurfaceRenderer(carContext, host, env)
    private var playing = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(CarSurfaceRenderer.TAG, "screen destroyed; releasing renderer")

                renderer.release()
            }
        })
        lifecycleScope.launch {
            host.isPlaying.collect {
                playing = it
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val home = Action.Builder()
            .setIcon(icon(R.drawable.ic_car_home))
            .setOnClickListener { env.requestHome() }
            .build()
        val playPause = Action.Builder()
            .setIcon(icon(if (playing) R.drawable.ic_car_pause else R.drawable.ic_car_play))
            .setOnClickListener { host.togglePlayPause() }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(home).addAction(playPause).build())
            // PAN in the map action strip makes the host deliver onScroll/onFling to the surface.
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}
