// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.model.rules

import app.boophis.model.Episode

/**
 * Jellyfin-like Next Up. Specials (season 0) are never picked as next-up.
 */
object NextUp {
    private val order = compareBy<Episode>({ it.seasonNumber }, { it.episodeNumber }, { it.id })

    /**
     * The episode the show's primary button should play:
     * 1. the most recently played episode if it is still resumable;
     * 2. else the first unwatched episode after the furthest-watched one (in airing order);
     * 3. else, if nothing is watched, the first episode;
     * 4. null when everything after the furthest-watched episode is watched.
     */
    fun forShow(episodes: List<Episode>): Episode? {
        val regular = episodes.filter { it.seasonNumber > 0 }.sortedWith(order)
        if (regular.isEmpty()) return null

        val lastPlayed = regular.filter { it.watch.lastPlayedAt != null }.maxByOrNull { it.watch.lastPlayedAt!! }
        if (lastPlayed != null && ResumePolicy.hasResumePoint(lastPlayed.watch)) return lastPlayed

        val furthestWatched = regular.indexOfLast { it.watch.watched }
        if (furthestWatched < 0) return regular.firstOrNull { !it.watch.watched }
        return regular.drop(furthestWatched + 1).firstOrNull { !it.watch.watched }
    }

    /**
     * Next Up for the Home row: only shows the user has started (at least one episode watched),
     * and only when the next-up episode is not already in Continue Watching (i.e. not resumable).
     */
    fun forHomeRow(episodes: List<Episode>): Episode? {
        if (episodes.none { it.seasonNumber > 0 && it.watch.watched }) return null
        val next = forShow(episodes) ?: return null
        return if (ResumePolicy.hasResumePoint(next.watch)) null else next
    }

    /** The episode right after [current] in airing order (Specials skipped), for auto-play. */
    fun after(current: Episode, episodes: List<Episode>): Episode? {
        val regular = episodes.filter { it.seasonNumber > 0 }.sortedWith(order)
        val idx = regular.indexOfFirst { it.id == current.id }
        if (idx < 0) return regular.firstOrNull { order.compare(it, current) > 0 }
        return regular.getOrNull(idx + 1)
    }
}
