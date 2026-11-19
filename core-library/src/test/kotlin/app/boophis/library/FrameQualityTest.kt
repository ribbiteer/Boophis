// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityTest {
    private fun frame(vararg v: Float) = FrameQuality.stats(FloatArray(100) { v[it % v.size] })

    @Test fun picksFirstAcceptableFrame() {
        val black = frame(0.01f)
        val flatGrey = frame(0.5f)
        val good = frame(0.2f, 0.6f)
        val better = frame(0.4f, 0.9f)
        assertFalse(FrameQuality.isAcceptable(black))
        assertFalse(FrameQuality.isAcceptable(flatGrey))
        assertTrue(FrameQuality.isAcceptable(good))
        assertEquals(2, FrameQuality.pick(listOf(black, flatGrey, good, better)))
    }

    @Test fun fallsBackToBestScore() {
        val a = frame(0.0f, 0.1f)
        val b = frame(0.05f, 0.2f)
        val c = frame(0.01f)
        assertEquals(1, FrameQuality.pick(listOf(a, b, c)))
        assertEquals(-1, FrameQuality.pick(emptyList()))
    }
}
