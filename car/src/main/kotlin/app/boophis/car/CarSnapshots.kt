// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.car

import android.view.View

/** Debug hook: the car Presentation's root view, if a surface is attached. */
object CarSnapshots {
    fun currentCarRoot(): View? = CarDisplayHost.active?.currentRootView
}
