// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import app.boophis.model.FolderKind
import app.boophis.model.HomeFeed
import app.boophis.model.LibraryFolder
import app.boophis.model.ScanState
import app.boophis.model.WatchState
import app.boophis.model.rules.ResumePolicy
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** "12:34" or "1:02:03". */
internal fun formatClock(ms: Long): String {
    val s = (if (ms < 0) 0 else ms) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

/** "1h 52m", "45m", "2h"; null when unknown. */
internal fun formatRuntime(ms: Long): String? {
    if (ms <= 0) return null
    val total = (ms + 30_000) / 60_000
    val h = total / 60
    val m = total % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${maxOf(m, 1)}m"
    }
}

/** "Resume 12:34" when there is a resume point, else "Play". */
internal fun playLabel(watch: WatchState): String =
    ResumePolicy.resumePosition(watch)?.let { "Resume ${formatClock(it)}" } ?: "Play"

internal fun metaLine(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }
        .map { it.trim().replace(' ', '\u00A0') }
        .joinToString("\u00A0· ")

internal fun ratingLabel(rating: Float?): String? =
    rating?.takeIf { it > 0f }?.let { String.format(Locale.ROOT, "★ %.1f", it) }

/** "just now", "2 min ago", "3 h ago", "yesterday", or a short date. */
internal fun relativeTime(then: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val diff = now - then
    if (diff < 60_000) return "just now"
    if (diff < 3_600_000) return "${diff / 60_000} min ago"
    val a = Calendar.getInstance(zone).apply { timeInMillis = then }
    val b = Calendar.getInstance(zone).apply { timeInMillis = now }
    fun Calendar.dayKey() = get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR)
    val days = b.dayKey() - a.dayKey()
    return when {
        days <= 0 && diff < 86_400_000 -> "${diff / 3_600_000} h ago"
        days == 1 -> "yesterday"
        else -> {
            val month = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[a.get(Calendar.MONTH)]
            if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) "$month ${a.get(Calendar.DAY_OF_MONTH)}"
            else "$month ${a.get(Calendar.DAY_OF_MONTH)}, ${a.get(Calendar.YEAR)}"
        }
    }
}

internal fun countLabel(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"

/**
 * The Home status line: "Updated 2 min ago · 14 movies · 3 shows", or while a scan runs
 * "Looking in Shows · SD card…" / "Updating library…".
 */
internal fun libraryStatusText(scan: ScanState, feed: HomeFeed?, now: Long): String = when (scan) {
    is ScanState.Scanning -> scan.folder?.let { "Looking in ${it.displayPath} · ${it.volumeLabel}…" } ?: "Updating library…"
    is ScanState.Idle -> {
        val last = scan.last
        val head = when {
            last == null || last.finishedAt <= 0L -> null
            last.error != null -> "Update failed"
            else -> "Updated ${relativeTime(last.finishedAt, now)}"
        }
        val counts = feed?.let {
            listOfNotNull(
                it.movieCount.takeIf { n -> n > 0 }?.let { n -> countLabel(n, "movie", "movies") },
                it.showCount.takeIf { n -> n > 0 }?.let { n -> countLabel(n, "show", "shows") },
            )
        }.orEmpty()
        (listOfNotNull(head) + counts).joinToString(" · ").ifEmpty { "Not scanned yet" }
    }
}

/** Setup step 1 summary: "Found Movies · Phone, Shows · SD card" / "No Movies or Shows folders found". */
internal fun foldersSummary(folders: List<LibraryFolder>): String {
    val found = folders.filter { it.enabled && it.available }
        .map { (if (it.kind == FolderKind.MOVIES) "Movies" else "Shows") + " · " + it.volumeLabel }
        .distinct()
    return if (found.isEmpty()) "No Movies or Shows folders found" else "Found " + found.joinToString(", ")
}
