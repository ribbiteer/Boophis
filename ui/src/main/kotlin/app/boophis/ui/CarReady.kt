// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Phone-only Home card shown while Boophis's install isn't one Android Auto accepts. One tap
 * reinstalls Boophis through the stock installer (Android's own dialogs), then the card is gone.
 */
/**
 * "Not now" on the Home card, or "Skip, phone only" in setup, hides the card for good. The About
 * screen keeps a non-dismissible copy, so the option is never lost.
 */
internal object CarCardPrefs {
    private const val KEY = "car_card_dismissed"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("boophis_setup", Context.MODE_PRIVATE)
    fun dismissed(ctx: Context) = prefs(ctx).getBoolean(KEY, false)
    fun dismiss(ctx: Context) = prefs(ctx).edit().putBoolean(KEY, true).apply()
}

@Composable
internal fun CarReadyCard(prompt: CarReadyPrompt?, modifier: Modifier = Modifier, dismissible: Boolean = true) {
    if (prompt == null || LocalLayoutMode.current.isCar) return
    val needed by prompt.needed.collectAsState()
    val busy by prompt.busy.collectAsState()
    val ctx = LocalContext.current
    var dismissed by remember { mutableStateOf(dismissible && CarCardPrefs.dismissed(ctx)) }
    if (!needed || dismissed) return
    val mode = LocalLayoutMode.current
    Row(
        modifier.padding(horizontal = mode.gutter).fillMaxWidth()
            .clip(CardShape)
            .background(
                Brush.horizontalGradient(
                    listOf(BoophisColors.Accent.copy(alpha = 0.16f), BoophisColors.SurfaceHigh.copy(alpha = 0.85f)),
                ),
            )
            .border(1.dp, BoophisColors.Accent.copy(alpha = 0.35f), CardShape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BoophisMark(56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Make Boophis car-ready", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android Auto only lists apps installed the Play way. One tap reinstalls Boophis like " +
                    "that. Your library and resume points stay.",
                style = MaterialTheme.typography.bodyMedium,
                color = BoophisColors.TextPrimary.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BoophisButton(
                    if (busy) "Preparing…" else "Make car-ready",
                    { if (!busy) prompt.start() },
                    icon = Icons.Filled.DirectionsCar,
                )
                if (dismissible) BoophisTextButton("Not now", { CarCardPrefs.dismiss(ctx); dismissed = true })
            }
        }
    }
}
