// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui.preview

import app.boophis.model.*
import app.boophis.model.rules.NextUp
import app.boophis.model.rules.ResumePolicy
import kotlinx.coroutines.flow.*

private const val MIN = 60_000L

object FakeData {
    private fun file(name: String, runtime: Long) = MediaFile("/storage/emulated/0/Movies/$name.mkv", 1_000_000, 0, runtime)

    val movies: List<Movie> = listOf(
        "Arrival of the Tide" to 112, "Blue Harbor" to 98, "Cinder Road" to 134, "Dust and Starlight" to 121,
        "Echoes" to 89, "Fallow Fields" to 105, "Glass Orchard" to 142, "Hollow Crown" to 97,
    ).mapIndexed { i, (t, rt) ->
        val rtMs = rt * MIN
        Movie(
            id = i + 1L, title = t, year = 2008 + i, rating = 6.4f + i * 0.3f, contentRating = if (i % 2 == 0) "PG-13" else "R",
            genres = listOf("Drama", "Adventure"),
            plot = "A quiet coastal town is turned upside down when a stranger arrives with a map nobody can read. " +
                "Over one long summer, old debts are settled and new friendships are tested. ".repeat(2),
            runtimeMs = rtMs, file = file(t, rtMs), addedAt = 1_000L + i,
            watch = when (i) {
                1 -> WatchState(positionMs = 33 * MIN + 14_000, durationMs = rtMs, lastPlayedAt = 5_000)
                3 -> WatchState(watched = true, durationMs = rtMs, lastPlayedAt = 2_000)
                else -> WatchState.UNWATCHED
            },
        )
    }

    private fun episodes(showId: Long, showTitle: String, seasons: Int, perSeason: Int, idBase: Long): List<Episode> =
        (1..seasons).flatMap { s ->
            (1..perSeason).map { e ->
                val n = (s - 1) * perSeason + e
                val rt = 44 * MIN
                Episode(
                    id = idBase + n, showId = showId, showTitle = showTitle, seasonNumber = s, episodeNumber = e,
                    title = "Episode title number $n", plot = "Things happen.", runtimeMs = rt,
                    file = file("$showTitle S${s}E$e", rt), addedAt = 2_000L + n,
                    watch = when {
                        showId == 101L && n <= 2 -> WatchState(watched = true, durationMs = rt, lastPlayedAt = 10_000L + n)
                        showId == 101L && n == 3 -> WatchState(positionMs = 12 * MIN + 34_000, durationMs = rt, lastPlayedAt = 20_000)
                        showId == 102L && n == 1 -> WatchState(watched = true, durationMs = rt, lastPlayedAt = 9_000)
                        else -> WatchState.UNWATCHED
                    },
                )
            }
        }

    val episodes: List<Episode> =
        episodes(101, "The Long Winter", 2, 6, 1000) + episodes(102, "Night Shift", 3, 4, 2000) + episodes(103, "Paper Planes", 1, 8, 3000)

    val shows: List<Show> = listOf(101L to "The Long Winter", 102L to "Night Shift", 103L to "Paper Planes").map { (id, t) ->
        val eps = episodes.filter { it.showId == id }
        Show(
            id = id, title = t, year = 2015 + (id - 101).toInt(), genres = listOf("Drama", "Mystery"), rating = 8.1f,
            contentRating = "TV-14",
            plot = "Winter closes in on a remote station and the crew starts to notice what is missing. ".repeat(3),
            seasonCount = eps.map { it.seasonNumber }.distinct().size, episodeCount = eps.size,
            unwatchedCount = eps.count { !it.watch.watched }, addedAt = 2_000L + id,
        )
    }

    val folders: List<LibraryFolder> = listOf(
        LibraryFolder("/storage/emulated/0/Movies", FolderKind.MOVIES, FolderSource.DETECTED, "Phone", "Movies", true, 8, true),
        LibraryFolder("/storage/emulated/0/Shows", FolderKind.SHOWS, FolderSource.DETECTED, "Phone", "Shows", true, 3, true),
        LibraryFolder("/storage/1234-ABCD/TV", FolderKind.SHOWS, FolderSource.DETECTED, "SD card", "TV", true, 12, true),
        LibraryFolder("/storage/emulated/0/Videos/Films", FolderKind.MOVIES, FolderSource.ADDED, "Phone", "Videos/Films", true, 14, true),
    )

