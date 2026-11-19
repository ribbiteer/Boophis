// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import androidx.room.withTransaction
import app.boophis.library.db.EpisodeEntity
import app.boophis.library.db.MediaFileEntity
import app.boophis.library.db.MovieEntity
import app.boophis.library.db.BoophisDatabase
import app.boophis.library.db.SeasonEntity
import app.boophis.library.db.ShowEntity
import app.boophis.model.ScanResult

/** Writes a [ScannedLibrary] into Room: stable ids per path, batch upserts, deletes of vanished rows. */
internal class LibraryStore(private val db: BoophisDatabase) {
    private val dao = db.dao()

    /**
     * [preserved] are roots that could not be read (unavailable, e.g. SD card removed): rows under them are kept as is.
     * Watch rows are never touched here; see [WatchPruner].
     */
    suspend fun sync(scanned: ScannedLibrary, now: Long, preserved: List<String> = emptyList()): ScanResult = db.withTransaction {
        fun kept(path: String) = preserved.any { isUnder(path, it) }
        val oldFiles = dao.mediaFiles().associateBy { it.path }
        val oldMovies = dao.movies().associateBy { it.filePath }
        val oldShows = dao.shows().associateBy { it.folderPath }
        val oldSeasons = dao.seasons().associateBy { it.showId to it.number }
        val oldEpisodes = dao.episodes().associateBy { it.filePath }

        // ---- files (incremental key: path + size + mtime)
        val allFiles = LinkedHashMap<String, ScannedFile>()
        scanned.movies.forEach { allFiles[it.file.path] = it.file }
        scanned.shows.forEach { s -> s.seasons.forEach { se -> se.episodes.forEach { allFiles[it.file.path] = it.file } } }
        var added = 0
        var updated = 0
        var unchanged = 0
        val fileWrites = ArrayList<MediaFileEntity>()
        for (f in allFiles.values) {
            val old = oldFiles[f.path]
            val ext = TrackCodec.encode(f.subtitles)
            when {
                old == null -> {
                    added++
                    fileWrites.add(MediaFileEntity(f.path, f.sizeBytes, f.mtimeMs, externalTracks = ext))
                }
                old.sizeBytes != f.sizeBytes || old.mtimeMs != f.mtimeMs -> {
                    updated++
                    fileWrites.add(MediaFileEntity(f.path, f.sizeBytes, f.mtimeMs, externalTracks = ext))
                }
                else -> {
                    unchanged++
                    if (old.externalTracks != ext) fileWrites.add(old.copy(externalTracks = ext))
                }
            }
        }
        val goneFiles = oldFiles.keys.filter { it !in allFiles && !kept(it) }
        if (fileWrites.isNotEmpty()) dao.upsertFiles(fileWrites)

        // ---- movies
        val movieRows = scanned.movies.map { m ->
            val old = oldMovies[m.file.path]
            MovieEntity(
                id = old?.id ?: 0, filePath = m.file.path, title = m.title, sortTitle = m.sortTitle, year = m.year,
                plot = m.plot, rating = m.rating, contentRating = m.contentRating, genres = m.genres.joinToString("\n"),
                runtimeMs = m.runtimeMs, poster = m.poster, backdrop = m.backdrop, addedAt = old?.addedAt ?: now,
            )
        }
        val oldMovieIds = oldMovies.mapValues { it.value.id }
        dao.upsertMovies(movieRows.filter { r -> oldMovies[r.filePath] != r })
        val keepMovies = scanned.movies.map { it.file.path }.toSet() + oldMovies.keys.filter(::kept)
        chunked(oldMovieIds.filterKeys { it !in keepMovies }.values.toList()) { dao.deleteMovies(it) }

        // ---- shows
        val showRows = scanned.shows.map { s ->
            val old = oldShows[s.folder]
            ShowEntity(
                id = old?.id ?: 0, folderPath = s.folder, title = s.title, sortTitle = s.sortTitle, year = s.year,
                plot = s.plot, rating = s.rating, contentRating = s.contentRating, genres = s.genres.joinToString("\n"),
                poster = s.poster, backdrop = s.backdrop,
            )
        }
        val changedShows = showRows.filter { oldShows[it.folderPath] != it }
        val newIds = dao.upsertShows(changedShows)
        val showIds = HashMap<String, Long>()
        oldShows.forEach { (k, v) -> showIds[k] = v.id }
        changedShows.forEachIndexed { i, r -> showIds[r.folderPath] = if (r.id != 0L) r.id else newIds[i] }
        val keepShows = scanned.shows.map { it.folder }.toSet() + oldShows.keys.filter(::kept)
        val goneShowIds = oldShows.filterKeys { it !in keepShows }.values.map { it.id }
        chunked(goneShowIds) { dao.deleteShows(it) }

        // ---- seasons + episodes
        val seasonRows = ArrayList<SeasonEntity>()
        val episodeRows = ArrayList<EpisodeEntity>()
        val keepSeasonKeys = HashSet<Pair<Long, Int>>()
        val keepEpisodes = HashSet<String>()
        oldEpisodes.keys.filterTo(keepEpisodes, ::kept)
        oldSeasons.values.filter { se -> oldShows.values.any { it.id == se.showId && kept(it.folderPath) } }
            .forEach { keepSeasonKeys.add(it.showId to it.number) }
        for (s in scanned.shows) {
            val showId = showIds.getValue(s.folder)
            for (se in s.seasons) {
                keepSeasonKeys.add(showId to se.number)
                val old = oldSeasons[showId to se.number]
                val row = SeasonEntity(old?.id ?: 0, showId, se.number, se.poster)
                if (old != row) seasonRows.add(row)
                for (e in se.episodes) {
                    keepEpisodes.add(e.file.path)
                    val oldE = oldEpisodes[e.file.path]
                    val er = EpisodeEntity(
                        id = oldE?.id ?: 0, showId = showId, filePath = e.file.path, seasonNumber = e.season,
                        episodeNumber = e.number, episodeNumberEnd = e.numberEnd, title = e.title, plot = e.plot,
                        airDate = e.aired, runtimeMs = e.runtimeMs, thumb = e.thumb, addedAt = oldE?.addedAt ?: now,
                    )
                    if (oldE != er) episodeRows.add(er)
                }
            }
        }
        dao.upsertSeasons(seasonRows)
        dao.upsertEpisodes(episodeRows)
        chunked(oldSeasons.values.filter { (it.showId to it.number) !in keepSeasonKeys }.map { it.id }) { dao.deleteSeasons(it) }
        chunked(oldEpisodes.values.filter { it.filePath !in keepEpisodes }.map { it.id }) { dao.deleteEpisodes(it) }
        chunked(goneFiles) { dao.deleteFiles(it) }

        ScanResult(added = added, updated = updated, removed = goneFiles.size, unchanged = unchanged, finishedAt = now)
    }

    /** SQLite on API 29 caps bound variables at 999. */
    private suspend fun <T> chunked(items: List<T>, block: suspend (List<T>) -> Unit) {
        items.chunked(500).forEach { block(it) }
    }
}
