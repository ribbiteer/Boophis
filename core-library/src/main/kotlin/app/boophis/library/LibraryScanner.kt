// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import app.boophis.model.TrackInfo
import app.boophis.model.TrackType
import java.io.File

data class LibraryRoot(val dir: File, val kind: Kind) {
    enum class Kind { MOVIES, SHOWS }
}

/** Finds `Movies/`, `TV/`, `Shows/`, `TV Shows/` (case-insensitive) at the top level of a volume. */
object RootDetector {
    fun classify(name: String): LibraryRoot.Kind? = when (name.trim().lowercase()) {
        "movies" -> LibraryRoot.Kind.MOVIES
        "tv", "shows", "tv shows" -> LibraryRoot.Kind.SHOWS
        else -> null
    }

    fun detect(volumeDir: File): List<LibraryRoot> =
        (volumeDir.listFiles() ?: emptyArray()).filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { d -> classify(d.name)?.let { LibraryRoot(d, it) } }

    /** For explicit roots: a directory that is itself named like a root is used directly, otherwise it is a volume. */
    fun fromExplicit(dirs: List<File>): List<LibraryRoot> = dirs.flatMap { d ->
        classify(d.name)?.let { listOf(LibraryRoot(d, it)) } ?: detect(d)
    }
}

data class ScannedFile(val path: String, val sizeBytes: Long, val mtimeMs: Long, val subtitles: List<TrackInfo> = emptyList())

data class ScannedMovie(
    val file: ScannedFile,
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val plot: String?,
    val rating: Float?,
    val contentRating: String?,
    val genres: List<String>,
    val runtimeMs: Long,
    val poster: String?,
    val backdrop: String?,
)

data class ScannedEpisode(
    val file: ScannedFile,
    val season: Int,
    val number: Int,
    val numberEnd: Int?,
    val title: String,
    val plot: String?,
    val aired: String?,
    val runtimeMs: Long,
    val thumb: String?,
)

data class ScannedSeason(val number: Int, val poster: String?, val episodes: List<ScannedEpisode>)

data class ScannedShow(
    val folder: String,
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val plot: String?,
    val rating: Float?,
    val contentRating: String?,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    val seasons: List<ScannedSeason>,
)

data class ScannedLibrary(val movies: List<ScannedMovie>, val shows: List<ScannedShow>) {
    val fileCount: Int get() = movies.size + shows.sumOf { s -> s.seasons.sumOf { it.episodes.size } }
}

/** Sidecar subtitle discovery: `<base>[.<lang>][.forced|.sdh|.cc].srt` etc. */
object SubtitleSidecars {
    private val FLAGS = setOf("forced", "sdh", "cc", "hi", "default")
    private val LANG = Regex("""^[A-Za-z]{2,}([-_][A-Za-z0-9]+)?$""")

    fun find(dir: DirListing, base: String): List<TrackInfo> {
        val prefix = base.lowercase() + "."
        return dir.files.filter { f ->
            val n = f.name.lowercase()
            n.startsWith(prefix) && Naming.extension(n) in Naming.SUBTITLE_EXT
        }.sortedBy { it.name.lowercase() }.map { f ->
            val ext = Naming.extension(f.name)
            val mid = f.name.substring(base.length + 1, f.name.length - ext.length - 1)
            val parts = mid.split('.').filter { it.isNotEmpty() }
            val lower = parts.map { it.lowercase() }
            TrackInfo(
                type = TrackType.SUBTITLE,
                language = parts.firstOrNull { it.lowercase() !in FLAGS && LANG.matches(it) },
                codec = ext,
                isForced = "forced" in lower,
                isDefault = "default" in lower,
                isHearingImpaired = lower.any { it == "sdh" || it == "cc" || it == "hi" },
                externalPath = f.path,
            )
        }
    }
}

/**
 * Pure directory walk and classification. Reads listings, sizes, mtimes and NFO files; no Android, no Room.
 * [onProgress] receives the running count of video files accepted.
 */
