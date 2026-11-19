// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** One video file; path + size + mtime key the incremental rescan. */
@Entity(tableName = "media_files")
data class MediaFileEntity(
    @PrimaryKey val path: String,
    val sizeBytes: Long,
    val mtimeMs: Long,
    val durationMs: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    /** Probed audio/subtitle tracks, encoded by TrackCodec. */
    val embeddedTracks: String = "",
    /** Sidecar subtitle tracks, encoded by TrackCodec; refreshed on every scan. */
    val externalTracks: String = "",
    val embeddedArt: String? = null,
    val frameArt: String? = null,
    val probed: Boolean = false,
)

@Entity(tableName = "movies", indices = [Index(value = ["filePath"], unique = true)])
data class MovieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val plot: String?,
    val rating: Float?,
    val contentRating: String?,
    /** Newline-separated. */
    val genres: String,
    val runtimeMs: Long,
    val poster: String?,
    val backdrop: String?,
    val addedAt: Long,
)

@Entity(tableName = "shows", indices = [Index(value = ["folderPath"], unique = true)])
data class ShowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderPath: String,
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val plot: String?,
    val rating: Float?,
    val contentRating: String?,
    val genres: String,
    val poster: String?,
    val backdrop: String?,
)

@Entity(tableName = "seasons", indices = [Index(value = ["showId", "number"], unique = true)])
data class SeasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val showId: Long,
    val number: Int,
    val poster: String?,
)

@Entity(
    tableName = "episodes",
    indices = [Index(value = ["filePath"], unique = true), Index(value = ["showId"])],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val showId: Long,
    val filePath: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeNumberEnd: Int?,
    val title: String,
    val plot: String?,
    val airDate: String?,
    val runtimeMs: Long,
    val thumb: String?,
    val addedAt: Long,
)

/** Keyed by file path so it survives rescans and row-id changes. */
@Entity(tableName = "watch_state")
data class WatchStateEntity(
    @PrimaryKey val path: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val lastPlayedAt: Long?,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM movies") suspend fun movies(): List<MovieEntity>
    @Query("SELECT * FROM shows") suspend fun shows(): List<ShowEntity>
    @Query("SELECT * FROM seasons") suspend fun seasons(): List<SeasonEntity>
    @Query("SELECT * FROM episodes") suspend fun episodes(): List<EpisodeEntity>
    @Query("SELECT * FROM media_files") suspend fun mediaFiles(): List<MediaFileEntity>
    @Query("SELECT * FROM watch_state") suspend fun watchStates(): List<WatchStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMovies(rows: List<MovieEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertShows(rows: List<ShowEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSeasons(rows: List<SeasonEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEpisodes(rows: List<EpisodeEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFiles(rows: List<MediaFileEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertWatch(row: WatchStateEntity)

    @Query("DELETE FROM movies WHERE id IN (:ids)") suspend fun deleteMovies(ids: List<Long>)
    @Query("DELETE FROM shows WHERE id IN (:ids)") suspend fun deleteShows(ids: List<Long>)
    @Query("DELETE FROM seasons WHERE id IN (:ids)") suspend fun deleteSeasons(ids: List<Long>)
    @Query("DELETE FROM episodes WHERE id IN (:ids)") suspend fun deleteEpisodes(ids: List<Long>)
    @Query("DELETE FROM media_files WHERE path IN (:paths)") suspend fun deleteFiles(paths: List<String>)

    @Query("SELECT * FROM watch_state WHERE path = :path") suspend fun watch(path: String): WatchStateEntity?
    @Query("DELETE FROM watch_state WHERE path = :path") suspend fun deleteWatch(path: String)
    @Query("SELECT path FROM watch_state") suspend fun watchPaths(): List<String>
    @Query("SELECT path FROM media_files") suspend fun filePaths(): List<String>
    @Query("DELETE FROM watch_state WHERE path IN (:paths)") suspend fun deleteWatches(paths: List<String>)
    @Query("SELECT filePath FROM movies WHERE id = :id") suspend fun moviePath(id: Long): String?
    @Query("SELECT filePath FROM episodes WHERE id = :id") suspend fun episodePath(id: Long): String?

    @Query("UPDATE media_files SET probed = 0 WHERE frameArt IS NOT NULL AND frameArt NOT LIKE '%-frame2.jpg'")
    suspend fun resetLegacyFrameGrabs()

    @Query("SELECT * FROM media_files WHERE probed = 0") suspend fun unprobed(): List<MediaFileEntity>

    /** Files whose owner lacks sidecar art in at least one slot (candidates for embedded / frame-grab art). */
    @Query(
        "SELECT filePath FROM movies WHERE poster IS NULL OR backdrop IS NULL " +
            "UNION SELECT filePath FROM episodes WHERE thumb IS NULL",
    )
    suspend fun pathsNeedingArt(): List<String>

    /** Guarded by size+mtime so a probe result never lands on a row that changed meanwhile. */
    @Query(
        "UPDATE media_files SET durationMs = :durationMs, width = :width, height = :height, videoCodec = :codec, " +
            "embeddedTracks = :tracks, embeddedArt = :embeddedArt, frameArt = :frameArt, probed = 1 " +
            "WHERE path = :path AND sizeBytes = :size AND mtimeMs = :mtime",
    )
    suspend fun applyProbe(
        path: String, size: Long, mtime: Long, durationMs: Long, width: Int?, height: Int?, codec: String?,
        tracks: String, embeddedArt: String?, frameArt: String?,
    )
}

@Database(
    entities = [
        MediaFileEntity::class, MovieEntity::class, ShowEntity::class, SeasonEntity::class,
        EpisodeEntity::class, WatchStateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BoophisDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
