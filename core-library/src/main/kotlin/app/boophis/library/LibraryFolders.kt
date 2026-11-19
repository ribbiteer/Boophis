// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.library

import app.boophis.model.FolderKind
import app.boophis.model.FolderSource
import app.boophis.model.LibraryFolder
import java.io.File

/** A mounted storage volume. [label] is "Phone" for the primary one, otherwise the system description. */
internal data class VolumeInfo(val dir: File, val label: String, val primary: Boolean)

internal fun FolderKind.toRootKind() = if (this == FolderKind.SHOWS) LibraryRoot.Kind.SHOWS else LibraryRoot.Kind.MOVIES
internal fun LibraryRoot.Kind.toFolderKind() = if (this == LibraryRoot.Kind.SHOWS) FolderKind.SHOWS else FolderKind.MOVIES

/** True when [path] is [root] or lies inside it. Both must be normalized with File(..).path. */
internal fun isUnder(path: String, root: String): Boolean = path == root || path.startsWith(root + File.separator)

/**
 * The user's folder choices, persisted as text. [added] are user-chosen folders; [disabled] are detected
 * folders that were turned off; [kinds] overrides the kind of a detected folder.
 */
internal data class FolderConfig(
    val added: Map<String, FolderKind> = emptyMap(),
    val disabled: Set<String> = emptySet(),
    val kinds: Map<String, FolderKind> = emptyMap(),
) {
    /** [detected] says whether [path] is an auto-detected folder (then only kind/enabled change). */
    fun add(path: String, kind: FolderKind, detected: Boolean): FolderConfig =
        if (detected) copy(kinds = kinds + (path to kind), disabled = disabled - path)
        else copy(added = added + (path to kind))

    fun remove(path: String, detected: Boolean): FolderConfig = when {
        path in added -> copy(added = added - path)
        detected -> copy(disabled = disabled + path)
        else -> this
    }

    fun restore(path: String): FolderConfig = copy(disabled = disabled - path)

    fun encode(): String = buildString {
        added.forEach { (p, k) -> append("A\t").append(k.name).append('\t').append(p).append('\n') }
        disabled.forEach { append("D\t").append(it).append('\n') }
        kinds.forEach { (p, k) -> append("K\t").append(k.name).append('\t').append(p).append('\n') }
    }

    companion object {
        /** Tolerant: unknown or malformed lines are skipped. */
        fun decode(text: String?): FolderConfig {
            var cfg = FolderConfig()
            for (line in text.orEmpty().lines()) {
                val p = line.split('\t', limit = 3)
                val kind = p.getOrNull(1)?.let { n -> FolderKind.entries.firstOrNull { it.name == n } }
                when (p[0]) {
                    "A" -> if (kind != null && p.size == 3) cfg = cfg.copy(added = cfg.added + (p[2] to kind))
                    "K" -> if (kind != null && p.size == 3) cfg = cfg.copy(kinds = cfg.kinds + (p[2] to kind))
                    "D" -> if (p.size >= 2 && p[1].isNotEmpty()) cfg = cfg.copy(disabled = cfg.disabled + p[1])
                }
            }
            return cfg
        }
    }
}

/** One configured folder before item counts are known. */
internal data class FolderEntry(
    val root: LibraryRoot,
    val source: FolderSource,
    val volume: VolumeInfo?,
    val enabled: Boolean,
)

internal object FolderPlanner {
    fun orderedVolumes(volumes: List<VolumeInfo>) = volumes.sortedWith(compareBy({ !it.primary }, { it.dir.path }))

    /** Auto-detected top-level roots on every volume (volume order, then path). */
    fun detected(volumes: List<VolumeInfo>): List<Pair<VolumeInfo, LibraryRoot>> =
        orderedVolumes(volumes).flatMap { v -> RootDetector.detect(v.dir).sortedBy { it.dir.path }.map { v to it } }

    fun volumeOf(path: String, volumes: List<VolumeInfo>): VolumeInfo? =
        volumes.filter { isUnder(path, it.dir.path) }.maxByOrNull { it.dir.path.length }

    /** Detected (enabled or not) then added, as documented on [app.boophis.model.MediaLibrary.folders]. */
    fun entries(volumes: List<VolumeInfo>, config: FolderConfig): List<FolderEntry> {
        val det = detected(volumes).map { (v, r) ->
            val kind = config.kinds[r.dir.path]?.toRootKind() ?: r.kind
            FolderEntry(LibraryRoot(r.dir, kind), FolderSource.DETECTED, v, r.dir.path !in config.disabled)
        }
        val detPaths = det.map { it.root.dir.path }.toSet()
        val ordered = orderedVolumes(volumes)
        val added = config.added.filterKeys { it !in detPaths }.map { (p, k) ->
            FolderEntry(LibraryRoot(File(p), k.toRootKind()), FolderSource.ADDED, volumeOf(p, volumes), true)
        }.sortedWith(
            compareBy({ e -> ordered.indexOf(e.volume).let { if (it < 0) Int.MAX_VALUE else it } }, { it.root.dir.path }),
        )
        return det + added
    }

