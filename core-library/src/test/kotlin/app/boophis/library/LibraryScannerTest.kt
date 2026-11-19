// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import app.boophis.model.TrackType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerTest {
    private val roots = RootDetector.detect(Fixtures.volume)
    private val lib by lazy { LibraryScanner().scan(roots) }
    private fun name(p: String?) = p?.let { File(it).name }
    private fun movie(title: String) = lib.movies.first { it.title == title }

    @Test fun detectsOnlyLibraryRoots() {
        assertEquals(listOf("Movies", "Shows"), roots.map { it.dir.name })
        assertEquals(listOf(LibraryRoot.Kind.MOVIES, LibraryRoot.Kind.SHOWS), roots.map { it.kind })
        assertEquals(LibraryRoot.Kind.SHOWS, RootDetector.classify("tv shows"))
        assertEquals(LibraryRoot.Kind.SHOWS, RootDetector.classify("TV"))
        assertEquals(LibraryRoot.Kind.MOVIES, RootDetector.classify("MOVIES"))
        assertNull(RootDetector.classify("DCIM"))
        assertTrue(lib.movies.none { "DCIM" in it.file.path || "Download" in it.file.path })
    }

    @Test fun movieSet() {
        assertEquals(
            listOf("Broken Lantern & Other Stories", "Midnight Orchard", "Paper Harbor", "Split Tide", "The Quiet Engine"),
            lib.movies.map { it.title }.sorted(),
        )
    }

    @Test fun skipsExtrasSamplesTrailersAndExtraParts() {
        val names = lib.movies.map { File(it.file.path).name }
        assertFalse(names.any { it.contains("trailer", true) || it.startsWith("sample") || it.startsWith("Making") })
        assertEquals(listOf("Split Tide (2019)-cd1.mkv"), names.filter { it.startsWith("Split Tide") })
        assertEquals(2019, movie("Split Tide").year)
    }

    @Test fun nfoDrivenMovie() {
        val m = movie("Paper Harbor")
        assertEquals(2021, m.year)
        assertEquals(7.4f, m.rating!!, 0.001f)
        assertEquals("PG-13", m.contentRating)
        assertEquals(104 * 60_000L, m.runtimeMs)
        assertEquals("poster.jpg", name(m.poster))
        assertEquals("fanart.jpg", name(m.backdrop))
        val subs = m.file.subtitles
        assertEquals(3, subs.size)
        assertTrue(subs.all { it.type == TrackType.SUBTITLE && it.externalPath != null })
        assertEquals("en", subs.first { it.isForced }.language)
        assertEquals("fr", subs.first { it.isHearingImpaired }.language)
        assertTrue(subs.any { it.language == "en" && !it.isForced })
    }

    @Test fun looseReleaseNamedMovie() {
        val m = movie("Midnight Orchard")
        assertEquals(2018, m.year)
        assertEquals("Midnight Orchard", m.sortTitle)
        assertEquals("Midnight.Orchard.2018.1080p.BluRay.x264-GRP-poster.jpg", name(m.poster))
        assertNull(m.backdrop)
    }

    @Test fun malformedAndTrailingUrlNfosStillIndexed() {
        assertEquals("Quiet Engine", movie("The Quiet Engine").sortTitle)
        assertEquals("The Quiet Engine (2023)-poster.jpg", name(movie("The Quiet Engine").poster))
        assertEquals("backdrop.png", name(movie("The Quiet Engine").backdrop))
        assertEquals(2015, movie("Broken Lantern & Other Stories").year)
    }

    @Test fun showStructure() {
        assertEquals(1, lib.shows.size)
        val s = lib.shows.single()
        assertEquals("Saltmarsh Station", s.title)
        assertEquals(2019, s.year)
        assertEquals("poster.jpg", name(s.poster))
        assertEquals("fanart.jpg", name(s.backdrop))
        assertEquals(listOf(0, 1, 2), s.seasons.map { it.number })
        assertEquals(listOf(1, 3, 3), s.seasons.map { it.episodes.size })
        assertEquals("season-specials-poster.jpg", name(s.seasons[0].poster))
        assertEquals("season01-poster.jpg", name(s.seasons[1].poster))
        assertEquals("poster.jpg", name(s.seasons[2].poster))
    }

    @Test fun episodes() {
        val s = lib.shows.single()
        val e1 = s.seasons[1].episodes[0]
        assertEquals("Arrival", e1.title)
        assertEquals("2019-03-02", e1.aired)
        assertEquals(47 * 60_000L, e1.runtimeMs)
        assertEquals("Saltmarsh Station S01E01 - Arrival-thumb.jpg", name(e1.thumb))
        val e2 = s.seasons[1].episodes[1]
        assertEquals("Low Tide", e2.title)
        assertEquals("Saltmarsh Station S01E02 - Low Tide.jpg", name(e2.thumb))
        assertEquals("The Signal", s.seasons[1].episodes[2].title)
        val s2 = s.seasons[2].episodes
        assertEquals(listOf(1, 2, 3), s2.map { it.number })
        assertEquals("Return", s2[0].title)
        assertEquals("Episode 2", s2[1].title)
        assertEquals("Episode 3", s2[2].title)
        assertEquals("Behind the Tides", s.seasons[0].episodes.single().title)
    }

    @Test fun fileCount() {
        assertEquals(5 + 7, lib.fileCount)
    }
}
