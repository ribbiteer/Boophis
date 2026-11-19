// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkResolverTest {
    private fun dir(vararg names: String) = DirListing(names.map { File("/d/$it") })
    private fun name(path: String?) = path?.let { File(it).name }
    private val bases = listOf("Movie (2020)")

    @Test fun moviePosterVariants() {
        for (n in listOf("poster.jpg", "folder.png", "cover.webp", "Movie (2020)-poster.jpeg", "movie.jpg", "POSTER.JPG")) {
            assertEquals(n, name(ArtworkResolver.moviePoster(dir(n), bases)))
        }
        assertNull(ArtworkResolver.moviePoster(dir("random.jpg", "poster.gif"), bases))
    }

    @Test fun moviePosterPriority() {
        assertEquals("poster.jpg", name(ArtworkResolver.moviePoster(dir("movie.jpg", "folder.jpg", "poster.jpg"), bases)))
        assertEquals("folder.jpg", name(ArtworkResolver.moviePoster(dir("movie.jpg", "Movie (2020)-poster.jpg", "folder.jpg"), bases)))
        assertEquals("poster.jpg", name(ArtworkResolver.moviePoster(dir("poster.png", "poster.jpg"), bases)))
    }

    @Test fun nonGenericOnlyBasenameVariants() {
        assertNull(ArtworkResolver.moviePoster(dir("poster.jpg"), bases, generic = false))
        val d = dir("poster.jpg", "Movie (2020)-poster.jpg")
        assertEquals("Movie (2020)-poster.jpg", name(ArtworkResolver.moviePoster(d, bases, false)))
    }

    @Test fun movieBackdropVariants() {
        for (n in listOf("fanart.jpg", "backdrop.png", "background.jpg", "Movie (2020)-fanart.jpg", "Movie (2020)-backdrop.jpg", "art.jpg")) {
            assertEquals(n, name(ArtworkResolver.movieBackdrop(dir(n), bases)))
        }
        assertEquals("fanart.jpg", name(ArtworkResolver.movieBackdrop(dir("art.jpg", "backdrop.jpg", "fanart.jpg"), bases)))
    }

    @Test fun showArt() {
        assertEquals("folder.jpg", name(ArtworkResolver.showPoster(dir("folder.jpg"))))
        assertEquals("backdrop.jpg", name(ArtworkResolver.showBackdrop(dir("backdrop.jpg", "art.png"))))
    }

    @Test fun seasonPoster() {
        val root = dir("season01-poster.jpg", "poster.jpg")
        assertEquals("season01-poster.jpg", name(ArtworkResolver.seasonPoster(root, null, 1)))
        assertEquals("season02-poster.png", name(ArtworkResolver.seasonPoster(root, dir("season02-poster.png", "poster.jpg"), 2)))
        assertEquals("poster.jpg", name(ArtworkResolver.seasonPoster(dir("poster.jpg"), dir("poster.jpg"), 3)))
        assertEquals("season-specials-poster.jpg", name(ArtworkResolver.seasonPoster(dir("season-specials-poster.jpg"), null, 0)))
        assertNull(ArtworkResolver.seasonPoster(dir("season01-poster.jpg"), null, 2))
        assertEquals("season3-poster.jpg", name(ArtworkResolver.seasonPoster(dir("season3-poster.jpg"), null, 3)))
    }

    @Test fun episodeThumb() {
        assertEquals("S01E01-thumb.jpg", name(ArtworkResolver.episodeThumb(dir("S01E01.jpg", "S01E01-thumb.jpg"), "S01E01")))
        assertEquals("S01E01.png", name(ArtworkResolver.episodeThumb(dir("S01E01.png"), "S01E01")))
        assertNull(ArtworkResolver.episodeThumb(dir("S01E02.jpg"), "S01E01"))
    }

    @Test fun fixtureTreeSidecars() {
        val l = DirListing.of(Fixtures.file("Movies/Paper Harbor (2021)"))
        assertEquals("poster.jpg", name(ArtworkResolver.moviePoster(l, listOf("Paper Harbor (2021)"))))
        assertEquals("fanart.jpg", name(ArtworkResolver.movieBackdrop(l, listOf("Paper Harbor (2021)"))))
    }
}
