// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.boophis.model.LayoutMode

/**
 * Palette taken from the mascot, a translucent mint Boophis tree frog:
 * night canopy (background), glass mint (skin, primary), spot coral (its red spots, used only as a
 * signal), iris bronze + ring blue (its eye, the one signature motif), belly cream (text).
 */
internal class Palette(
    val night: Boolean,
    val background: Color, val backgroundLift: Color, val surface: Color, val surfaceHigh: Color,
    val accent: Color, val accent2: Color, val ring: Color, val iris: Color, val coral: Color,
    val onAccent: Color, val textPrimary: Color, val textSecondary: Color, val edge: Color,
)

/** Night canopy: the default, and always used by the player. */
internal val NightPalette = Palette(
    night = true,
    background = Color(0xFF06110F), backgroundLift = Color(0xFF0B1A18),
    surface = Color(0xFF0F1F1C), surfaceHigh = Color(0xFF18302C),
    accent = Color(0xFF7FE0C4), accent2 = Color(0xFF3FB89C),
    ring = Color(0xFF2F6BFF), iris = Color(0xFFE08A45), coral = Color(0xFFFF5B3A),
    onAccent = Color(0xFF032019),
    textPrimary = Color(0xFFEEF3E8), textSecondary = Color(0xFF9DB3AB), edge = Color(0x2E9FE8D2),
)

/**
 * Daylight canopy, for sunlit car screens and phones in light mode: pale leaf ground, deep-green
 * text, and the same eye and spots, darkened enough to hold contrast on light surfaces.
 */
internal val DayPalette = Palette(
    night = false,
    background = Color(0xFFE6F2EC), backgroundLift = Color(0xFFF2F9F5),
    surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFD9EBE3),
    accent = Color(0xFF178269), accent2 = Color(0xFF0F6E59),
    ring = Color(0xFF1E5BE6), iris = Color(0xFFC46A26), coral = Color(0xFFDE3F1E),
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0B2420), textSecondary = Color(0xFF4A645D), edge = Color(0x260B2420),
)

/**
 * Which palette is on screen. Snapshot state, so composables recompose and draw lambdas redraw when
 * it flips. Priority: the player is always night; otherwise the car's day/night signal while a car
 * session runs; otherwise the phone's own light/dark setting.
 */
object BoophisPalette {
    internal var carNight by mutableStateOf<Boolean?>(null)
    internal var phoneNight by mutableStateOf(true)

    /**
     * How many roots (phone, car) are showing the player. A count, not one flag, because the phone's
     * root stays alive behind a car session: with a single flag it overwrote the car's "in the
     * player" with its own "not in the player" and the car player turned day.
     */
    internal var playerRoots by mutableIntStateOf(0)

    /** Called by the app with the car's day/night state; null when no car session is running. */
    fun setCarNight(night: Boolean?) { carNight = night }

    internal val current: Palette
        get() = if (playerRoots > 0 || (carNight ?: phoneNight)) NightPalette else DayPalette
}

/**
 * Palette taken from the mascot, a translucent mint Boophis tree frog:
 * night canopy (background), glass mint (skin, primary), spot coral (its red spots, used only as a
 * signal), iris bronze + ring blue (its eye, the one signature motif), belly cream (text).
 * Every colour follows [BoophisPalette] (day or night).
 */
object BoophisColors {
    private val p get() = BoophisPalette.current
    val IsNight: Boolean get() = p.night
    /** Night canopy: green-black, never neutral grey-black. */
    val Background: Color get() = p.background
    val BackgroundLift: Color get() = p.backgroundLift
    val Surface: Color get() = p.surface
    val SurfaceHigh: Color get() = p.surfaceHigh
    /** Glass mint: primary actions, progress, focus. */
    val Accent: Color get() = p.accent
    /** Deeper wet-skin mint: second stop of the primary gradient. */
    val Accent2: Color get() = p.accent2
    /** Ring blue: the eye's outer ring (progress ring, completed steps). */
    val Teal: Color get() = p.ring
    val RingBlue: Color get() = p.ring
    /** Iris bronze: the eye's centre. */
    val Iris: Color get() = p.iris
    /** Spot coral: "new" and "unwatched" signals only. */
    val Coral: Color get() = p.coral
    val OnAccent: Color get() = p.onAccent
    /** Belly cream (night) / deep leaf (day). */
    val TextPrimary: Color get() = p.textPrimary
    val TextSecondary: Color get() = p.textSecondary
    /** Wet-glass highlight on card edges (a mint-tinted hairline, not grey). */
    val Edge: Color get() = p.edge

    val AccentBrush: Brush get() = Brush.linearGradient(listOf(Accent, Accent2))
    val AccentBrushH: Brush get() = Brush.horizontalGradient(listOf(Accent, Accent2))
}

val LocalLayoutMode = staticCompositionLocalOf { LayoutMode.Phone }

