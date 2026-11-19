// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.boophis.model.Route

class BoophisNavigator(initial: Route = Route.Home) {
    val backStack: SnapshotStateList<Route> = mutableStateListOf(initial)

    val current: Route get() = backStack.last()

    fun open(route: Route) {
        if (route is Route.Player && current is Route.Player) return
        backStack.add(route)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun home() {
        backStack.clear()
        backStack.add(Route.Home)
    }

    fun replaceAll(vararg routes: Route) {
        if (routes.isEmpty()) return
        backStack.clear()
        backStack.addAll(routes)
    }
}
