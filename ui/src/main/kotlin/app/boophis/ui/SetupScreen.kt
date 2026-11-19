// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Phone first-run checklist: 1) let Boophis see the videos, 2) make the install one Android Auto
 * accepts, 3) all set. Android's own security screens can't be tapped for the user, so each step says
 * exactly what they'll see. Shown until finished; never in car mode.
 */
@Stable
internal class SetupGate(private val prefs: SharedPreferences) {
    /** Set once the checklist has been shown with work left, so "All set" can be shown after it. */
    var started by mutableStateOf(prefs.getBoolean(K_STARTED, false))
        private set
    var finished by mutableStateOf(prefs.getBoolean(K_FINISHED, false))
        private set

    fun markStarted() {
        if (started) return
        started = true
        prefs.edit().putBoolean(K_STARTED, true).apply()
    }

    fun finish() {
        finished = true
        prefs.edit().putBoolean(K_FINISHED, true).apply()
    }

    fun visible(hasStorageAccess: Boolean, carNeeded: Boolean): Boolean =
        !hasStorageAccess || (!finished && (carNeeded || started))

    private companion object {
        const val K_STARTED = "setup_started"
        const val K_FINISHED = "setup_finished"
    }
}

@Composable
internal fun rememberSetupGate(): SetupGate {
    val ctx = LocalContext.current
    return remember { SetupGate(ctx.applicationContext.getSharedPreferences("boophis_setup", Context.MODE_PRIVATE)) }
}

private enum class StepState { Done, Active, Upcoming }