internal val LayoutMode.isCar: Boolean get() = this == LayoutMode.Car
internal val LayoutMode.target: Dp get() = if (isCar) 76.dp else 48.dp
internal val LayoutMode.posterWidth: Dp get() = if (isCar) 124.dp else 120.dp
internal val LayoutMode.wideWidth: Dp get() = if (isCar) 260.dp else 200.dp
internal val LayoutMode.gutter: Dp get() = if (isCar) 24.dp else 16.dp

/*
 * Type: Bricolage Grotesque for titles (organic, slightly irregular, like the frog), Atkinson
 * Hyperlegible Next for everything you read at a glance in the car. Both OFL, bundled.
 * Bricolage's optical-size axis is set per weight: heavier cuts are only used at large sizes.
 */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, opsz: Float) = Font(
    R.font.bricolage_grotesque, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz)),
)

@OptIn(ExperimentalTextApi::class)
private fun atkinson(weight: Int) = Font(
    R.font.atkinson_hyperlegible_next, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val DisplayFamily = FontFamily(bricolage(600, 28f), bricolage(700, 36f), bricolage(800, 60f))
private val TextFamily = FontFamily(atkinson(400), atkinson(500), atkinson(700), atkinson(800))

private fun Typography.boophis(): Typography {
    fun TextStyle.d(weight: Int, tracking: Float = 0f) =
        copy(fontFamily = DisplayFamily, fontWeight = FontWeight(weight), letterSpacing = tracking.sp)
    fun TextStyle.t(weight: Int, tracking: Float = 0f) =
        copy(fontFamily = TextFamily, fontWeight = FontWeight(weight), letterSpacing = tracking.sp)
    return copy(
        displaySmall = displaySmall.d(800, -0.6f),
        headlineMedium = headlineMedium.d(800, -0.5f),
        headlineSmall = headlineSmall.d(700, -0.3f),
        titleLarge = titleLarge.d(700, -0.2f),
        titleMedium = titleMedium.d(700, -0.1f),
        titleSmall = titleSmall.t(700),
        bodyLarge = bodyLarge.t(500),
        bodyMedium = bodyMedium.t(400),
        bodySmall = bodySmall.t(400),
        labelLarge = labelLarge.t(700, 0.1f),
        labelMedium = labelMedium.t(700),
        labelSmall = labelSmall.t(700),
    )
}

private fun carTypography(): Typography {
    val d = Typography()
    fun TextStyle.c(size: Int, line: Int = size + 8) = copy(fontSize = size.sp, lineHeight = line.sp)
    return d.copy(
        displaySmall = d.displaySmall.c(40, 48),
        headlineMedium = d.headlineMedium.c(36, 44),
        headlineSmall = d.headlineSmall.c(32, 40),
        titleLarge = d.titleLarge.c(32, 40),
        titleMedium = d.titleMedium.c(28, 36),
        titleSmall = d.titleSmall.c(24),
        bodyLarge = d.bodyLarge.c(24),
        bodyMedium = d.bodyMedium.c(24),
        bodySmall = d.bodySmall.c(24),
        labelLarge = d.labelLarge.c(24),
        labelMedium = d.labelMedium.c(24),
        labelSmall = d.labelSmall.c(24),
    )
}

@Composable
fun BoophisTheme(mode: LayoutMode, content: @Composable () -> Unit) {
    // The phone follows its own light/dark setting; the car's signal comes from the app (BoophisPalette).
    if (!mode.isCar) {
        val systemDark = isSystemInDarkTheme()
        SideEffect { BoophisPalette.phoneNight = systemDark }
    }
    val night = BoophisColors.IsNight
    val base = if (night) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = BoophisColors.Accent,
        onPrimary = BoophisColors.OnAccent,
        secondary = BoophisColors.Accent,
        background = BoophisColors.Background,
        onBackground = BoophisColors.TextPrimary,
        surface = BoophisColors.Surface,
        onSurface = BoophisColors.TextPrimary,
        surfaceVariant = BoophisColors.SurfaceHigh,
        onSurfaceVariant = BoophisColors.TextSecondary,
        surfaceContainer = BoophisColors.Surface,
        surfaceContainerHigh = BoophisColors.SurfaceHigh,
        secondaryContainer = BoophisColors.SurfaceHigh,
        onSecondaryContainer = BoophisColors.TextPrimary,
        error = if (night) Color(0xFFFF6B6B) else Color(0xFFB3261E),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = (if (mode.isCar) carTypography() else Typography()).boophis(),
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalLayoutMode provides mode,
            androidx.compose.material3.LocalContentColor provides BoophisColors.TextPrimary,
            content = content,
        )
    }
}

/** Bottom content padding for scrollers: nav-bar inset (phone) + 24dp. */
@Composable
internal fun listBottomPadding(mode: LayoutMode): Dp =
    if (mode.isCar) 24.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