    val foldersWithMissingCard: List<LibraryFolder> = folders.map {
        if (it.volumeLabel == "SD card") it.copy(available = false) else it
    } + LibraryFolder("/storage/1234-ABCD/Kids", FolderKind.SHOWS, FolderSource.ADDED, "SD card", "Kids", true, 5, false) +
        LibraryFolder("/storage/emulated/0/Download/Old", FolderKind.MOVIES, FolderSource.DETECTED, "Phone", "Old", false, null, true)

    fun feed(
        movies: List<Movie> = this.movies,
        shows: List<Show> = this.shows,
        episodes: List<Episode> = this.episodes,
    ): HomeFeed {
        val inProgress = (movies + episodes).filter { ResumePolicy.isInProgress(it.watch) }
            .sortedByDescending { it.watch.lastPlayedAt ?: 0 }
        val nextUp = shows.mapNotNull { s -> NextUp.forHomeRow(episodes.filter { it.showId == s.id }) }
        val recent = (movies + shows).sortedByDescending { it.addedAt }.take(20)
        return HomeFeed(inProgress, nextUp, recent, movies, shows)
    }

    fun detail(showId: Long, episodes: List<Episode> = this.episodes, shows: List<Show> = this.shows): ShowDetail? {
        val show = shows.firstOrNull { it.id == showId } ?: return null
        val eps = episodes.filter { it.showId == showId }.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        val seasons = eps.map { it.seasonNumber }.distinct().map { n ->
            Season(id = showId * 10 + n, showId = showId, number = n, episodeCount = eps.count { it.seasonNumber == n })
        }
        return ShowDetail(show, seasons, eps, NextUp.forShow(eps))
    }

    fun playerState(item: Playable, positionMs: Long = 25 * MIN, playing: Boolean = true) = PlayerState(
        item = item, isPlaying = playing, positionMs = positionMs, durationMs = item.runtimeMs,
        videoAspectRatio = 16f / 9f, hasNext = item is Episode,
    )
}

