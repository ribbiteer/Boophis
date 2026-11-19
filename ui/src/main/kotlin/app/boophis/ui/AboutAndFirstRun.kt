// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.boophis.model.LayoutMode
import app.boophis.model.ScanState

private val licenses = listOf(
    "AndroidX, Jetpack Compose, Media3, Room, Car App Library" to "Apache-2.0",
    "Coil" to "Apache-2.0",
    "Jellyfin FFmpeg decoder" to "GPL-3.0",
    "Fermata (input-bridge approach)" to "GPL-3.0",
    "Bricolage Grotesque, Atkinson Hyperlegible Next (fonts)" to "OFL-1.1",
)

@Composable
internal fun AboutScreen(actions: Actions, version: String) {
    val mode = LocalLayoutMode.current
    val scan by actions.deps.library.scanState.collectAsState()
    Box(Modifier.fillMaxSize()) {
    AmbientBackground(null, strength = 0.7f)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = listBottomPadding(mode)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("About", { actions.nav.back() }, Modifier.padding(horizontal = mode.gutter)) }
        item {
            Column(Modifier.padding(horizontal = mode.gutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Frog(FrogArt.Mark, Modifier.size(if (mode.isCar) 120.dp else 96.dp).entrance(0))
                Spacer(Modifier.height(4.dp))
                Text(
                    "Boophis", Modifier.entrance(1),
                    style = MaterialTheme.typography.headlineMedium.copy(brush = BoophisColors.AccentBrushH),
                )
                Text(
                    "Your local videos, on your phone and in your car. No accounts, no network.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Version $version", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "GPL-3.0-or-later", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Phone only, while not car-ready: the Home card can be dismissed, so the option lives here too.
        item { CarReadyCard(actions.deps.carReady, dismissible = false) }
        item {
            Column(Modifier.padding(horizontal = mode.gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Safety")
                Text(
                    "Boophis is for passengers and for use while parked. Don't watch or operate it while driving; in many places it's illegal for video to be visible to the driver of a moving vehicle. You're responsible for using it safely and legally. Boophis is provided as is, without any warranty (GPL-3.0, sections 15–16).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionTitle("Licenses", Modifier.padding(horizontal = mode.gutter))
        }
        licenses.forEach { (name, lic) ->
            item {
                Column(Modifier.padding(horizontal = mode.gutter)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(lic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = mode.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!mode.isCar) BoophisButton(
                    "Library folders", { actions.nav.open(app.boophis.model.Route.LibraryFolders) },
                    primary = false, icon = Icons.Filled.FolderOpen,
                )
                BoophisButton(
                    if (scan is ScanState.Scanning) "Updating…" else "Refresh library",
                    actions::rescan, primary = false, icon = Icons.Filled.Refresh,
                )
            }
        }
    }
    }
}

@Composable
internal fun FirstRunScreen(mode: LayoutMode, onRequestStorageAccess: (() -> Unit)?) {
    if (mode.isCar || onRequestStorageAccess == null) {
        EmptyStateLayout(
            title = "Finish setup on your phone",
            body = "Open Boophis on your phone once to allow access to your videos.\nFor passengers and parked use only.",
        )
    } else {
        EmptyStateLayout(
            title = "Let Boophis find your videos",
            body = "Boophis looks in the Movies, TV and Shows folders on your phone and SD card and reads " +
                "Jellyfin artwork and NFO files. Android calls this \"All files access\".",
        ) {
            BoophisButton("Allow access", onRequestStorageAccess)
        }
    }
}