class LibraryScanner(
    private val onProgress: (Int) -> Unit = {},
    /** Called as each root starts being read. */
    private val onRoot: (LibraryRoot) -> Unit = {},
) {
    private var seen = 0

    /** Paths of the other roots while one root is scanned: the deepest root owns its subtree, so these are skipped. */
    private var skip: Set<String> = emptySet()

    private fun File.walkable() = isDirectory && !name.startsWith(".") && !Naming.isExtrasDir(name) && path !in skip

    fun scan(roots: List<LibraryRoot>): ScannedLibrary {
        seen = 0
        val movies = ArrayList<ScannedMovie>()
        val shows = ArrayList<ScannedShow>()
        val seenDirs = HashSet<String>()
        for (r in roots) {
            if (!seenDirs.add(r.dir.path)) continue
            skip = roots.map { it.dir.path }.toSet() - r.dir.path
            onRoot(r)
            when (r.kind) {
                LibraryRoot.Kind.MOVIES -> scanMovieDir(r.dir, 0, true, movies)
                LibraryRoot.Kind.SHOWS ->
                    (r.dir.listFiles() ?: emptyArray())
                        .filter { it.walkable() }
                        .sortedBy { it.name.lowercase() }
                        .forEach { d -> scanShow(d)?.let(shows::add) }
            }
        }
        skip = emptySet()
        return ScannedLibrary(movies, shows)
    }

    private fun file(f: File): ScannedFile {
        seen++
        if (seen % 25 == 0) onProgress(seen)
        return ScannedFile(f.path, f.length(), f.lastModified())
    }

    // ---- movies ---------------------------------------------------------------------------

    private fun scanMovieDir(dir: File, depth: Int, isRoot: Boolean, out: MutableList<ScannedMovie>) {
        val entries = (dir.listFiles() ?: return).sortedBy { it.name.lowercase() }
        val listing = DirListing(entries.filter { it.isFile })
        val videos = listing.files.filter { Naming.isVideo(it.name) && !Naming.isSkippableVideo(Naming.baseName(it.name)) }
        val chosen = firstPartsOnly(videos)
        val single = chosen.size == 1 && !isRoot
        for (v in chosen) out.add(movieFrom(v, listing, single, dir.name.takeIf { !isRoot }))
        if (depth < 4) {
            entries.filter { it.walkable() }
                .forEach { scanMovieDir(it, depth + 1, false, out) }
        }
    }

    private fun firstPartsOnly(videos: List<File>): List<File> {
        val minPart = HashMap<String, Int>()
        for (v in videos) Naming.partOf(Naming.baseName(v.name))?.let { (stem, n) ->
            minPart.merge(stem.lowercase(), n) { a, b -> minOf(a, b) }
        }
        return videos.filter { v ->
            val p = Naming.partOf(Naming.baseName(v.name)) ?: return@filter true
            minPart[p.first.lowercase()] == p.second
        }
    }

    private fun movieFrom(video: File, listing: DirListing, single: Boolean, folderName: String?): ScannedMovie {
        val base = Naming.baseName(video.name)
        val stem = Naming.partOf(base)?.first
        val bases = listOfNotNull(base, stem).distinct()
        val nfoFile = bases.firstNotNullOfOrNull { listing.file("$it.nfo") } ?: if (single) listing.file("movie.nfo") else null
        val nfo = nfoFile?.let { NfoParser.parseFile(it) }

        var parsed = Naming.parseTitle(stem ?: base)
        if (parsed.year == null && folderName != null && single) {
            val fp = Naming.parseTitle(folderName)
            if (fp.year != null) parsed = fp
        }
        val title = nfo?.title ?: parsed.title
        return ScannedMovie(
            file = file(video).copy(subtitles = bases.flatMap { SubtitleSidecars.find(listing, it) }.distinctBy { it.externalPath }),
            title = title,
            sortTitle = nfo?.sortTitle ?: Naming.sortTitle(title),
            year = nfo?.year ?: parsed.year,
            plot = nfo?.plot,
            rating = nfo?.rating,
            contentRating = nfo?.mpaa,
            genres = nfo?.genres ?: emptyList(),
            runtimeMs = (nfo?.runtimeMinutes ?: 0) * 60_000L,
            poster = ArtworkResolver.moviePoster(listing, bases, single),
            backdrop = ArtworkResolver.movieBackdrop(listing, bases, single),
        )
    }

    // ---- shows ----------------------------------------------------------------------------

    private fun scanShow(dir: File): ScannedShow? {
        val showListing = DirListing.of(dir)
        val nfo = showListing.file("tvshow.nfo")?.let { NfoParser.parseFile(it) }
        val episodes = ArrayList<ScannedEpisode>()
        val seasonDirs = HashMap<Int, DirListing>()
        walkShow(dir, 0, null, episodes, seasonDirs)
        if (episodes.isEmpty()) return null

        val parsed = Naming.parseTitle(dir.name)
        val title = nfo?.title ?: parsed.title
        val seasons = episodes.groupBy { it.season }.toSortedMap().map { (n, eps) ->
            ScannedSeason(
                n,
                ArtworkResolver.seasonPoster(showListing, seasonDirs[n], n),
                eps.sortedWith(compareBy({ it.number }, { it.file.path.lowercase() })),
            )
        }
        return ScannedShow(
            folder = dir.path,
            title = title,
            sortTitle = nfo?.sortTitle ?: Naming.sortTitle(title),
            year = nfo?.year ?: parsed.year,
            plot = nfo?.plot,
            rating = nfo?.rating,
            contentRating = nfo?.mpaa,
            genres = nfo?.genres ?: emptyList(),
            poster = ArtworkResolver.showPoster(showListing),
            backdrop = ArtworkResolver.showBackdrop(showListing),
            seasons = seasons,
        )
    }

    private fun walkShow(
        dir: File,
        depth: Int,
        folderSeason: Int?,
        out: MutableList<ScannedEpisode>,
        seasonDirs: MutableMap<Int, DirListing>,
    ) {
        val entries = (dir.listFiles() ?: return).sortedBy { it.name.lowercase() }
        val listing = DirListing(entries.filter { it.isFile })
        if (folderSeason != null) seasonDirs.putIfAbsent(folderSeason, listing)
        for (v in listing.files) {
            if (!Naming.isVideo(v.name)) continue
            val base = Naming.baseName(v.name)
            if (Naming.isSkippableVideo(base)) continue
            val nfo = listing.file("$base.nfo")?.let { NfoParser.parseFile(it) }
            val pe = Naming.parseEpisode(base, folderSeason)
            val season = pe?.season ?: folderSeason ?: nfo?.season ?: 1
            val number = pe?.episode ?: nfo?.episode ?: continue
            out.add(
                ScannedEpisode(
                    file = file(v).copy(subtitles = SubtitleSidecars.find(listing, base)),
                    season = season,
                    number = number,
                    numberEnd = pe?.episodeEnd,
                    title = nfo?.title ?: pe?.title ?: "Episode $number",
                    plot = nfo?.plot,
                    aired = nfo?.aired,
                    runtimeMs = (nfo?.runtimeMinutes ?: 0) * 60_000L,
                    thumb = ArtworkResolver.episodeThumb(listing, base),
                ),
            )
        }
        if (depth < 3) {
            entries.filter { it.walkable() }.forEach { d ->
                walkShow(d, depth + 1, Naming.seasonFromFolder(d.name) ?: folderSeason, out, seasonDirs)
            }
        }
    }
}
