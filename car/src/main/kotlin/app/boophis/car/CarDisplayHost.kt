// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.car

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.ViewModelStore

/**
 * Renders Boophis's car UI onto any car surface: surface → private VirtualDisplay → Presentation
 * → ComposeView. Fed by the legacy projection CarActivity (car-projection/); the dormant Car App
 * Library route in car/ uses it too. No mirroring, no projection of the phone screen.
 */
class CarDisplayHost(
    context: Context,
    private val host: CarAppHost,
    val env: CarEnv,
) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var savedState: Bundle? = null
    private val viewModelStore = ViewModelStore()

    val isAttached: Boolean get() = presentation != null

    /** For the debug snapshot receiver. */
    val currentRootView get() = presentation?.rootView

    /** (Re)attaches to [surface]. Tears down any previous display first; UI state carries over. */
    fun attach(surface: Surface, width: Int, height: Int, dpi: Int) {
        Log.i(TAG, "attach ${width}x$height@$dpi cfg=$CarDebugConfig")
        detach()
        CarDebugConfig.load(appContext)
        env.surfaceWidth = width
        env.surfaceHeight = height
        env.surfaceDpi = dpi
        env.surfaceGeneration++

        // Fallback chain for VirtualDisplay flags: configured → OWN_CONTENT_ONLY → none.
        val flagChain = linkedSetOf(CarDebugConfig.virtualDisplayFlags, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, 0)
        for (flags in flagChain) {
            val vd = try {
                displayManager.createVirtualDisplay("Boophis-Car", width, height, dpi, surface, flags)
            } catch (t: Throwable) {
                Log.w(TAG, "createVirtualDisplay(flags=$flags) failed", t)
                null
            } ?: continue
            val p = CarPresentation(appContext, vd.display, host, env, savedState, viewModelStore)
            try {
                p.show()
            } catch (e: WindowManager.InvalidDisplayException) {
                Log.w(TAG, "Presentation.show failed on flags=$flags", e)
                vd.release()
                continue
            }
            virtualDisplay = vd
            presentation = p
            Log.i(TAG, "presentation shown on display ${vd.display.displayId} flags=$flags")
            return
        }
        Log.e(TAG, "No VirtualDisplay configuration worked")
    }

    /** Tears down the Presentation + VirtualDisplay, keeping saved UI state for the next attach. */
    fun detach() {
        presentation?.let {
            savedState = it.saveState()
            it.destroy()
        }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    /** Ends the whole car session: also drops retained ViewModels. */
    fun release() {
        detach()
        savedState = null
        viewModelStore.clear()
    }

    fun setVisibleArea(area: Rect?) {
        env.visibleArea = area?.let(::Rect)
    }

    fun setStableArea(area: Rect?) {
        env.stableArea = area?.let(::Rect)
    }

    /** Touch in surface pixels (== Presentation window pixels). */
    fun injectTouch(ev: MotionEvent): Boolean = presentation?.injectTouch(ev) ?: false

    companion object {
        const val TAG = "BoophisCar"

        /** The host currently drawing on a car (debug snapshots). */
        @Volatile
        var active: CarDisplayHost? = null
    }
}