    fun effectiveRoots(volumes: List<VolumeInfo>, config: FolderConfig): List<LibraryRoot> =
        entries(volumes, config).filter { it.enabled }.map { it.root }

    fun isDetectedPath(path: String, volumes: List<VolumeInfo>): Boolean = detected(volumes).any { it.second.dir.path == path }

    fun toFolder(e: FolderEntry, itemCount: Int?, available: Boolean): LibraryFolder {
        val path = e.root.dir.path
        return LibraryFolder(
            path = path,
            kind = e.root.kind.toFolderKind(),
            source = e.source,
            volumeLabel = e.volume?.label ?: "Storage",
            displayPath = e.volume?.let { path.removePrefix(it.dir.path).trimStart(File.separatorChar) } ?: path,
            enabled = e.enabled,
            itemCount = itemCount,
            available = available,
        )
    }

    /**
     * Builds the folder list. Items are attributed to the deepest enabled folder containing them (matching the scanner).
     * The count is null for disabled folders and for enabled ones with no items that no scan has read yet ([scanned]).
     */
    fun folders(
        volumes: List<VolumeInfo>,
        config: FolderConfig,
        moviePaths: Collection<String>,
        showFolders: Collection<String>,
        scanned: Set<String>,
        isDirectory: (File) -> Boolean = { it.isDirectory },
    ): List<LibraryFolder> {
        val entries = entries(volumes, config)
        val enabledPaths = entries.filter { it.enabled }.map { it.root.dir.path }
        fun owner(p: String) = enabledPaths.filter { isUnder(p, it) }.maxByOrNull { it.length }
        val movieCounts = moviePaths.groupingBy { owner(it) }.eachCount()
        val showCounts = showFolders.groupingBy { owner(it) }.eachCount()
        return entries.map { e ->
            val path = e.root.dir.path
            val n = (if (e.root.kind == LibraryRoot.Kind.MOVIES) movieCounts[path] else showCounts[path]) ?: 0
            val count = if (!e.enabled) null else if (n > 0 || path in scanned) n else null
            toFolder(e, count, isDirectory(e.root.dir))
        }
    }
}

/** Decides which watch rows may be dropped after a scan. */
internal object WatchPruner {
    /**
     * Watch paths that are no longer indexed, live on a currently mounted volume, and whose file no longer exists.
     * Rows for files that still exist (e.g. in a removed folder) and rows on unmounted volumes are kept.
     */
    fun stale(
        watchPaths: Collection<String>,
        indexed: Set<String>,
        mountedVolumeDirs: List<String>,
        exists: (String) -> Boolean = { File(it).exists() },
    ): List<String> = watchPaths.filter { p ->
        p !in indexed && mountedVolumeDirs.any { isUnder(p, it) } && !exists(p)
    }
}

/** Best-effort kind for a folder about to be added. */
internal object KindGuesser {
    private fun kids(d: File): Array<File> = d.listFiles() ?: emptyArray()

    fun guess(dir: File): FolderKind {
        if (RootDetector.classify(dir.name) == LibraryRoot.Kind.SHOWS) return FolderKind.SHOWS
        val level1 = kids(dir).sortedBy { it.name.lowercase() }
        val dirs1 = level1.filter { it.isDirectory }
        if (dirs1.any { Naming.seasonFromFolder(it.name) != null }) return FolderKind.SHOWS
        val dirs2 = dirs1.take(200).flatMap { d -> kids(d).filter { it.isDirectory } }
        if (dirs2.any { Naming.seasonFromFolder(it.name) != null }) return FolderKind.SHOWS
        val files = level1.filter { it.isFile } +
            dirs1.take(200).flatMap { d -> kids(d).filter { it.isFile } } +
            dirs2.take(200).flatMap { d -> kids(d).filter { it.isFile } }
        val videos = files.filter { Naming.isVideo(it.name) && !Naming.isSkippableVideo(Naming.baseName(it.name)) }
        if (videos.isEmpty()) return FolderKind.MOVIES
        // SxxEyy or NxNN only: parseEpisode yields a season solely for those when no folder season is given.
        val episodic = videos.count { Naming.parseEpisode(Naming.baseName(it.name))?.season != null }
        return if (episodic * 2 > videos.size) FolderKind.SHOWS else FolderKind.MOVIES
    }
}
