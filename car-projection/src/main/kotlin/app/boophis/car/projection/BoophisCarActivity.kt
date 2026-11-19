// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
//
// Legacy Android Auto projection entry point, modelled on Fermata's MirrorService/MirrorActivity
// (GPL-3.0, Andrey Pavlenko, https://github.com/AndreyPavlenko/Fermata) — but Boophis renders its
// own UI on a private VirtualDisplay instead of mirroring the phone. See NOTICE.md.
package app.boophis.car.projection

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import app.boophis.car.CarAppHost
import app.boophis.car.CarDisplayHost
import app.boophis.car.CarEnv
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/** Registered with CATEGORY_PROJECTION; Android Auto lists it in the car launcher. */
class BoophisProjectionService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = BoophisCarActivity::class.java
}

/**
 * Full-screen car activity: a SurfaceView whose surface backs Boophis's VirtualDisplay. Touches on
 * the car screen arrive here as real MotionEvents (with positions) and are forwarded 1:1.
 */
class BoophisCarActivity : CarActivity(), SurfaceHolder.Callback {
    private val env = CarEnv()
    private lateinit var host: CarAppHost
    private lateinit var carDisplay: CarDisplayHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            carUiController.statusBarController.hideAppHeader()
        } catch (t: Throwable) {
            Log.w(TAG, "hideAppHeader failed", t)
        }
        host = CarAppHost.from(this)
        carDisplay = CarDisplayHost(this, host, env)
        host.onCarSessionStarted(env)
        reportNight(resources.configuration)

        val surfaceView = SurfaceView(this).apply { holder.addCallback(this@BoophisCarActivity) }
        val root = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                carDisplay.injectTouch(ev)
                return true
            }
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        Log.i(TAG, "projection activity created")
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        carDisplay.attach(holder.surface, width, height, resources.displayMetrics.densityDpi)
        CarDisplayHost.active = carDisplay
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        carDisplay.detach()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        reportNight(newConfig)
    }

    /** Android Auto passes the car's day/night state as the night-mode bit of the configuration. */
    private fun reportNight(c: android.content.res.Configuration) {
        val night = (c.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        Log.i(TAG, "car night mode: $night")
        host.onCarNightMode(night)
    }

    override fun onBackPressed() {
        if (!host.onCarBack(env)) super.onBackPressed()
    }

    override fun onDestroy() {
        carDisplay.release()
        if (CarDisplayHost.active === carDisplay) CarDisplayHost.active = null
        host.onCarSessionEnded()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BoophisCar"
    }
}