class FakeLibrary(
    movies: List<Movie> = FakeData.movies,
    shows: List<Show> = FakeData.shows,
    episodes: List<Episode> = FakeData.episodes,
    scanning: Boolean = false,
    folders: List<LibraryFolder> = FakeData.folders,
    lastScan: ScanResult? = ScanResult(finishedAt = System.currentTimeMillis() - 2 * MIN),
) : MediaLibrary {
    val foldersState = MutableStateFlow(folders)
    val moviesState = MutableStateFlow(movies)
    val showsState = MutableStateFlow(shows)
    val episodesState = MutableStateFlow(episodes)

    override val scanState = MutableStateFlow<ScanState>(
        if (scanning) ScanState.Scanning(ScanReason.MANUAL, 42, folders.firstOrNull { it.available }) else ScanState.Idle(lastScan),
    )

    var rescanCount = 0
        private set

    override fun rescan(reason: ScanReason) { rescanCount++ }
    override fun hasStorageAccess() = true

    override fun folders(): Flow<List<LibraryFolder>> = foldersState
    override fun addFolder(path: String, kind: FolderKind) {
        foldersState.update { l ->
            l.filter { it.path != path } + LibraryFolder(
                path, kind, FolderSource.ADDED, "Phone", path.removePrefix("/storage/emulated/0/"), true, null, true,
            )
        }
    }
    override fun removeFolder(path: String) {
        foldersState.update { l ->
            l.mapNotNull { if (it.path != path) it else if (it.source == FolderSource.ADDED) null else it.copy(enabled = false) }
        }
    }
    override fun restoreFolder(path: String) {
        foldersState.update { l -> l.map { if (it.path == path) it.copy(enabled = true) else it } }
    }
    override suspend fun guessKind(path: String): FolderKind =
        if (Regex("tv|show|season", RegexOption.IGNORE_CASE).containsMatchIn(path.substringAfterLast('/'))) FolderKind.SHOWS else FolderKind.MOVIES
    override fun home(): Flow<HomeFeed> =
        combine(moviesState, showsState, episodesState) { m, s, e -> FakeData.feed(m, s, e) }
    override fun movies(): Flow<List<Movie>> = moviesState
    override fun shows(): Flow<List<Show>> = showsState
    override fun movie(id: Long): Flow<Movie?> = moviesState.map { l -> l.firstOrNull { it.id == id } }
    override fun showDetail(showId: Long): Flow<ShowDetail?> =
        combine(showsState, episodesState) { s, e -> FakeData.detail(showId, e, s) }
    override fun episode(id: Long): Flow<Episode?> = episodesState.map { l -> l.firstOrNull { it.id == id } }

    override suspend fun playable(ref: MediaRef): Playable? = when (ref.kind) {
        MediaRef.Kind.MOVIE -> moviesState.value.firstOrNull { it.id == ref.id }
        MediaRef.Kind.EPISODE -> episodesState.value.firstOrNull { it.id == ref.id }
    }

    fun playableNow(ref: MediaRef): Playable? = when (ref.kind) {
        MediaRef.Kind.MOVIE -> moviesState.value.firstOrNull { it.id == ref.id }
        MediaRef.Kind.EPISODE -> episodesState.value.firstOrNull { it.id == ref.id }
    }

    override suspend fun nextEpisodeAfter(episodeId: Long): Episode? {
        val cur = episodesState.value.firstOrNull { it.id == episodeId } ?: return null
        return NextUp.after(cur, episodesState.value.filter { it.showId == cur.showId })
    }

    override suspend fun lastInProgress(): Playable? =
        (moviesState.value + episodesState.value).filter { ResumePolicy.isInProgress(it.watch) }
            .maxByOrNull { it.watch.lastPlayedAt ?: 0 }

    fun updateWatch(ref: MediaRef, f: (WatchState) -> WatchState) {
        when (ref.kind) {
            MediaRef.Kind.MOVIE -> moviesState.update { l -> l.map { if (it.id == ref.id) it.copy(watch = f(it.watch)) else it } }
            MediaRef.Kind.EPISODE -> episodesState.update { l -> l.map { if (it.id == ref.id) it.copy(watch = f(it.watch)) else it } }
        }
    }
}

class FakeWatch(private val library: FakeLibrary? = null) : WatchStateStore {
    override suspend fun savePosition(ref: MediaRef, positionMs: Long, durationMs: Long) {
        library?.updateWatch(ref) { ResumePolicy.afterProgress(it, positionMs, durationMs, 0) }
    }

    override suspend fun setWatched(ref: MediaRef, watched: Boolean) {
        library?.updateWatch(ref) { it.copy(watched = watched, positionMs = if (watched) 0 else it.positionMs) }
    }
}

class FakePlayer(
    private val library: FakeLibrary? = null,
    initial: PlayerState = PlayerState(),
) : PlayerController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PlayerState> = _state

    override fun play(ref: MediaRef, fromStart: Boolean) {
        val item = library?.playableNow(ref) ?: return
        val pos = if (fromStart) 0 else ResumePolicy.resumePosition(item.watch) ?: 0
        _state.value = FakeData.playerState(item, pos)
    }

    override fun togglePlayPause() = _state.update { it.copy(isPlaying = !it.isPlaying) }
    override fun pause() = _state.update { it.copy(isPlaying = false) }
    override fun resume() = _state.update { it.copy(isPlaying = true) }
    override fun seekTo(positionMs: Long) = _state.update { it.copy(positionMs = positionMs) }
    override fun seekBy(deltaMs: Long) = _state.update { it.copy(positionMs = (it.positionMs + deltaMs).coerceIn(0, it.durationMs)) }
    override fun skipToNext() = Unit
    override fun skipToPrevious() = _state.update { it.copy(positionMs = 0) }
    override fun cancelAutoNext() = _state.update { it.copy(autoNext = null) }
    override fun stop() { _state.value = PlayerState() }
}
