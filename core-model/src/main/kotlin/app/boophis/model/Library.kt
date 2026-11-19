// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ScanReason { APP_START, CAR_SESSION_START, MANUAL }

data class ScanResult(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val unchanged: Int = 0,
    val durationMs: Long = 0,
    val finishedAt: Long = 0,
    /** Human-readable error, null on success. */
    val error: String? = null,
)

sealed interface ScanState {
    /** [last] is the most recent finished scan, persisted across restarts; null before the very first. */
    data class Idle(val last: ScanResult? = null) : ScanState

    /**
     * [filesSeen] counts video files visited so far in this pass. [folder] is the folder being
     * read right now (null between folders), so the UI can say "Looking in Shows · SD card…".
     */
    data class Scanning(val reason: ScanReason, val filesSeen: Int = 0, val folder: LibraryFolder? = null) : ScanState
}

enum class FolderKind { MOVIES, SHOWS }

/** How a folder became part of the library. */
enum class FolderSource {
    /** Found automatically: a top-level `Movies/`, `TV/`, `Shows/` or `TV Shows/` on a volume. */
    DETECTED,

    /** Chosen by the user on the Library folders screen. */
    ADDED,
}

/** One folder the library reads (or, for a disabled detected folder, would read). */
data class LibraryFolder(
    /** Absolute path; the folder's identity. */
    val path: String,
    val kind: FolderKind,
    val source: FolderSource,
    /** "Phone" for the primary volume, otherwise the volume's description (e.g. "SD card"). */
    val volumeLabel: String,
    /** Path relative to its volume, e.g. "Movies" or "Videos/Films". */
    val displayPath: String,
    /** Detected folders can be turned off; added folders are always enabled. */
    val enabled: Boolean,
    /** Movies (MOVIES) or shows (SHOWS) indexed from this folder; null until a scan has read it. */
    val itemCount: Int?,
    /** False when the folder is missing right now (e.g. SD card removed). Its items stay indexed. */
    val available: Boolean,
)

/**
 * Read side of the library plus scan control. Implemented by core-library (Room-backed).
 * All flows re-emit whenever the index or watch state changes. All functions are main-safe.
 */
interface MediaLibrary {
    val scanState: StateFlow<ScanState>

    /**
     * Every folder: detected ones (enabled or not) followed by added ones, each group sorted by
     * volume then path. Re-emits after scans and after any folder change.
     */
    fun folders(): Flow<List<LibraryFolder>>

    /**
     * Adds [path] as a library folder of [kind] (persisted) and starts a rescan. Adding a folder
     * that is already detected just sets its kind and enables it. Paths inside or containing an
     * existing folder are allowed; files are never indexed twice.
     */
    fun addFolder(path: String, kind: FolderKind)

    /** Removes an added folder, or disables a detected one (persisted), then rescans. */
    fun removeFolder(path: String)

    /** Re-enables a disabled detected folder, then rescans. */
    fun restoreFolder(path: String)

    /**
     * Best guess for a folder about to be added: SHOWS when its name is TV/Shows/TV Shows or it
     * holds `Season NN` folders or SxxEyy-named files near the top, otherwise MOVIES.
     */
    suspend fun guessKind(path: String): FolderKind

    /**
     * Starts an incremental rescan (path + size + mtime) of every enabled library folder.
     * Calls while a scan is running are coalesced into one follow-up pass. Never blocks.
     */
    fun rescan(reason: ScanReason)

    /** True once storage access is granted (MANAGE_EXTERNAL_STORAGE). */
    fun hasStorageAccess(): Boolean

    fun home(): Flow<HomeFeed>

    /** All movies, sorted A–Z by sortTitle. */
    fun movies(): Flow<List<Movie>>

    /** All shows, sorted A–Z by sortTitle. */
    fun shows(): Flow<List<Show>>

    fun movie(id: Long): Flow<Movie?>

    fun showDetail(showId: Long): Flow<ShowDetail?>

    fun episode(id: Long): Flow<Episode?>

    suspend fun playable(ref: MediaRef): Playable?

    /** Next episode in (season, episode) order after [episodeId], skipping Specials; null at the end. */
    suspend fun nextEpisodeAfter(episodeId: Long): Episode?

    /** The most recently played item that is still in progress (for Android Auto "open to Resume"). */
    suspend fun lastInProgress(): Playable?
}

/** Write side for playback progress. Implemented by core-library. */
interface WatchStateStore {
    /**
     * Persists the position. The implementation applies [app.boophis.model.rules.ResumePolicy]
     * (marks watched at ≥ 92 % and clears the resume point).
     */
    suspend fun savePosition(ref: MediaRef, positionMs: Long, durationMs: Long)

    suspend fun setWatched(ref: MediaRef, watched: Boolean)
}
