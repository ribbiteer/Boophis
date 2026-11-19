// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import app.boophis.library.db.EpisodeEntity
import app.boophis.library.db.MediaFileEntity
import app.boophis.library.db.MovieEntity
import app.boophis.library.db.SeasonEntity
import app.boophis.library.db.ShowEntity
import app.boophis.library.db.WatchStateEntity
import app.boophis.model.Artwork
import app.boophis.model.Episode
import app.boophis.model.HomeFeed
import app.boophis.model.LibraryItem
import app.boophis.model.MediaFile
import app.boophis.model.Movie
import app.boophis.model.Playable
import app.boophis.model.Season
import app.boophis.model.Show
import app.boophis.model.ShowDetail
import app.boophis.model.WatchState
import app.boophis.model.rules.NextUp
import app.boophis.model.rules.ResumePolicy

/** Fully assembled, immutable view of the library; every public flow is a projection of this. */
internal class Snapshot(
    val movies: List<Movie>,
    val shows: List<Show>,
    val moviesById: Map<Long, Movie>,
    val episodesById: Map<Long, Episode>,
    val showDetails: Map<Long, ShowDetail>,
    val home: HomeFeed,
    /** Folder path of every indexed show (for per-folder counts). */
    val showFolders: List<String> = emptyList(),
)

internal object SnapshotBuilder {
    private const val HOME_CAP = 20

