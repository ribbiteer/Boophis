// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.boophis.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Phone only. Lists the folders the library reads; add, remove, switch detected ones off. */
@Composable
internal fun LibraryFoldersScreen(actions: Actions) {
    val library = actions.deps.library
    val picker = actions.deps.folderPicker
    val folders by remember(library) { library.folders() }.collectAsState(initial = emptyList())
    val feed by remember(library) { library.home() }.collectOrNull()
    val scan by library.scanState.collectAsState()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, FolderKind>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000)
            message = null
        }
    }

    LibraryFoldersContent(
        folders = folders,
        scan = scan,
        feed = feed,
        message = message,
        canAdd = picker != null,
        onBack = { actions.nav.back() },
        onToggle = { f -> if (f.enabled) library.removeFolder(f.path) else library.restoreFolder(f.path) },
        onRemove = { library.removeFolder(it.path) },
        onAdd = {
            picker?.pick { path ->
                if (path == null) message = "Choose a folder on your phone or SD card"
                else scope.launch { pending = path to library.guessKind(path) }
            }
        },
    )

    pending?.let { (path, guess) ->
        KindDialog(
            name = path.trimEnd('/').substringAfterLast('/'),
            guess = guess,
            onDismiss = { pending = null },
            onConfirm = { kind ->
                library.addFolder(path, kind)
                pending = null
            },
        )
    }
}

@Composable
internal fun LibraryFoldersContent(
    folders: List<LibraryFolder>,
    scan: ScanState,
    feed: HomeFeed?,
    message: String?,
    canAdd: Boolean,
    onBack: () -> Unit,
    onToggle: (LibraryFolder) -> Unit,
    onRemove: (LibraryFolder) -> Unit,
    onAdd: () -> Unit,
) {
    val mode = LocalLayoutMode.current
    val detected = folders.filter { it.source == FolderSource.DETECTED }
    val added = folders.filter { it.source == FolderSource.ADDED }
    Box(Modifier.fillMaxSize()) {
        AmbientBackground(null, strength = 0.7f)
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Library folders", onBack, Modifier.padding(horizontal = mode.gutter))
            ScanBar(scan is ScanState.Scanning)
            if (scan is ScanState.Scanning) LibraryStatusLine(scan, feed, Modifier.padding(top = 4.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = listBottomPadding(mode)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (canAdd) item {
                    Column(Modifier.padding(horizontal = mode.gutter).entrance(0), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BoophisButton("Add folder", onAdd, Modifier.fillMaxWidth(), icon = Icons.Filled.Add)
                        if (message != null) {
                            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item { SectionTitle("Found automatically", Modifier.padding(horizontal = mode.gutter).padding(top = 8.dp).entrance(1)) }
                if (detected.isEmpty()) item {
                    Row(Modifier.padding(horizontal = mode.gutter), verticalAlignment = Alignment.CenterVertically) {
                        Frog(FrogArt.Mark, Modifier.size(72.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "No Movies, TV or Shows folders found",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                detected.forEachIndexed { i, f ->
                    item(key = "d${f.path}") { FolderRow(f, onToggle, onRemove, Modifier.entrance(2 + i)) }
                }
                item { SectionTitle("Added by you", Modifier.padding(horizontal = mode.gutter).padding(top = 8.dp).entrance(2)) }
                if (added.isEmpty()) item { Hint("Nothing added yet") }
                added.forEachIndexed { i, f ->
                    item(key = "a${f.path}") { FolderRow(f, onToggle, onRemove, Modifier.entrance(3 + i)) }
                }
                item {
                    Text(
                        "Boophis finds Movies, TV and Shows folders on your phone and SD card. Add any other folder here.",
                        Modifier.padding(horizontal = mode.gutter).padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, Modifier.padding(horizontal = LocalLayoutMode.current.gutter),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun FolderKind.icon() = if (this == FolderKind.MOVIES) Icons.Filled.Movie else Icons.Filled.Tv

private fun folderDetail(f: LibraryFolder): String {
    val n = f.itemCount
    return when {
        !f.available -> "Not available right now (SD card removed?)"
        !f.enabled -> "${f.volumeLabel} · Off"
        n == null -> "${f.volumeLabel} · not scanned yet"
        f.kind == FolderKind.MOVIES -> "${f.volumeLabel} · ${countLabel(n, "movie", "movies")}"
        else -> "${f.volumeLabel} · ${countLabel(n, "show", "shows")}"
    }
}

@Composable
private fun FolderRow(f: LibraryFolder, onToggle: (LibraryFolder) -> Unit, onRemove: (LibraryFolder) -> Unit, modifier: Modifier = Modifier) {
    val mode = LocalLayoutMode.current
    val dim = !f.available || !f.enabled
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.padding(horizontal = mode.gutter).fillMaxWidth()
            .background(BoophisColors.Surface.copy(alpha = 0.85f), shape)
            .border(1.dp, BoophisColors.Edge, shape)
            .heightIn(min = 72.dp)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).alpha(if (dim) 0.5f else 1f).background(BoophisColors.SurfaceHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(f.kind.icon(), null, tint = BoophisColors.Accent) }
        Column(Modifier.weight(1f).alpha(if (dim) 0.55f else 1f)) {
            Text(f.displayPath, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                folderDetail(f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (f.source == FolderSource.ADDED) {
            IconButton({ onRemove(f) }, Modifier.size(mode.target)) { Icon(Icons.Filled.Close, "Remove ${f.displayPath}") }
        } else {
            Box(Modifier.defaultMinSize(mode.target, mode.target), contentAlignment = Alignment.Center) {
                Switch(
                    f.enabled, { onToggle(f) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BoophisColors.OnAccent, checkedTrackColor = BoophisColors.Accent,
                    ),
                )
            }
        }
    }
}

/** "What's in <folder>?" with two big choices; the guess is preselected. */
@Composable
internal fun KindDialog(name: String, guess: FolderKind, onDismiss: () -> Unit, onConfirm: (FolderKind) -> Unit) {
    var kind by remember { mutableStateOf(guess) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BoophisColors.SurfaceHigh,
        title = { Text("What's in $name?", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (k in FolderKind.entries) {
                    val hint = if (k == guess) (if (k == FolderKind.SHOWS) " (looks like shows)" else " (looks like movies)") else ""
                    KindChoice((if (k == FolderKind.MOVIES) "Movies" else "Shows") + hint, k.icon(), kind == k) { kind = k }
                }
            }
        },
        confirmButton = { BoophisButton("Add folder", { onConfirm(kind) }) },
        dismissButton = { BoophisTextButton("Cancel", onDismiss) },
    )
}

@Composable
private fun KindChoice(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .background(if (selected) BoophisColors.Accent.copy(alpha = 0.16f) else BoophisColors.Surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) BoophisColors.Accent else BoophisColors.Edge, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (selected) BoophisColors.Accent else BoophisColors.TextSecondary)
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
