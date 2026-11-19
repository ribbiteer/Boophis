// SPDX-License-Identifier: GPL-3.0-or-later
package app.boophis.model.rules

import app.boophis.model.Episode
import app.boophis.model.MediaFile
import app.boophis.model.TrackInfo
import app.boophis.model.TrackType
import app.boophis.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private val hour = 3_600_000L

    @Test fun noResumeUnderTwoMinutes() {
        assertNull(ResumePolicy.resumePosition(119_000, hour))
        assertNull(ResumePolicy.resumePosition(120_000, hour))
        assertEquals(120_001L, ResumePolicy.resumePosition(120_001, hour))
    }

    @Test fun noResumeAtOrPast92Percent() {
        assertEquals((0.91 * hour).toLong(), ResumePolicy.resumePosition((0.91 * hour).toLong(), hour))
        assertNull(ResumePolicy.resumePosition((0.92 * hour).toLong(), hour))
    }

    @Test fun watchedAt92Percent() {
        assertFalse(ResumePolicy.isWatched((0.919 * hour).toLong(), hour))
        assertTrue(ResumePolicy.isWatched((0.92 * hour).toLong(), hour))
        assertFalse(ResumePolicy.isWatched(10, 0))
    }

    @Test fun afterProgressMarksWatchedAndClears() {
        val s = ResumePolicy.afterProgress(WatchState(), (0.95 * hour).toLong(), hour, now = 5)
        assertTrue(s.watched)
        assertEquals(0L, s.positionMs)
        assertEquals(5L, s.lastPlayedAt)
    }

    @Test fun afterProgressKeepsPosition() {
        val s = ResumePolicy.afterProgress(WatchState(), 600_000, hour, now = 7)
        assertFalse(s.watched)
        assertEquals(600_000L, s.positionMs)
        assertTrue(ResumePolicy.isInProgress(s))
    }
}

class NextUpTest {
    private fun ep(id: Long, s: Int, e: Int, watched: Boolean = false, pos: Long = 0, played: Long? = null) = Episode(
        id = id, showId = 1, showTitle = "Show", seasonNumber = s, episodeNumber = e, title = "E$id",
        file = MediaFile("/x/$id.mkv", 1, 1), watch = WatchState(pos, 3_600_000, watched, played),
    )

    @Test fun firstEpisodeWhenNothingWatched() {
        val eps = listOf(ep(2, 1, 2), ep(1, 1, 1), ep(3, 2, 1))
        assertEquals(1L, NextUp.forShow(eps)?.id)
        assertNull(NextUp.forHomeRow(eps))
    }

    @Test fun nextAfterFurthestWatched() {
        val eps = listOf(ep(1, 1, 1, watched = true, played = 1), ep(2, 1, 2, watched = true, played = 2), ep(3, 1, 3), ep(4, 2, 1))
        assertEquals(3L, NextUp.forShow(eps)?.id)
        assertEquals(3L, NextUp.forHomeRow(eps)?.id)
    }

    @Test fun crossesSeasonBoundary() {
        val eps = listOf(ep(1, 1, 1, watched = true), ep(2, 1, 2, watched = true), ep(3, 2, 1))
        assertEquals(3L, NextUp.forShow(eps)?.id)
    }

    @Test fun skipsWatchedGapAfterFurthest() {
        // Watched E1 and E3 (skipped E2): next-up is E4, Jellyfin-style.
        val eps = listOf(ep(1, 1, 1, watched = true), ep(2, 1, 2), ep(3, 1, 3, watched = true), ep(4, 1, 4))
        assertEquals(4L, NextUp.forShow(eps)?.id)
    }

    @Test fun resumableLastPlayedWins() {
        val eps = listOf(ep(1, 1, 1, watched = true, played = 1), ep(2, 1, 2, pos = 600_000, played = 9), ep(3, 1, 3))
        assertEquals(2L, NextUp.forShow(eps)?.id)
        // Already in Continue Watching → not on the Next Up home row.
        assertNull(NextUp.forHomeRow(eps))
    }

    @Test fun allWatchedIsNull() {
        val eps = listOf(ep(1, 1, 1, watched = true), ep(2, 1, 2, watched = true))
        assertNull(NextUp.forShow(eps))
    }

    @Test fun specialsIgnored() {
        val eps = listOf(ep(9, 0, 1), ep(1, 1, 1, watched = true), ep(2, 1, 2))
        assertEquals(2L, NextUp.forShow(eps)?.id)
        assertEquals(2L, NextUp.after(eps[1], eps)?.id)
        assertNull(NextUp.after(eps[2], eps))
    }
}

class TrackSelectionTest {
    private fun a(lang: String?, def: Boolean = false) = TrackInfo(TrackType.AUDIO, lang, isDefault = def)
    private fun s(lang: String?, forced: Boolean = false, def: Boolean = false, sdh: Boolean = false) =
        TrackInfo(TrackType.SUBTITLE, lang, isForced = forced, isDefault = def, isHearingImpaired = sdh)

    @Test fun prefersEnglishAudio() {
        assertEquals(1, TrackSelection.chooseAudio(listOf(a("jpn", def = true), a("eng"))))
        assertEquals(2, TrackSelection.chooseAudio(listOf(a("ger"), a("en-US"), a("English", def = true))))
    }

    @Test fun fallsBackToDefaultThenFirst() {
        assertEquals(1, TrackSelection.chooseAudio(listOf(a("ger"), a("fra", def = true))))
        assertEquals(0, TrackSelection.chooseAudio(listOf(a("ger"), a("fra"))))
        assertNull(TrackSelection.chooseAudio(emptyList()))
    }

    @Test fun subsOffForEnglishAudio() {
        val c = TrackSelection.choose(listOf(a("eng")), listOf(s("eng"), s("spa")))
        assertNull(c.subtitleIndex)
    }

    @Test fun forcedSubsOn() {
        val c = TrackSelection.choose(listOf(a("eng")), listOf(s("eng"), s("eng", forced = true)))
        assertEquals(1, c.subtitleIndex)
    }

    @Test fun englishSubsForForeignAudio() {
        val c = TrackSelection.choose(listOf(a("jpn")), listOf(s("eng", sdh = true), s("spa"), s("eng")))
        assertEquals(2, c.subtitleIndex)
    }

    @Test fun unknownAudioLanguageNoSubs() {
        val c = TrackSelection.choose(listOf(a(null)), listOf(s("eng")))
        assertNull(c.subtitleIndex)
    }

    @Test fun normalize() {
        assertEquals("en", TrackSelection.normalize("ENG"))
        assertEquals("en", TrackSelection.normalize("en_GB"))
        assertNull(TrackSelection.normalize("und"))
        assertEquals("ja", TrackSelection.normalize("jpn"))
    }
}
