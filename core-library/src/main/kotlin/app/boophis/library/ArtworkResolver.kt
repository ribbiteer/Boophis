// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import java.io.File

/** Case-insensitive view of one directory's files. */
class DirListing(val files: List<File>) {
    private val byLower: Map<String, File> = files.associateBy { it.name.lowercase() }

    fun file(name: String): File? = byLower[name.lowercase()]

    /** First image named `<stem>.<jpg|jpeg|png|webp>`. */
    fun image(stem: String): File? = Naming.IMAGE_EXT.firstNotNullOfOrNull { byLower["${stem.lowercase()}.$it"] }

    fun firstImage(stems: List<String>): File? = stems.firstNotNullOfOrNull { image(it) }

    companion object {
        fun of(dir: File): DirListing = DirListing(dir.listFiles()?.filter { it.isFile } ?: emptyList())
    }
}

/** Sidecar artwork lookup (pure Kotlin over directory listings). Embedded / frame-grab art is handled by the prober. */
object ArtworkResolver {
    /**
     * [bases] are the file base names to try (e.g. the video base name, and the base without a -cd1 suffix).
     * [generic] enables folder-wide names (poster.jpg ...), which only make sense when the folder holds one movie.
     */
    fun moviePoster(dir: DirListing, bases: List<String>, generic: Boolean = true): String? {
        val stems = buildList {
            if (generic) addAll(listOf("poster", "folder", "cover"))
            bases.forEach { add("$it-poster") }
            if (generic) add("movie")
        }
        return dir.firstImage(stems)?.path
    }

    fun movieBackdrop(dir: DirListing, bases: List<String>, generic: Boolean = true): String? {
        val stems = buildList {
            if (generic) addAll(listOf("fanart", "backdrop", "background"))
            bases.forEach { add("$it-fanart"); add("$it-backdrop") }
            if (generic) add("art")
        }
        return dir.firstImage(stems)?.path
    }

    fun showPoster(dir: DirListing): String? = dir.firstImage(listOf("poster", "folder", "cover"))?.path

    fun showBackdrop(dir: DirListing): String? = dir.firstImage(listOf("fanart", "backdrop", "background", "art"))?.path

    /**
     * Season poster: `seasonNN-poster` in the season folder, then at the show root, then
     * `season-specials-poster` (season 0), then `poster`/`folder` inside the season folder.
     */
    fun seasonPoster(showDir: DirListing, seasonDir: DirListing?, number: Int): String? {
        val names = if (number == 0) listOf("season-specials-poster", "season00-poster", "season0-poster")
        else listOf("season%02d-poster".format(number), "season$number-poster")
        seasonDir?.firstImage(names)?.let { return it.path }
        showDir.firstImage(names)?.let { return it.path }
        return seasonDir?.firstImage(listOf("poster", "folder"))?.path
    }

    /** Episode still: `<base>-thumb.*`, then `<base>.jpg|png|...`. */
    fun episodeThumb(dir: DirListing, base: String): String? =
        dir.firstImage(listOf("$base-thumb", base))?.path
}
