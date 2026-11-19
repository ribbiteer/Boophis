// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import app.boophis.model.FolderKind
import app.boophis.model.FolderSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryFoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(vararg parts: String): File = File(tmp.root, parts.joinToString("/")).also { it.mkdirs() }
    private fun touch(dir: File, name: String): File = File(dir, name).also { it.parentFile.mkdirs(); it.writeText("") }

    /** Phone has Movies, TV and a plain Videos folder; the SD card has Shows. */
    private fun volumes(): List<VolumeInfo> {
        dir("phone", "Movies"); dir("phone", "TV"); dir("phone", "Videos"); dir("sd", "Shows")
        return listOf(
            VolumeInfo(File(tmp.root, "sd"), "SD card", false),
            VolumeInfo(File(tmp.root, "phone"), "Phone", true),
        )
    }

    private fun p(vararg parts: String) = File(tmp.root, parts.joinToString("/")).path

    // ---- config ---------------------------------------------------------------------------

    @Test fun configRoundTripsAndToleratesGarbage() {
        val c = FolderConfig()
            .add(p("phone", "Videos"), FolderKind.SHOWS, detected = false)
            .add(p("phone", "TV"), FolderKind.MOVIES, detected = true)
            .remove(p("phone", "Movies"), detected = true)
        assertEquals(c, FolderConfig.decode(c.encode()))
        assertEquals(FolderConfig(), FolderConfig.decode(null))
        assertEquals(FolderConfig(), FolderConfig.decode("junk\nA\tNOPE\t/x\nA\tMOVIES\n\u0000"))
    }

    @Test fun addOnDetectedSetsKindAndEnables() {
        val tv = p("phone", "TV")
        val c = FolderConfig().remove(tv, detected = true)
        assertTrue(tv in c.disabled)
        val c2 = c.add(tv, FolderKind.MOVIES, detected = true)
        assertFalse(tv in c2.disabled)
        assertEquals(FolderKind.MOVIES, c2.kinds[tv])
        assertTrue(c2.added.isEmpty())
    }

    @Test fun removeDeletesAddedAndDisablesDetected() {
        val v = p("phone", "Videos")
        val c = FolderConfig().add(v, FolderKind.MOVIES, detected = false)
        assertEquals(FolderConfig(), c.remove(v, detected = false))
        val m = p("phone", "Movies")
        assertEquals(setOf(m), FolderConfig().remove(m, detected = true).disabled)
        assertEquals(FolderConfig(), FolderConfig().remove(v, detected = false)) // unknown path: no-op
        assertEquals(FolderConfig(), FolderConfig().remove(m, true).restore(m))
    }

    // ---- effective roots and listing ------------------------------------------------------

    @Test fun effectiveRootsCombineDetectedAddedAndOverrides() {
        val vols = volumes()
        val cfg = FolderConfig()
            .remove(p("phone", "Movies"), detected = true)
            .add(p("phone", "TV"), FolderKind.MOVIES, detected = true)
            .add(p("phone", "Videos"), FolderKind.SHOWS, detected = false)
        val roots = FolderPlanner.effectiveRoots(vols, cfg)
        assertEquals(
            listOf(p("phone", "TV") to LibraryRoot.Kind.MOVIES, p("sd", "Shows") to LibraryRoot.Kind.SHOWS,
                p("phone", "Videos") to LibraryRoot.Kind.SHOWS),
            roots.map { it.dir.path to it.kind },
        )
    }

    @Test fun listingOrderLabelsAndPaths() {
        val vols = volumes()
        val cfg = FolderConfig()
            .add(p("sd", "Misc"), FolderKind.MOVIES, detected = false)
            .add(p("phone", "Videos", "Kids"), FolderKind.MOVIES, detected = false)
            .add(p("phone", "Videos"), FolderKind.SHOWS, detected = false)
            .remove(p("phone", "TV"), detected = true)
        val list = FolderPlanner.folders(vols, cfg, emptyList(), emptyList(), emptySet())
        // detected first (phone before sd), then added (phone before sd, by path)
        assertEquals(
            listOf("Movies", "TV", "Shows", "Videos", "Videos/Kids", "Misc"),
            list.map { it.displayPath.replace(File.separatorChar, '/') },
        )
        assertEquals(listOf("Phone", "Phone", "SD card", "Phone", "Phone", "SD card"), list.map { it.volumeLabel })
        assertEquals(
            List(3) { FolderSource.DETECTED } + List(3) { FolderSource.ADDED },
            list.map { it.source },
        )
        assertFalse(list.first { it.displayPath == "TV" }.enabled)
        assertTrue(list.filter { it.displayPath != "Misc" && !it.displayPath.endsWith("Kids") }.all { it.available })
        assertFalse(list.first { it.displayPath == "Misc" }.available)
    }

    @Test fun itemCountsAttributeToDeepestRootAndStayNullUntilRead() {
        val vols = volumes()
        val cfg = FolderConfig().add(p("phone", "Movies", "Kids"), FolderKind.MOVIES, detected = false)
        val movies = listOf(p("phone", "Movies", "a.mkv"), p("phone", "Movies", "b", "b.mkv"), p("phone", "Movies", "Kids", "k.mkv"))
        val unread = FolderPlanner.folders(vols, cfg, emptyList(), emptyList(), emptySet())
        assertTrue(unread.all { it.itemCount == null })
        val read = FolderPlanner.folders(vols, cfg, movies, emptyList(), setOf(p("phone", "Movies"), p("sd", "Shows"), p("phone", "Movies", "Kids")))
        assertEquals(2, read.first { it.displayPath == "Movies" }.itemCount)
        assertEquals(1, read.first { it.displayPath == "Movies/Kids".replace('/', File.separatorChar) }.itemCount)
        assertEquals(0, read.first { it.displayPath == "Shows" }.itemCount)
        assertNull(read.first { it.displayPath == "TV" }.itemCount) // never read
    }

    @Test fun unavailableFolderKeepsItsItems() {
        val vols = volumes()
        val sdShows = p("sd", "Shows")
        val list = FolderPlanner.folders(
            vols, FolderConfig(), emptyList(), listOf(File(sdShows, "A").path, File(sdShows, "B").path), emptySet(),
            isDirectory = { it.path != sdShows },
        )
        val f = list.first { it.path == sdShows }
        assertFalse(f.available)
        assertEquals(2, f.itemCount)
        assertTrue(f.enabled)
    }

    // ---- overlap --------------------------------------------------------------------------

    @Test fun nestedRootsNeverIndexAFileTwice() {
        val movies = dir("v", "Movies")
        touch(File(movies, "Alpha (2020)"), "Alpha (2020).mkv")
        val kids = dir("v", "Movies", "Kids Shows")
        touch(File(kids, "Pup Patrol/Season 01"), "Pup Patrol S01E01.mkv")
        touch(File(kids, "Pup Patrol/Season 01"), "Pup Patrol S01E02.mkv")
        val deepMovies = dir("v", "Movies", "Kids Shows", "Pup Patrol", "Season 01", "Films")
        touch(deepMovies, "Pup Film (2021).mkv")

        val roots = listOf(
            LibraryRoot(movies, LibraryRoot.Kind.MOVIES),
            LibraryRoot(kids, LibraryRoot.Kind.SHOWS),
            LibraryRoot(deepMovies, LibraryRoot.Kind.MOVIES),
        )
        val lib = LibraryScanner().scan(roots)
        assertEquals(listOf("Alpha"), lib.movies.filter { "Pup Film" !in it.title }.map { it.title })
        assertEquals(1, lib.movies.count { it.title == "Pup Film" })
        assertEquals(listOf("Pup Patrol"), lib.shows.map { it.title })
        assertEquals(2, lib.shows.single().seasons.single().episodes.size)
        val all = lib.movies.map { it.file.path } + lib.shows.flatMap { s -> s.seasons.flatMap { se -> se.episodes.map { it.file.path } } }
        assertEquals(all.size, all.toSet().size)
        assertEquals(4, all.size)
    }

    @Test fun nestedRootSkippedEvenWhenOuterIsShows() {
        val shows = dir("v2", "TV")
        touch(File(shows, "Show A"), "Show A S01E01.mkv")
        val inner = dir("v2", "TV", "Show A", "Movies")
        touch(inner, "Film (2000).mkv")
        val lib = LibraryScanner().scan(
            listOf(LibraryRoot(shows, LibraryRoot.Kind.SHOWS), LibraryRoot(inner, LibraryRoot.Kind.MOVIES)),
        )
        assertEquals(listOf("Film"), lib.movies.map { it.title })
        assertEquals(1, lib.shows.single().seasons.single().episodes.size)
    }

    // ---- watch retention ------------------------------------------------------------------

    @Test fun watchRowsOfExistingFilesSurviveFolderRemoval() {
        val mounted = listOf(p("phone"))
        val onDisk = touch(dir("phone", "Movies"), "A (2020).mkv")
        val gone = File(dir("phone", "Movies"), "Deleted (2019).mkv")
        val stale = WatchPruner.stale(
            watchPaths = listOf(onDisk.path, gone.path),
            indexed = emptySet(), // folder was removed, so nothing is indexed
            mountedVolumeDirs = mounted,
        )
        assertEquals(listOf(gone.path), stale)
    }

    @Test fun watchRowsOfIndexedOrUnmountedFilesAreKept() {
        val sdFile = File(p("sd", "Shows", "A", "A S01E01.mkv"))
        val indexed = p("phone", "Movies", "x.mkv")
        val stale = WatchPruner.stale(
            watchPaths = listOf(sdFile.path, indexed),
            indexed = setOf(indexed),
            mountedVolumeDirs = listOf(p("phone")), // card is out: its files are not judged
            exists = { false },
        )
        assertTrue(stale.isEmpty())
    }

    @Test fun removeThenReAddRestoresTheSameRoots() {
        val vols = volumes()
        val m = p("phone", "Movies")
        val removed = FolderConfig().remove(m, detected = true)
        assertFalse(FolderPlanner.effectiveRoots(vols, removed).any { it.dir.path == m })
        assertTrue(FolderPlanner.effectiveRoots(vols, removed.restore(m)).any { it.dir.path == m })
    }

    // ---- guessKind ------------------------------------------------------------------------

    @Test fun guessByName() {
        assertEquals(FolderKind.SHOWS, KindGuesser.guess(dir("g1", "TV Shows")))
        assertEquals(FolderKind.SHOWS, KindGuesser.guess(dir("g2", "shows")))
        assertEquals(FolderKind.MOVIES, KindGuesser.guess(dir("g3", "Movies")))
        assertEquals(FolderKind.MOVIES, KindGuesser.guess(dir("g4", "Videos")))
        assertEquals(FolderKind.MOVIES, KindGuesser.guess(File(tmp.root, "does-not-exist")))
    }

    @Test fun guessBySeasonFolders() {
        val a = dir("g5", "Stuff", "Some Show", "Season 01")
        assertEquals(FolderKind.SHOWS, KindGuesser.guess(File(a.parentFile.parentFile.path))) // two levels down
        dir("g6", "Season 2")
        assertEquals(FolderKind.SHOWS, KindGuesser.guess(File(tmp.root, "g6")))
        dir("g7", "Some Show", "Specials")
        assertEquals(FolderKind.SHOWS, KindGuesser.guess(File(tmp.root, "g7")))
    }

    @Test fun guessByEpisodeNaming() {
        val eps = dir("g8")
        for (n in 1..3) touch(eps, "Thing.S01E0$n.1080p.mkv")
        touch(eps, "Extra (2020).mkv")
        assertEquals(FolderKind.SHOWS, KindGuesser.guess(eps))

        val grid = dir("g9")
        touch(grid, "Show 1x01.mkv"); touch(grid, "Show 1x02.mkv")
        assertEquals(FolderKind.SHOWS, KindGuesser.guess(grid))

        val films = dir("g10")
        touch(films, "Alpha (2020).mkv"); touch(films, "Beta (2021).mkv"); touch(films, "Thing.S01E01.mkv")
        assertEquals(FolderKind.MOVIES, KindGuesser.guess(films))
        assertEquals(FolderKind.MOVIES, KindGuesser.guess(dir("g11")))
    }
}