@Composable
internal fun SetupScreen(
    gate: SetupGate,
    hasStorageAccess: Boolean,
    onRequestStorageAccess: (() -> Unit)?,
    carReady: CarReadyPrompt?,
    library: app.boophis.model.MediaLibrary? = null,
    onChooseFolders: (() -> Unit)? = null,
) {
    val folders = if (hasStorageAccess && library != null) remember(library) { library.folders() }.collectOrNull().value else null
    val carNeeded = carReady?.needed?.collectAsState()?.value ?: false
    val busy = carReady?.busy?.collectAsState()?.value ?: false
    val ctx = LocalContext.current
    LaunchedEffect(hasStorageAccess, carNeeded) {
        if (!hasStorageAccess || carNeeded) gate.markStarted()
    }
    val s1 = if (hasStorageAccess) StepState.Done else StepState.Active
    val s2 = when {
        !carNeeded -> StepState.Done
        hasStorageAccess -> StepState.Active
        else -> StepState.Upcoming
    }
    val s3 = if (s1 == StepState.Done && s2 == StepState.Done) StepState.Active else StepState.Upcoming

    Box(Modifier.fillMaxSize()) {
        AmbientBackground(null, strength = 0.7f)
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Frog(FrogArt.Mark, Modifier.size(88.dp).entrance(0), react = LocalFrogFound.current)
            Text(
                if (s3 == StepState.Active) "You're all set" else "Get ready for the car",
                Modifier.entrance(1),
                style = MaterialTheme.typography.headlineMedium.copy(brush = BoophisColors.AccentBrushH),
                textAlign = TextAlign.Center,
            )
            Text(
                if (s3 == StepState.Active) "Boophis is ready for your phone and your car."
                else "Two steps. Each one shows what to tap on Android's screens.",
                Modifier.entrance(2),
                style = MaterialTheme.typography.bodyLarge,
                color = BoophisColors.TextPrimary.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))

            Step(
                1, "Let Boophis find your videos", s1, Icons.Filled.FolderOpen, Modifier.entrance(3),
                summary = folders?.let(::foldersSummary),
                body = "Boophis looks in the Movies and Shows folders on your phone and SD card.",
                // Samsung opens the whole "All files access" list rather than Boophis's own switch.
                hints = listOf("Android opens \"All files access\": find Boophis in the list, switch it on, press Back."),
                button = onRequestStorageAccess?.let { "Allow access" to it },
            )
            Step(
                2, "Make Boophis car-ready", s2, Icons.Filled.DirectionsCar, Modifier.entrance(4),
                body = "Android Auto only shows apps installed the Play way. Boophis reinstalls itself " +
                    "like that. Your videos and resume points stay.",
                hints = listOf(
                    "If Android says it isn't allowed: tap Settings, find Boophis in the list, switch it on, press Back.",
                    "Tap Update.",
                    // Verified: Play Protect's scan passes ("No harmful apps found").
                    "If Play Protect asks: tap Scan app and wait a moment.",
                    "Tap Open to come back here.",
                ),
                button = carReady?.let { (if (busy) "Preparing…" else "Make car-ready") to { if (!busy) it.start() } },
                secondary = "Skip, phone only" to { CarCardPrefs.dismiss(ctx); gate.finish() },
            )
            Step(
                3, "Plug in your car", s3, Icons.Filled.PlayArrow, Modifier.entrance(5),
                body = "Connect your phone to Android Auto and tap Boophis on the car screen. " +
                    "It picks up where you left off.",
                note = "For passengers and parked use only. Never watch while driving. You're responsible for following local laws.",
                button = "Start watching" to gate::finish,
                secondary = onChooseFolders?.let { "Choose folders" to it },
            )
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    state: StepState,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    body: String,
    hints: List<String> = emptyList(),
    button: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    summary: String? = null,
    note: String? = null,
) {
    val active = state == StepState.Active
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().widthIn(max = 560.dp)
            .alpha(if (state == StepState.Upcoming) 0.45f else 1f)
            .background(
                if (active) Brush.verticalGradient(
                    listOf(BoophisColors.Accent.copy(alpha = 0.14f), BoophisColors.SurfaceHigh.copy(alpha = 0.9f)),
                ) else Brush.verticalGradient(listOf(BoophisColors.Surface.copy(alpha = 0.8f), BoophisColors.Surface.copy(alpha = 0.8f))),
                shape,
            )
            .border(1.dp, if (active) BoophisColors.Accent.copy(alpha = 0.45f) else BoophisColors.Edge, shape)
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).background(
                    when (state) {
                        StepState.Done -> Brush.linearGradient(listOf(BoophisColors.Teal, BoophisColors.Teal))
                        StepState.Active -> BoophisColors.AccentBrush
                        StepState.Upcoming -> Brush.linearGradient(listOf(BoophisColors.SurfaceHigh, BoophisColors.SurfaceHigh))
                    },
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (state == StepState.Done) Icon(Icons.Filled.Check, "Done", tint = Color.Black)
                else Text("$number", style = MaterialTheme.typography.titleMedium, color = if (active) Color.Black else BoophisColors.TextPrimary)
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(icon, null, tint = if (active) BoophisColors.Accent else BoophisColors.TextPrimary.copy(alpha = 0.5f))
        }
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = BoophisColors.TextPrimary.copy(alpha = 0.8f))
        if (!active) return@Column
        Text(body, style = MaterialTheme.typography.bodyMedium, color = BoophisColors.TextPrimary.copy(alpha = 0.8f))
        if (note != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Warning, null, Modifier.size(18.dp).padding(top = 2.dp), tint = BoophisColors.Accent2)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = BoophisColors.TextPrimary.copy(alpha = 0.7f))
        }
        if (hints.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "What you'll see",
                    style = MaterialTheme.typography.labelMedium.copy(brush = BoophisColors.AccentBrushH, fontWeight = FontWeight.Black),
                )
                hints.forEachIndexed { i, h ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (hints.size > 1) "${i + 1}." else "•", style = MaterialTheme.typography.bodyMedium, color = BoophisColors.Accent)
                        Text(h, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            button?.let { (label, onClick) -> BoophisButton(label, onClick) }
            secondary?.let { (label, onClick) -> BoophisTextButton(label, onClick) }
        }
    }
}
