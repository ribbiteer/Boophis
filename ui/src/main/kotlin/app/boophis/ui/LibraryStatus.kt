// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.boophis.model.HomeFeed
import app.boophis.model.ScanState
import kotlinx.coroutines.delay

/** Wall clock that ticks every 30 s so "2 min ago" stays honest while the screen is open. */
@Composable
internal fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * One line of library status ("Updated 2 min ago · 14 movies · 3 shows"). Phone: tappable when
 * [onClick] is set (opens Library folders). Car: plain text.
 */
@Composable
internal fun LibraryStatusLine(scan: ScanState, feed: HomeFeed?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val mode = LocalLayoutMode.current
    val text = libraryStatusText(scan, feed, rememberNow())
    LibraryStatusLine(text, modifier, onClick, mode.isCar)
}

@Composable
internal fun LibraryStatusLine(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, car: Boolean = false) {
    val mode = LocalLayoutMode.current
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = if (onClick != null) mode.target else 0.dp)
            .padding(horizontal = mode.gutter, vertical = if (car) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, Modifier.weight(1f, fill = false),
            style = if (car) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, "Library folders", Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Preview helper: idle, scanning a folder, and scanning with no folder. */
@Composable
fun LibraryStatusPreviewHost(mode: app.boophis.model.LayoutMode) {
    val now = System.currentTimeMillis()
    val feed = HomeFeed(movieCount = 14, showCount = 3)
    BoophisTheme(mode) {
        Column(Modifier.padding(vertical = 8.dp)) {
            LibraryStatusLine(ScanState.Idle(app.boophis.model.ScanResult(finishedAt = now - 2 * 60_000)), feed)
            LibraryStatusLine(
                ScanState.Scanning(
                    app.boophis.model.ScanReason.MANUAL, 10,
                    app.boophis.model.LibraryFolder(
                        "/x", app.boophis.model.FolderKind.SHOWS, app.boophis.model.FolderSource.DETECTED,
                        "SD card", "Shows", true, null, true,
                    ),
                ),
                feed,
            )
            LibraryStatusLine(ScanState.Scanning(app.boophis.model.ScanReason.MANUAL), feed)
        }
    }
}
