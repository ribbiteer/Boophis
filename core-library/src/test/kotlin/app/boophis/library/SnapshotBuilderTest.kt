// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import app.boophis.library.db.EpisodeEntity
import app.boophis.library.db.MediaFileEntity
import app.boophis.library.db.MovieEntity
import app.boophis.library.db.SeasonEntity
import app.boophis.library.db.ShowEntity
import app.boophis.library.db.WatchStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotBuilderTest {
    private val min = 60_000L

    private fun movie(id: Long, title: String, addedAt: Long) = MovieEntity(
        id, "/m/$id.mkv", title, Naming.sortTitle(title), null, null, null, null, "", 0, null, null, addedAt,
    )

    private fun ep(id: Long, season: Int, n: Int) =
        EpisodeEntity(id, 1, "/s/$id.mkv", season, n, null, "E$n", null, null, 0, null, 1)

    private val show = ShowEntity(1, "/s", "The Show", "Show", null, null, null, null, "", null, null)

    private fun file(path: String) = MediaFileEntity(path, 1, 1, durationMs = 100 * min, probed = true)

    @Test fun homeFeedRules() {
        val movies = listOf(movie(1, "Zeta", 10), movie(2, "The Alpha", 30), movie(3, "Beta", 20))
        val eps = listOf(ep(10, 1, 1), ep(11, 1, 2), ep(12, 1, 3))
        val files = (movies.map { it.filePath } + eps.map { it.filePath }).map(::file)
        val watch = listOf(
            WatchStateEntity("/m/1.mkv", 50 * min, 100 * min, false, 500), // in progress
            WatchStateEntity("/m/3.mkv", 1 * min, 100 * min, false, 900), // under 2 min: not in progress
            WatchStateEntity("/s/10.mkv", 0, 100 * min, true, 700), // watched ep 1
        )
        val snap = SnapshotBuilder.build(movies, listOf(show), listOf(SeasonEntity(1, 1, 1, null)), eps, files, watch)
        val h = snap.home
        assertEquals(listOf("Zeta"), h.continueWatching.map { it.title })
        assertEquals(listOf(11L), h.nextUp.map { it.id })
        assertEquals(listOf("The Alpha", "Beta", "Zeta"), snap.movies.map { it.title })
        assertEquals("The Alpha", h.recentlyAdded.first().title)
        assertTrue(!h.isLibraryEmpty)
        assertEquals(11L, snap.showDetails.getValue(1).nextUp!!.id)
        assertEquals(2, snap.showDetails.getValue(1).show.unwatchedCount)
        assertEquals(100 * min, snap.moviesById.getValue(1).runtimeMs)
    }

    @Test fun emptyLibraryAndArtFallbacks() {
        val empty = SnapshotBuilder.build(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(empty.home.isLibraryEmpty)
        val files = listOf(MediaFileEntity("/s/10.mkv", 1, 1, frameArt = "/art/f.jpg", probed = true))
        val snap = SnapshotBuilder.build(emptyList(), listOf(show), emptyList(), listOf(ep(10, 1, 1)), files, emptyList())
        val e = snap.episodesById.getValue(10)
        assertEquals("/art/f.jpg", e.artwork.thumb)
        assertEquals("/art/f.jpg", e.artwork.poster)
        assertNull(snap.showDetails.getValue(1).show.artwork.thumb)
    }
}
