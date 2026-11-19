// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.car

import android.content.Context
import android.hardware.display.DisplayManager

/**
 * Renderer knobs for the M1 fallbacks (TextureView, VirtualDisplay flags, input tuning).
 * Persisted in prefs; the debug-only receiver in app/ can change them over adb. Release builds
 * just use the defaults.
 */
object CarDebugConfig {
    private const val PREFS = "boophis_car_renderer"

    const val DEFAULT_VD_FLAGS =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

    @Volatile var useTextureView = false
    @Volatile var virtualDisplayFlags = DEFAULT_VD_FLAGS
    /** +1: host fling velocity is in finger direction; -1: content direction. */
    @Volatile var flingSign = 1f
    @Volatile var scrollIdleUpMs = 150L

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        useTextureView = p.getBoolean("texture", false)
        virtualDisplayFlags = p.getInt("vdflags", DEFAULT_VD_FLAGS)
        flingSign = p.getFloat("flingsign", 1f)
        scrollIdleUpMs = p.getLong("idleup", 150L)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("texture", useTextureView)
            .putInt("vdflags", virtualDisplayFlags)
            .putFloat("flingsign", flingSign)
            .putLong("idleup", scrollIdleUpMs)
            .apply()
    }

    override fun toString() =
        "texture=$useTextureView vdflags=$virtualDisplayFlags flingSign=$flingSign idleUp=$scrollIdleUpMs"
}
