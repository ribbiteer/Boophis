// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import app.boophis.model.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class FormatTest {
    @Test fun clock() {
        assertEquals("12:34", formatClock(754_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("0:00", formatClock(-5))
    }

    @Test fun runtime() {
        assertEquals("1h 52m", formatRuntime(112 * 60_000L))
        assertEquals("2h", formatRuntime(120 * 60_000L))
        assertEquals("45m", formatRuntime(45 * 60_000L))
        assertNull(formatRuntime(0))
    }

    @Test fun labels() {
        assertEquals("Play", playLabel(WatchState()))
        assertEquals("Resume 12:34", playLabel(WatchState(positionMs = 754_000, durationMs = 6_000_000)))
        assertEquals("Play", playLabel(WatchState(positionMs = 60_000, durationMs = 6_000_000)))
        assertEquals("2020 · 1h 5m", metaLine("2020", null, "1h 5m"))
    }

    @Test fun relative() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_700_000_000_000L // 2023-11-14 22:13 UTC
        assertEquals("just now", relativeTime(now - 20_000, now, utc))
        assertEquals("2 min ago", relativeTime(now - 125_000, now, utc))
        assertEquals("3 h ago", relativeTime(now - 3 * 3_600_000L - 5_000, now, utc))
        assertEquals("yesterday", relativeTime(now - 30 * 3_600_000L, now, utc))
        assertEquals("Nov 10", relativeTime(now - 4 * 86_400_000L, now, utc))
    }

    @Test fun counts() {
        assertEquals("1 movie", countLabel(1, "movie", "movies"))
        assertEquals("14 movies", countLabel(14, "movie", "movies"))
    }
}
