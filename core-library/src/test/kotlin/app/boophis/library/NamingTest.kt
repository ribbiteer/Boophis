// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {
    private fun t(s: String) = Naming.parseTitle(s)

    @Test fun parenYear() = assertEquals(Naming.ParsedTitle("Paper Harbor", 2021), t("Paper Harbor (2021)"))

    @Test fun bracketYear() = assertEquals(Naming.ParsedTitle("Paper Harbor", 2021), t("Paper Harbor [2021] [1080p]"))

    @Test fun dottedWithJunk() =
        assertEquals(Naming.ParsedTitle("Midnight Orchard", 2018), t("Midnight.Orchard.2018.1080p.BluRay.x264-GRP"))

    @Test fun yearThatIsAlsoTitle() = assertEquals(Naming.ParsedTitle("2012", 2009), t("2012.2009.720p.HDTV"))

    @Test fun titleWithNumberBeforeYear() =
        assertEquals(Naming.ParsedTitle("Blade Runner 2049", 2017), t("Blade.Runner.2049.2017.2160p.UHD.HDR"))

    @Test fun noYearJunkStripped() = assertEquals(Naming.ParsedTitle("Quiet Engine", null), t("Quiet_Engine_720p_x265-GRP"))

    @Test fun keepsHyphenAndDotsWithSpaces() {
        assertEquals("Spider-Man", t("Spider-Man (2002)").title)
        assertEquals("Mr. Robot", t("Mr. Robot (2015)").title)
    }

    @Test fun sortTitle() {
        assertEquals("Quiet Engine", Naming.sortTitle("The Quiet Engine"))
        assertEquals("Hidden Figure", Naming.sortTitle("A Hidden Figure"))
        assertEquals("Apple Tree", Naming.sortTitle("An Apple Tree"))
        assertEquals("Theory of Things", Naming.sortTitle("Theory of Things"))
        assertEquals("The", Naming.sortTitle("The"))
    }

    private fun e(s: String, folder: Int? = null) = Naming.parseEpisode(s, folder)
    private fun code(s: String) = e(s)!!.let { Triple(it.season, it.episode, it.episodeEnd) }

    @Test fun episodeVariants() {
        assertEquals(Triple(1, 1, null), code("Show S01E01 - Pilot"))
        assertEquals(Triple(1, 1, null), code("show s1e1"))
        assertEquals(Triple(1, 1, 2), code("Show S01E01-E02"))
        assertEquals(Triple(1, 1, 2), code("Show S01E01E02"))
        assertEquals(Triple(2, 3, null), code("saltmarsh station 2x03"))
    }

    @Test fun episodeTitles() {
        assertEquals("Pilot", e("Show S01E01 - Pilot")!!.title)
        assertEquals("Low Tide", e("Show.S01E02.Low.Tide.1080p.WEB-DL")!!.title)
        assertNull(e("Saltmarsh.Station.S02E02.720p.WEB-DL.x264-GRP")!!.title)
    }

    @Test fun resolutionIsNotAnEpisodeEnd() {
        assertNull(e("Show S01E05-1080p")!!.episodeEnd)
        assertNull(e("Movie 1920x1080"))
    }

    @Test fun seasonFromFolderOrBareEpisode() {
        assertEquals(3, e("E07 - Foo", 3)!!.season)
        assertEquals(2, e("04 - Foo", 2)!!.season)
        assertEquals(4, e("04 - Foo", 2)!!.episode)
        assertNull(e("Making Of", 0))
    }

    @Test fun seasonFolders() {
        assertEquals(1, Naming.seasonFromFolder("Season 01"))
        assertEquals(12, Naming.seasonFromFolder("season 12"))
        assertEquals(3, Naming.seasonFromFolder("S03"))
        assertEquals(0, Naming.seasonFromFolder("Specials"))
        assertNull(Naming.seasonFromFolder("Extras"))
    }

    @Test fun skipAndParts() {
        assertTrue(Naming.isSkippableVideo("sample"))
        assertTrue(Naming.isSkippableVideo("Movie (2020)-trailer"))
        assertFalse(Naming.isSkippableVideo("Trailer Park Boys"))
        assertEquals("Movie (2020)" to 2, Naming.partOf("Movie (2020)-cd2"))
        assertEquals("Movie" to 1, Naming.partOf("Movie part1"))
        assertNull(Naming.partOf("Movie (2020)"))
        assertTrue(Naming.isExtrasDir("Behind The Scenes"))
        assertTrue(Naming.isVideo("a.M2TS"))
        assertFalse(Naming.isVideo("a.srt"))
    }
}
