// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.model

/** Identifies anything that can be played. Ids are the Room primary keys from core-library. */
data class MediaRef(val kind: Kind, val id: Long) {
    enum class Kind { MOVIE, EPISODE }

    companion object {
        fun movie(id: Long) = MediaRef(Kind.MOVIE, id)
        fun episode(id: Long) = MediaRef(Kind.EPISODE, id)
    }
}

/**
 * Resolved artwork as absolute local file paths (null = none found).
 * Resolution order is sidecar → embedded → cached frame grab; the consumer never needs to know which.
 */
data class Artwork(
    val poster: String? = null,
    /** 16:9 backdrop / fanart. */
    val backdrop: String? = null,
    /** 16:9 still (episode thumb or frame grab). */
    val thumb: String? = null,
) {
    companion object { val NONE = Artwork() }
}

enum class TrackType { AUDIO, SUBTITLE }

/**
 * A track as probed by the indexer (embedded) or found as a sidecar file (external subtitle).
 * [language] is whatever the container/filename says (e.g. "en", "eng", "en-US", "English") or null.
 */
data class TrackInfo(
    val type: TrackType,
    val language: String? = null,
    val label: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /** Hearing-impaired / SDH flag, when known. */
    val isHearingImpaired: Boolean = false,
    /** Absolute path for sidecar subtitle files (.srt/.ass/.vtt); null for embedded tracks. */
    val externalPath: String? = null,
)

/** One video file on disk. Size + mtime + path key the incremental rescan. */
data class MediaFile(
    val path: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    val durationMs: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    /** Embedded audio + subtitle tracks plus sidecar subtitles. */
    val tracks: List<TrackInfo> = emptyList(),
)

/** Persisted watch progress. */
data class WatchState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watched: Boolean = false,
    /** Epoch millis of the last playback, null if never played. */
    val lastPlayedAt: Long? = null,
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    companion object { val UNWATCHED = WatchState() }
}

/** Common surface of anything playable (movie or episode). */
sealed interface Playable {
    val ref: MediaRef
    val title: String
    val file: MediaFile
    val watch: WatchState
    val artwork: Artwork
    val runtimeMs: Long
    val addedAt: Long
}

/** Things that can appear as a poster card in rows and grids. */
sealed interface LibraryItem {
    val id: Long
    val title: String
    val sortTitle: String
    val artwork: Artwork
    val addedAt: Long
}

data class Movie(
    override val id: Long,
    override val title: String,
    override val sortTitle: String = title,
    val year: Int? = null,
    val plot: String? = null,
    /** 0–10 community rating from the NFO, if present. */
    val rating: Float? = null,
    /** e.g. "PG-13", from the NFO if present. */
    val contentRating: String? = null,
    val genres: List<String> = emptyList(),
    override val runtimeMs: Long = 0,
    override val artwork: Artwork = Artwork.NONE,
    override val file: MediaFile,
    override val watch: WatchState = WatchState.UNWATCHED,
    override val addedAt: Long = 0,
) : Playable, LibraryItem {
    override val ref: MediaRef get() = MediaRef.movie(id)
}

data class Show(
    override val id: Long,
    override val title: String,
    override val sortTitle: String = title,
    val year: Int? = null,
    val plot: String? = null,
    val rating: Float? = null,
    val contentRating: String? = null,
    val genres: List<String> = emptyList(),
    override val artwork: Artwork = Artwork.NONE,
    val seasonCount: Int = 0,
    val episodeCount: Int = 0,
    val unwatchedCount: Int = 0,
    /** Newest episode's addedAt (so a show bubbles up in Recently Added when episodes arrive). */
    override val addedAt: Long = 0,
) : LibraryItem

data class Season(
    val id: Long,
    val showId: Long,
    /** 0 = Specials. */
    val number: Int,
    val title: String = if (number == 0) "Specials" else "Season $number",
    val poster: String? = null,
    val episodeCount: Int = 0,
    val unwatchedCount: Int = 0,
)

data class Episode(
    val id: Long,
    val showId: Long,
    val showTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    /** For multi-episode files like S01E01-E02. */
    val episodeNumberEnd: Int? = null,
    override val title: String,
    val plot: String? = null,
    val airDate: String? = null,
    override val runtimeMs: Long = 0,
    /** thumb = episode still; poster/backdrop fall back to the show's art. */
    override val artwork: Artwork = Artwork.NONE,
    override val file: MediaFile,
    override val watch: WatchState = WatchState.UNWATCHED,
    override val addedAt: Long = 0,
) : Playable {
    override val ref: MediaRef get() = MediaRef.episode(id)

    /** "S01E02" or "S01E02-E03". */
    val code: String
        get() = buildString {
            append("S%02dE%02d".format(seasonNumber, episodeNumber))
            episodeNumberEnd?.let { append("-E%02d".format(it)) }
        }
}

/** Everything the Show detail page needs in one emission. Episodes sorted by (season, episode). */
data class ShowDetail(
    val show: Show,
    val seasons: List<Season>,
    val episodes: List<Episode>,
    /** Result of [app.boophis.model.rules.NextUp.forShow]; null when everything is watched. */
    val nextUp: Episode?,
)

/** The Home screen in one emission. Empty lists mean "hide the row". */
data class HomeFeed(
    /** In-progress movies + episodes, most recently played first. */
    val continueWatching: List<Playable> = emptyList(),
    /** One next-up episode per started show, most recently played show first. */
    val nextUp: List<Episode> = emptyList(),
    /** Movies and shows, newest first (capped, ~20). */
    val recentlyAdded: List<LibraryItem> = emptyList(),
    /** A–Z preview of movies (capped, ~20). Full list via [MediaLibrary.movies]. */
    val movies: List<Movie> = emptyList(),
    /** A–Z preview of shows (capped, ~20). Full list via [MediaLibrary.shows]. */
    val shows: List<Show> = emptyList(),
    /** Totals for the library status line ("14 movies · 3 shows"); [movies]/[shows] are capped. */
    val movieCount: Int = movies.size,
    val showCount: Int = shows.size,
    /** True when the library has no movies and no shows at all (show the empty state). */
    val isLibraryEmpty: Boolean = movieCount == 0 && showCount == 0,
)
