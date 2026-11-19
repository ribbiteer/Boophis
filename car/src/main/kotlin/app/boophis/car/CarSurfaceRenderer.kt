// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.car

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.ViewConfiguration
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/**
 * Car App Library entry point: receives the Android Auto map surface and hands it to
 * [CarDisplayHost]. Gestures arrive without positions for scrolls, so [CarInputBridge] synthesizes
 * touch streams. No mirroring, no projection.
 */
internal class CarSurfaceRenderer(
    carContext: CarContext,
    host: CarAppHost,
    private val env: CarEnv,
) : SurfaceCallback {

    val display = CarDisplayHost(carContext, host, env)

    private val input = CarInputBridge(
        dispatch = { ev -> display.injectTouch(ev) },
        anchor = ::scrollAnchor,
        touchSlopPx = { ViewConfiguration.get(carContext).scaledTouchSlop.toFloat() },
    )

    /** For the debug snapshot receiver. */
    val currentRootView get() = display.currentRootView

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface
        Log.i(TAG, "surfaceAvailable ${container.width}x${container.height}@${container.dpi} surface=${surface != null}")
        if (surface == null || container.width <= 0 || container.height <= 0) return
        input.reset()
        display.attach(surface, container.width, container.height, container.dpi)
        CarDisplayHost.active = display
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.i(TAG, "visibleArea=$visibleArea")
        display.setVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.i(TAG, "stableArea=$stableArea")
        display.setStableArea(stableArea)
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        Log.i(TAG, "surfaceDestroyed")
        input.reset()
        display.detach()
    }

    override fun onClick(x: Float, y: Float) {
        Log.i(TAG, "click $x,$y")
        input.click(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "scroll $distanceX,$distanceY")
        input.scroll(distanceX, distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        Log.i(TAG, "fling $velocityX,$velocityY")
        input.fling(velocityX, velocityY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Tap + scroll only (no multi-touch in Boophis's car UI).
    }

    fun release() {
        input.reset()
        display.release()
        if (CarDisplayHost.active === display) CarDisplayHost.active = null
    }

    private fun scrollAnchor(): PointF {
        val area = env.visibleArea
        return if (area != null && !area.isEmpty) {
            PointF(area.exactCenterX(), area.exactCenterY())
        } else {
            PointF(env.surfaceWidth / 2f, env.surfaceHeight / 2f)
        }
    }

    companion object {
        const val TAG = CarDisplayHost.TAG
    }
}
