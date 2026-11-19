// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.room.Room
import androidx.room.withTransaction
import app.boophis.library.db.BoophisDatabase
import app.boophis.library.db.WatchStateEntity
import app.boophis.model.Episode
import app.boophis.model.FolderKind
import app.boophis.model.LibraryFolder
import app.boophis.model.HomeFeed
import app.boophis.model.MediaLibrary
import app.boophis.model.MediaRef
import app.boophis.model.Movie
import app.boophis.model.Playable
import app.boophis.model.ScanReason
import app.boophis.model.ScanResult
import app.boophis.model.ScanState
import app.boophis.model.Show
import app.boophis.model.ShowDetail
import app.boophis.model.WatchState
import app.boophis.model.WatchStateStore
import app.boophis.model.rules.NextUp
import app.boophis.model.rules.ResumePolicy
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room-backed [MediaLibrary] + [WatchStateStore]. All work runs on Dispatchers.IO.
 *
 * Scans are two-phase: a fast structural pass (walk + NFO + sidecar art, one DB transaction) followed by a
 * background probe pass (duration, tracks, embedded art, frame grabs) that only touches new/changed files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoophisLibrary private constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: BoophisDatabase,
) : MediaLibrary, WatchStateStore {

    private val dao = db.dao()
    private val io = Dispatchers.IO
    private val probeDispatcher = Dispatchers.IO.limitedParallelism(3)
    private val store = LibraryStore(db)
    private val probe = MediaProbe(File(context.filesDir, "artwork"))

    private val prefs = context.getSharedPreferences("boophis_library", Context.MODE_PRIVATE)
    private val config = MutableStateFlow(FolderConfig.decode(prefs.getString(KEY_FOLDERS, null)))

    /** Folders a scan has finished reading in this process (an empty folder then shows 0 instead of null). */
    private val readFolders = MutableStateFlow<Set<String>>(emptySet())

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle(loadLastScan()))
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Bumped after every write; the snapshot reloads from Room on each bump (conflated). */
    private val version = MutableStateFlow(0L)
    private val snapshot: StateFlow<Snapshot?> = version
        .mapLatest { loadSnapshot() }
        .flowOn(io)
        .stateIn(scope, SharingStarted.Eagerly, null)

    private suspend fun loadSnapshot(): Snapshot = db.withTransaction {
        SnapshotBuilder.build(
            dao.movies(), dao.shows(), dao.seasons(), dao.episodes(), dao.mediaFiles(), dao.watchStates(),
        )
    }

    private fun bump() { version.value = version.value + 1 }

    // ---- folders --------------------------------------------------------------------------

    private fun volumes() = Volumes.volumes(context)

    private fun updateConfig(f: (FolderConfig) -> FolderConfig) {
        synchronized(config) {
            val next = f(config.value)
            if (next == config.value) return
            config.value = next
            prefs.edit().putString(KEY_FOLDERS, next.encode()).apply()
        }
    }

    override fun folders(): Flow<List<LibraryFolder>> =
        combine(config, snap(), readFolders, scanState) { cfg, s, read, _ ->
            FolderPlanner.folders(volumes(), cfg, s.movies.map { it.file.path }, s.showFolders, read)
        }.flowOn(io).distinctUntilChanged()

    override fun addFolder(path: String, kind: FolderKind) {
        val p = File(path).path
        updateConfig { it.add(p, kind, FolderPlanner.isDetectedPath(p, volumes())) }
        rescan(ScanReason.MANUAL)
    }

    override fun removeFolder(path: String) {
        val p = File(path).path
        updateConfig { it.remove(p, FolderPlanner.isDetectedPath(p, volumes())) }
        rescan(ScanReason.MANUAL)
    }

    override fun restoreFolder(path: String) {
        updateConfig { it.restore(File(path).path) }
        rescan(ScanReason.MANUAL)
    }

    override suspend fun guessKind(path: String): FolderKind = withContext(io) { KindGuesser.guess(File(path)) }

    private fun loadLastScan(): ScanResult? {
        if (!prefs.contains(KEY_LAST_AT)) return null
        return ScanResult(
            added = prefs.getInt("last_added", 0), updated = prefs.getInt("last_updated", 0),
            removed = prefs.getInt("last_removed", 0), unchanged = prefs.getInt("last_unchanged", 0),
            durationMs = prefs.getLong("last_duration", 0), finishedAt = prefs.getLong(KEY_LAST_AT, 0),
        )
    }

    private fun saveLastScan(r: ScanResult) {
        prefs.edit().putInt("last_added", r.added).putInt("last_updated", r.updated).putInt("last_removed", r.removed)
            .putInt("last_unchanged", r.unchanged).putLong("last_duration", r.durationMs).putLong(KEY_LAST_AT, r.finishedAt).apply()
    }

    // ---- scan control ---------------------------------------------------------------------

    private val lock = Any()
    private var runnerActive = false
    private var runnerJob: Job? = null
    private var probeJob: Job? = null
    private var pending: ScanReason? = null
    private val scanMutex = Mutex()

    override fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    override fun rescan(reason: ScanReason) {
        synchronized(lock) {
            pending = reason
            if (runnerActive) {
                probeJob?.cancel() // incremental: unprobed rows are picked up by the next pass
                return
            }
            runnerActive = true
            _scanState.value = ScanState.Scanning(reason)
            runnerJob = scope.launch(io) { runLoop() }
        }
    }

    /** Suspends until no scan or probe pass is running or queued. */
    suspend fun awaitIdle() {
        while (true) {
            val j = synchronized(lock) { runnerJob?.takeIf { runnerActive } } ?: break
            j.join()
        }
    }

    private suspend fun runLoop() {
        while (true) {
            val reason = synchronized(lock) {
                val r = pending
                if (r == null) { runnerActive = false; return }
                pending = null
                r
            }
            _scanState.value = ScanState.Scanning(reason)
            val result = scanMutex.withLock {
                try {
                    if (!hasStorageAccess()) {
                        ScanResult(error = "Storage access not granted", finishedAt = System.currentTimeMillis())
                    } else {
                        scanConfigured(reason)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    ScanResult(error = t.message ?: t::class.java.simpleName, finishedAt = System.currentTimeMillis())
                }
            }
            if (result.error == null) saveLastScan(result)
            _scanState.value = ScanState.Idle(result)
            if (result.error == null) {
                val job = scope.launch(io) { runProbePass() }
                synchronized(lock) {
                    probeJob = job
                    if (pending != null) job.cancel()
                }
                job.join()
            }
        }
    }

    /** Scans the effective roots (enabled detected folders + added folders). */
    private suspend fun scanConfigured(reason: ScanReason): ScanResult {
        val vols = volumes()
        val entries = FolderPlanner.entries(vols, config.value).filter { it.enabled }
        val byPath = entries.associateBy { it.root.dir.path }
        var seen = 0
        var current: LibraryFolder? = null
        val r = scanAndStore(
            entries.map { it.root },
            progress = { n -> seen = n; _scanState.value = ScanState.Scanning(reason, n, current) },
            onRoot = { root ->
                current = byPath[root.dir.path]?.let { FolderPlanner.toFolder(it, null, true) }
                _scanState.value = ScanState.Scanning(reason, seen, current)
            },
            mountedVolumeDirs = vols.map { it.dir.path },
        )
        readFolders.value = readFolders.value + entries.filter { it.root.dir.isDirectory }.map { it.root.dir.path }
        return r
    }

    private suspend fun scanAndStore(
        roots: List<LibraryRoot>,
        progress: (Int) -> Unit,
        onRoot: (LibraryRoot) -> Unit = {},
        mountedVolumeDirs: List<String> = emptyList(),
    ): ScanResult {
        val start = System.currentTimeMillis()
        val scanned = LibraryScanner(progress, onRoot).scan(roots)
        val now = System.currentTimeMillis()
        // Unavailable roots (card removed) keep their rows; the scan saw nothing there.
        val unavailable = roots.filter { !it.dir.isDirectory }.map { it.dir.path }
        val r = store.sync(scanned, now, unavailable)
        if (mountedVolumeDirs.isNotEmpty()) {
            // Only watch rows of files that are gone from disk are dropped; removed folders keep their progress.
            val stale = WatchPruner.stale(dao.watchPaths(), dao.filePaths().toHashSet(), mountedVolumeDirs)
            stale.chunked(500).forEach { dao.deleteWatches(it) }
        }
        bump()
        return r.copy(durationMs = System.currentTimeMillis() - start)
    }

    /** Test / smoke hook: scans explicit directories (volume dirs, or dirs named Movies/TV/Shows/TV Shows). */
    suspend fun scanRoots(roots: List<File>, probe: Boolean = true): ScanResult = withContext(io) {
        val r = scanMutex.withLock {
            try {
                scanAndStore(RootDetector.fromExplicit(roots), progress = { })
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ScanResult(error = t.message ?: t::class.java.simpleName, finishedAt = System.currentTimeMillis())
            }
        }
        if (probe && r.error == null) runProbePass()
        r
    }

    private suspend fun runProbePass() {
        // Frame grabs from before the quality-aware picker (v1 key, "-frame.jpg") are re-probed and their files removed.
        File(context.filesDir, "artwork").listFiles()?.filter { it.name.endsWith("-frame.jpg") }?.forEach { it.delete() }
        dao.resetLegacyFrameGrabs()
        val todo = dao.unprobed()
        if (todo.isEmpty()) return
        val needArt = dao.pathsNeedingArt().toHashSet()
        for (chunk in todo.chunked(12)) {
            currentCoroutineContext().ensureActive()
            val results = coroutineScope {
                chunk.map { f ->
                    async(probeDispatcher) {
                        f to runCatching { probe.probe(f.path, f.sizeBytes, f.mtimeMs, f.path in needArt) }.getOrNull()
                    }
                }.awaitAll()
            }
            db.withTransaction {
                for ((f, r) in results) {
                    dao.applyProbe(
                        f.path, f.sizeBytes, f.mtimeMs, r?.durationMs ?: 0, r?.width, r?.height, r?.codec,
                        TrackCodec.encode(r?.tracks ?: emptyList()), r?.embeddedArt, r?.frameArt,
                    )
                }
            }
            bump()
        }
    }

    // ---- reads ----------------------------------------------------------------------------

    private fun snap(): Flow<Snapshot> = snapshot.filterNotNull()

    override fun home(): Flow<HomeFeed> = snap().map { it.home }.distinctUntilChanged()
    override fun movies(): Flow<List<Movie>> = snap().map { it.movies }.distinctUntilChanged()
    override fun shows(): Flow<List<Show>> = snap().map { it.shows }.distinctUntilChanged()
    override fun movie(id: Long): Flow<Movie?> = snap().map { it.moviesById[id] }.distinctUntilChanged()
    override fun showDetail(showId: Long): Flow<ShowDetail?> = snap().map { it.showDetails[showId] }.distinctUntilChanged()
    override fun episode(id: Long): Flow<Episode?> = snap().map { it.episodesById[id] }.distinctUntilChanged()

    override suspend fun playable(ref: MediaRef): Playable? {
        val s = snap().first()
        return when (ref.kind) {
            MediaRef.Kind.MOVIE -> s.moviesById[ref.id]
            MediaRef.Kind.EPISODE -> s.episodesById[ref.id]
        }
    }

    override suspend fun nextEpisodeAfter(episodeId: Long): Episode? {
        val s = snap().first()
        val ep = s.episodesById[episodeId] ?: return null
        val all = s.showDetails[ep.showId]?.episodes ?: return null
        return NextUp.after(ep, all)
    }

    override suspend fun lastInProgress(): Playable? = snap().first().home.continueWatching.firstOrNull()

    // ---- watch state ----------------------------------------------------------------------

    private suspend fun pathOf(ref: MediaRef): String? = when (ref.kind) {
        MediaRef.Kind.MOVIE -> dao.moviePath(ref.id)
        MediaRef.Kind.EPISODE -> dao.episodePath(ref.id)
    }

    private fun WatchStateEntity.toDomain() = WatchState(positionMs, durationMs, watched, lastPlayedAt)

    override suspend fun savePosition(ref: MediaRef, positionMs: Long, durationMs: Long) {
        withContext(io) {
            val path = pathOf(ref) ?: return@withContext
            val prev = dao.watch(path)?.toDomain() ?: WatchState.UNWATCHED
            val next = ResumePolicy.afterProgress(prev, positionMs, durationMs, System.currentTimeMillis())
            dao.upsertWatch(WatchStateEntity(path, next.positionMs, next.durationMs, next.watched, next.lastPlayedAt))
            bump()
        }
    }

    override suspend fun setWatched(ref: MediaRef, watched: Boolean) {
        withContext(io) {
            val path = pathOf(ref) ?: return@withContext
            if (watched) {
                val prev = dao.watch(path)
                dao.upsertWatch(WatchStateEntity(path, 0, prev?.durationMs ?: 0, true, System.currentTimeMillis()))
            } else {
                dao.deleteWatch(path)
            }
            bump()
        }
    }

    companion object {
        private const val KEY_FOLDERS = "folders"
        private const val KEY_LAST_AT = "last_finished_at"

        fun create(context: Context, scope: CoroutineScope): BoophisLibrary {
            val app = context.applicationContext
            val db = Room.databaseBuilder(app, BoophisDatabase::class.java, "boophis.db")
                .setQueryCoroutineContext(Dispatchers.IO)
                .fallbackToDestructiveMigration(true)
                .build()
            return BoophisLibrary(app, scope, db)
        }
    }
}
