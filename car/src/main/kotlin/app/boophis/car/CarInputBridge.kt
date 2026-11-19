// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
//
// Scroll synthesis approach (centre anchor, DOWN + MOVE by negated distance + idle UP) follows
// Fermata's MirrorServiceFS (GPL-3.0, Andrey Pavlenko, https://github.com/AndreyPavlenko/Fermata).
// Written independently for Boophis; see NOTICE.md.
package app.boophis.car

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns Car App Library surface gestures (tap / scroll / fling, no positions for scroll) into
 * single-pointer touch streams for the Presentation's view tree. Must be called on the main thread.
 */
internal class CarInputBridge(
    private val dispatch: (MotionEvent) -> Unit,
    /** Where scroll gestures start (centre of the visible area). */
    private val anchor: () -> PointF,
    private val touchSlopPx: () -> Float,
) {
    private val handler = Handler(Looper.getMainLooper())

    private var inGesture = false
    private var downTime = 0L
    private var lastEventTime = 0L
    private var x = 0f
    private var y = 0f
    private var travelled = 0f
    private var lastScrollAt = 0L

    private val idleUp = object : Runnable {
        override fun run() {
            if (!inGesture) return
            val idle = SystemClock.uptimeMillis() - lastScrollAt
            val wait = CarDebugConfig.scrollIdleUpMs - idle
            if (wait > 0) {
                handler.postDelayed(this, wait)
            } else {
                // A drag shorter than touch slop must not turn into a tap at the anchor.
                endGesture(if (travelled < touchSlopPx() * 2) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
            }
        }
    }

    fun click(cx: Float, cy: Float) {
        if (inGesture) endGesture(MotionEvent.ACTION_CANCEL)
        val t = nextTime()
        send(t, t, MotionEvent.ACTION_DOWN, cx, cy)
        handler.postDelayed({
            val up = max(SystemClock.uptimeMillis(), lastEventTime + 1)
            lastEventTime = up
            send(t, up, MotionEvent.ACTION_UP, cx, cy)
        }, TAP_UP_DELAY_MS)
    }

    fun scroll(distanceX: Float, distanceY: Float) {
        val now = nextTime()
        lastScrollAt = SystemClock.uptimeMillis()
        if (!inGesture) startGesture(now)
        // Host uses GestureDetector convention: distance = previous - current finger position.
        x -= distanceX
        y -= distanceY
        travelled += hypot(distanceX, distanceY)
        send(downTime, now, MotionEvent.ACTION_MOVE, x, y)
        handler.removeCallbacks(idleUp)
        handler.postDelayed(idleUp, CarDebugConfig.scrollIdleUpMs)
    }

    fun fling(velocityX: Float, velocityY: Float) {
        handler.removeCallbacks(idleUp)
        val vx = velocityX * CarDebugConfig.flingSign
        val vy = velocityY * CarDebugConfig.flingSign
        var t = nextTime()
        if (!inGesture) startGesture(t)
        // A short, velocity-consistent tail so the target's VelocityTracker sees the fling.
        repeat(FLING_STEPS) {
            t += FLING_STEP_MS
            x += vx * FLING_STEP_MS / 1000f
            y += vy * FLING_STEP_MS / 1000f
            travelled += hypot(vx, vy) * FLING_STEP_MS / 1000f
            send(downTime, t, MotionEvent.ACTION_MOVE, x, y)
        }
        lastEventTime = t
        endGesture(MotionEvent.ACTION_UP)
    }

    fun reset() {
        handler.removeCallbacksAndMessages(null)
        inGesture = false
    }

    private fun startGesture(t: Long) {
        val a = anchor()
        x = a.x
        y = a.y
        travelled = 0f
        downTime = t
        inGesture = true
        send(t, t, MotionEvent.ACTION_DOWN, x, y)
    }

    private fun endGesture(action: Int) {
        handler.removeCallbacks(idleUp)
        if (!inGesture) return
        inGesture = false
        // Stamp the UP right after the last MOVE (not "now", 150 ms later): Compose's VelocityTracker
        // treats a > 40 ms pause as "stopped", which would kill the fling of a fast swipe.
        val t = lastEventTime + 1
        lastEventTime = t
        send(downTime, t, action, x, y)
    }

    private fun nextTime(): Long {
        val t = max(SystemClock.uptimeMillis(), lastEventTime + 1)
        lastEventTime = t
        return t
    }

    private fun send(down: Long, time: Long, action: Int, px: Float, py: Float) {
        val ev = MotionEvent.obtain(down, time, action, px, py, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            dispatch(ev)
        } finally {
            ev.recycle()
        }
    }

    companion object {
        private const val TAP_UP_DELAY_MS = 40L
        private const val FLING_STEPS = 6
        private const val FLING_STEP_MS = 8L

        @Suppress("unused")
        private fun Float.nearZero() = abs(this) < 0.01f
    }
}
