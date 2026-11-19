// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.model

/**
 * Navigation destinations shared by the phone activity, the car presentation and ui/.
 * Kept here so app/ and car/ can deep-link (e.g. "open the movie with Resume focused")
 * without depending on ui internals.
 */
sealed interface Route {
    data object Home : Route

    data class Library(val kind: Kind) : Route {
        enum class Kind { MOVIES, SHOWS }
    }

    /** [focusResume] = the primary Play/Resume button gets initial focus/emphasis. */
    data class MovieDetail(val movieId: Long, val focusResume: Boolean = false) : Route

    /** [focusEpisodeId] selects that episode's season tab and scrolls it into view. */
    data class ShowDetail(
        val showId: Long,
        val focusEpisodeId: Long? = null,
        val focusResume: Boolean = false,
    ) : Route

    /** Full-screen player for whatever [PlayerController] is playing. */
    data object Player : Route

    data object About : Route

    /** Phone only: the folders the library reads, with add / remove. The car never routes here. */
    data object LibraryFolders : Route
}

/** Phone and car share the screens; only density/layout differ. */
enum class LayoutMode { Phone, Car }
