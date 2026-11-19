// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NfoParserTest {
    private fun fixture(rel: String) = NfoParser.parseFile(Fixtures.file(rel))

    @Test fun movieNfo() {
        val n = fixture("Movies/Paper Harbor (2021)/movie.nfo")!!
        assertEquals("movie", n.root)
        assertEquals("Paper Harbor", n.title)
        assertEquals(2021, n.year)
        assertEquals(7.4f, n.rating!!, 0.001f)
        assertEquals("PG-13", n.mpaa)
        assertEquals(listOf("Drama", "Mystery"), n.genres)
        assertEquals(104, n.runtimeMinutes)
        assertEquals(listOf("eng"), n.audioLanguages)
        assertEquals(listOf("eng"), n.subtitleLanguages)
    }

    @Test fun bomAndTrailingUrl() {
        val n = fixture("Movies/The Quiet Engine (2023)/The Quiet Engine (2023).nfo")!!
        assertEquals("The Quiet Engine", n.title)
        assertEquals(8.1f, n.rating!!, 0.001f)
        assertEquals(listOf("Sci-Fi"), n.genres)
    }

    @Test fun malformedXmlStillYieldsFields() {
        val n = fixture("Movies/Broken Lantern (2015)/movie.nfo")!!
        assertEquals("Broken Lantern & Other Stories", n.title)
        assertEquals(2015, n.year)
        assertEquals("Unclosed tags & a bare ampersand.", n.plot)
        assertNotNull(n.genres.firstOrNull())
    }

    @Test fun tvshowNfo() {
        val n = fixture("Shows/Saltmarsh Station/tvshow.nfo")!!
        assertEquals("tvshow", n.root)
        assertEquals("Saltmarsh Station", n.title)
        assertEquals(2019, n.year)
        assertEquals(8.3f, n.rating!!, 0.001f)
        assertEquals("TV-14", n.mpaa)
    }

    @Test fun episodeNfo() {
        val n = fixture("Shows/Saltmarsh Station/Season 01/Saltmarsh Station S01E01 - Arrival.nfo")!!
        assertEquals("Arrival", n.title)
        assertEquals(1, n.season)
        assertEquals(1, n.episode)
        assertEquals("2019-03-02", n.aired)
        assertEquals(2019, n.year)
        assertEquals(47, n.runtimeMinutes)
    }

    @Test fun ratingVariants() {
        assertEquals(6.5f, NfoParser.parse("<movie><rating>6,5</rating></movie>")!!.rating!!, 0.001f)
        val multi = "<movie><ratings><rating name=\"a\"><value>1.0</value></rating>" +
            "<rating name=\"b\" default=\"true\"><value>9.0</value></rating></ratings></movie>"
        assertEquals(9.0f, NfoParser.parse(multi)!!.rating!!, 0.001f)
        val noDefault = "<movie><ratings><rating name=\"a\"><value>3.5</value></rating></ratings></movie>"
        assertEquals(3.5f, NfoParser.parse(noDefault)!!.rating!!, 0.001f)
        assertNull(NfoParser.parse("<movie><title>x</title></movie>")!!.rating)
    }

    @Test fun fallbackFieldsAndGarbage() {
        val n = NfoParser.parse("<movie><title><![CDATA[A & B]]></title><premiered>2010-05-06</premiered><outline>short</outline>")!!
        assertEquals("A & B", n.title)
        assertEquals(2010, n.year)
        assertEquals("short", n.plot)
        assertNull(NfoParser.parse("no xml here at all"))
        assertNull(NfoParser.parse(""))
    }
}
