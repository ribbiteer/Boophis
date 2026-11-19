// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen sizes are the car content areas measured on the DHU profiles in tools/dhu/. */
class CarGridTest {
    private fun cols(w: Float, h: Float) = carGridColumns(w, h, gutterDp = 24f, spacingDp = 20f)
    private fun cardWidth(w: Float, n: Int) = (w - 48f + 20f) / n - 20f

    @Test fun shortSixInchScreenFitsAWholeRow() {
        val n = cols(737f, 360f) // 800x480 with 50/30 margins, 160 dpi
        assertEquals(5, n)
        val card = cardWidth(737f, n)
        assertTrue("card $card dp", card >= 100f)
        assertTrue("row ${card * 1.5f + 144f} dp", card * 1.5f + 144f <= 360f)
    }

    @Test fun otherSizesMatchAdaptive124() {
        // (width, height) -> what GridCells.Adaptive(124.dp) gives
        assertEquals(5, cols(776f, 395f))   // 800x480, 160 dpi
        assertEquals(6, cols(950f, 560f))   // 1280x720, 200 dpi (8-inch OEM)
        assertEquals(5, cols(770f, 480f))   // very wide, AA split view
        assertEquals(4, cols(720f, 1190f))  // portrait 1080x1920, 240 dpi
    }

    @Test fun unboundedHeightKeepsAdaptive() {
        assertEquals(6, cols(950f, Float.POSITIVE_INFINITY))
    }
}
