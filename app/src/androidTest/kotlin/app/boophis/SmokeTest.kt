// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.boophis.model.ScanReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Needs the fixture tree on the phone (/sdcard/Movies/<fixture folders>, /sdcard/Shows) and
 * MANAGE_EXTERNAL_STORAGE granted via `adb shell appops set io.github.ribbiteer.boophis MANAGE_EXTERNAL_STORAGE allow`.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BoophisApp

    @Test
    fun scansFixturesAndShowsHomeRows() {
        val lib = app.library
        assertTrue("storage access not granted", lib.hasStorageAccess())
        runBlocking {
            lib.rescan(ScanReason.MANUAL)
            lib.awaitIdle()
            val movies = lib.movies().first().map { it.title }
            for (t in listOf("Paper Harbor", "Midnight Orchard", "The Quiet Engine")) {
                assertTrue("missing movie $t in $movies", t in movies)
            }
            val show = lib.shows().first().single { it.title == "Saltmarsh Station" }
            assertEquals(6, show.episodeCount)
            val detail = lib.showDetail(show.id).first()!!
            assertEquals(listOf(1, 2), detail.seasons.map { it.number }.filter { it > 0 })
            val paper = lib.movies().first().single { it.title == "Paper Harbor" }
            assertEquals(2021, paper.year)
            assertTrue("sidecar poster not resolved", paper.artwork.poster?.endsWith("poster.jpg") == true)
        }
        // Rows are lazy: assert the row titles, plus at least one fixture title that is composed on screen.
        for (row in listOf("Recently Added", "Movies", "Shows")) {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(row)).fetchSemanticsNodes().isNotEmpty() }
        }
        val fixtures = listOf("Saltmarsh Station", "Midnight Orchard", "The Quiet Engine", "Paper Harbor")
        compose.waitUntil(5_000) {
            fixtures.any { compose.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