    fun build(
        movieRows: List<MovieEntity>,
        showRows: List<ShowEntity>,
        seasonRows: List<SeasonEntity>,
        episodeRows: List<EpisodeEntity>,
        fileRows: List<MediaFileEntity>,
        watchRows: List<WatchStateEntity>,
    ): Snapshot {
        val files = fileRows.associateBy { it.path }
        val watch = watchRows.associate { it.path to WatchState(it.positionMs, it.durationMs, it.watched, it.lastPlayedAt) }
        fun fileOf(path: String): MediaFile = files[path]?.let {
            MediaFile(
                path = it.path, sizeBytes = it.sizeBytes, mtimeMs = it.mtimeMs, durationMs = it.durationMs,
                width = it.width, height = it.height, videoCodec = it.videoCodec,
                tracks = TrackCodec.decode(it.embeddedTracks) + TrackCodec.decode(it.externalTracks),
            )
        } ?: MediaFile(path, 0, 0)
        fun watchOf(path: String) = watch[path] ?: WatchState.UNWATCHED
        fun runtime(nfoMs: Long, f: MediaFile) = if (f.durationMs > 0) f.durationMs else nfoMs
        val byName = compareBy<LibraryItem, String>(String.CASE_INSENSITIVE_ORDER) { it.sortTitle }
        val tieMtime = HashMap<String, Long>() // "m<id>" / "s<id>" -> newest mtime, for stable "recent" ordering

        val movies = movieRows.map { r ->
            val f = fileOf(r.filePath)
            val e = files[r.filePath]
            val frame = e?.frameArt
            val art = Artwork(
                poster = r.poster ?: e?.embeddedArt ?: frame,
                backdrop = r.backdrop ?: frame ?: e?.embeddedArt,
                thumb = frame ?: e?.embeddedArt,
            )
            tieMtime["m${r.id}"] = f.mtimeMs
            Movie(
                id = r.id, title = r.title, sortTitle = r.sortTitle, year = r.year, plot = r.plot, rating = r.rating,
                contentRating = r.contentRating, genres = genres(r.genres), runtimeMs = runtime(r.runtimeMs, f),
                artwork = art, file = f, watch = watchOf(r.filePath), addedAt = r.addedAt,
            )
        }.sortedWith(byName)

        val epsByShow = episodeRows.groupBy { it.showId }
        val seasonsByShow = seasonRows.groupBy { it.showId }
        val detailsById = HashMap<Long, ShowDetail>()
        val episodesById = HashMap<Long, Episode>()
        val shows = ArrayList<Show>()
        val showFolders = ArrayList<String>()

        for (s in showRows) {
            val epRows = (epsByShow[s.id] ?: emptyList())
                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }, { it.id }))
            if (epRows.isEmpty()) continue
            fun thumbOf(r: EpisodeEntity): String? = r.thumb ?: files[r.filePath]?.let { it.frameArt ?: it.embeddedArt }
            val firstRegular = epRows.firstOrNull { it.seasonNumber > 0 } ?: epRows.first()
            val seasonPosterFallback = seasonsByShow[s.id].orEmpty().sortedBy { it.number }.firstNotNullOfOrNull { it.poster }
            val showArt = Artwork(
                poster = s.poster ?: seasonPosterFallback ?: thumbOf(firstRegular),
                backdrop = s.backdrop ?: thumbOf(firstRegular),
                thumb = null,
            )
            val episodes = epRows.map { r ->
                val f = fileOf(r.filePath)
                val thumb = thumbOf(r)
                tieMtime["s${s.id}"] = maxOf(tieMtime["s${s.id}"] ?: 0, f.mtimeMs)
                Episode(
                    id = r.id, showId = s.id, showTitle = s.title, seasonNumber = r.seasonNumber,
                    episodeNumber = r.episodeNumber, episodeNumberEnd = r.episodeNumberEnd, title = r.title,
                    plot = r.plot, airDate = r.airDate, runtimeMs = runtime(r.runtimeMs, f),
                    artwork = Artwork(poster = showArt.poster ?: thumb, backdrop = showArt.backdrop ?: thumb, thumb = thumb),
                    file = f, watch = watchOf(r.filePath), addedAt = r.addedAt,
                )
            }
            episodes.forEach { episodesById[it.id] = it }
            val seasonNumbers = episodes.map { it.seasonNumber }.distinct().sorted()
            val seasonRowsByNumber = seasonsByShow[s.id].orEmpty().associateBy { it.number }
            val seasons = seasonNumbers.map { n ->
                val eps = episodes.filter { it.seasonNumber == n }
                val row = seasonRowsByNumber[n]
                Season(
                    id = row?.id ?: -(s.id * 1000 + n), showId = s.id, number = n,
                    poster = row?.poster ?: eps.firstNotNullOfOrNull { it.artwork.thumb },
                    episodeCount = eps.size, unwatchedCount = eps.count { !it.watch.watched },
                )
            }
            val show = Show(
                id = s.id, title = s.title, sortTitle = s.sortTitle, year = s.year, plot = s.plot, rating = s.rating,
                contentRating = s.contentRating, genres = genres(s.genres), artwork = showArt,
                seasonCount = seasons.size, episodeCount = episodes.size,
                unwatchedCount = episodes.count { !it.watch.watched }, addedAt = episodes.maxOf { it.addedAt },
            )
            shows.add(show)
            showFolders.add(s.folderPath)
            detailsById[s.id] = ShowDetail(show, seasons, episodes, NextUp.forShow(episodes))
        }
        shows.sortWith(byName)

        return Snapshot(
            movies = movies,
            shows = shows,
            moviesById = movies.associateBy { it.id },
            episodesById = episodesById,
            showDetails = detailsById,
            home = home(movies, shows, detailsById, tieMtime),
            showFolders = showFolders,
        )
    }

    private fun home(
        movies: List<Movie>,
        shows: List<Show>,
        details: Map<Long, ShowDetail>,
        tieMtime: Map<String, Long>,
    ): HomeFeed {
        val allEpisodes = details.values.flatMap { it.episodes }
        val continueWatching: List<Playable> =
            (movies.filter { ResumePolicy.isInProgress(it.watch) } + allEpisodes.filter { ResumePolicy.isInProgress(it.watch) })
                .sortedByDescending { it.watch.lastPlayedAt ?: 0L }
                .take(HOME_CAP)

        val nextUp = details.values
            .mapNotNull { d -> NextUp.forHomeRow(d.episodes)?.let { ep -> ep to (d.episodes.maxOf { it.watch.lastPlayedAt ?: 0L }) } }
            .sortedByDescending { it.second }
            .take(HOME_CAP)
            .map { it.first }

        val recent: List<LibraryItem> = (movies + shows).sortedWith(
            compareByDescending<LibraryItem> { it.addedAt }.thenByDescending {
                tieMtime[(if (it is Movie) "m" else "s") + it.id] ?: 0L
            },
        ).take(HOME_CAP)

        return HomeFeed(
            continueWatching = continueWatching,
            nextUp = nextUp,
            recentlyAdded = recent,
            movies = movies.take(HOME_CAP),
            shows = shows.take(HOME_CAP),
            movieCount = movies.size,
            showCount = shows.size,
            isLibraryEmpty = movies.isEmpty() && shows.isEmpty(),
        )
    }

    private fun genres(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split('\n')
}
