// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.boophis.model.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.io.File
import kotlin.math.absoluteValue

@Composable
internal fun <T> Flow<T>.collectOrNull(): State<T?> = collectAsState(initial = null)

internal val CardShape = RoundedCornerShape(14.dp)

/** Local image with a gradient + title placeholder when there is no art. Real art fades in. */
@Composable
fun ArtImage(
    path: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showTitle: Boolean = true,
) {
    val hue = remember(title) { (title.hashCode().absoluteValue % 360).toFloat() }
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(
                    Color.hsv(hue, 0.55f, 0.42f),
                    Color.hsv((hue + 35f) % 360f, 0.6f, 0.2f),
                    Color.hsv((hue + 70f) % 360f, 0.5f, 0.09f),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (path == null) {
            if (showTitle) {
                Text(
                    title,
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            val ctx = LocalContext.current
            val request = remember(path) { ImageRequest.Builder(ctx).data(File(path)).crossfade(350).build() }
            AsyncImage(request, null, Modifier.matchParentSize(), contentScale = contentScale)
        }
    }
}

/**
 * Primary = glass-mint gradient pill with a soft glow; secondary = wet-glass panel.
 * [emphasized] adds a glow ring that breathes three times, then holds (the auto-focused Resume button).
 */
@Composable
internal fun BoophisButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    icon: ImageVector? = null,
    emphasized: Boolean = false,
    /** Optional second line (e.g. "S01E02 · Late Arrival"); keeps long labels readable on narrow car panes. */
    subtitle: String? = null,
    /** Replaces [icon] with custom leading content (the hero's eye badge). */
    leading: (@Composable () -> Unit)? = null,
) {
    val mode = LocalLayoutMode.current
    val shape = RoundedCornerShape(if (mode.isCar) 20.dp else 16.dp)
    val source = remember { MutableInteractionSource() }
    val hostPressed = source.collectIsPressedAsState()
    val content = if (primary) BoophisColors.OnAccent else BoophisColors.TextPrimary

    // Three breaths to draw the eye to Resume, then a steady ring: endless pulsing would keep pulling a
    // driver's eye (and spends H.264 bitrate in the car) long after it has done its job.
    val breathe = if (emphasized) {
        val a = remember { Animatable(0.35f) }
        LaunchedEffect(Unit) {
            repeat(3) {
                a.animateTo(1f, tween(1100))
                a.animateTo(0.35f, tween(1100))
            }
            a.animateTo(1f, tween(600))
        }
        a.asState()
    } else null

    Box(
        modifier
            .pressScale(source)
            .then(
                if (primary) Modifier.shadow(
                    if (mode.isCar) 18.dp else 14.dp, shape,
                    ambientColor = BoophisColors.Accent2, spotColor = BoophisColors.Accent,
                ) else Modifier,
            )
            .then(
                if (breathe != null) Modifier.drawWithContent {
                    drawContent()
                    val a = breathe.value
                    // Breathing ring in the eye's blue.
                    drawRoundRect(
                        BoophisColors.RingBlue.copy(alpha = a),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius((if (mode.isCar) 20.dp else 16.dp).toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
                    )
                } else Modifier,
            )
            .clip(shape)
            .then(
                if (primary) Modifier.background(BoophisColors.AccentBrushH)
                else Modifier.background(BoophisColors.SurfaceHigh.copy(alpha = 0.78f)).border(1.dp, BoophisColors.Edge, shape),
            )
            .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick)
            .heightIn(min = if (mode.isCar) 76.dp else 52.dp)
            .padding(horizontal = if (mode.isCar) 28.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content, LocalHostPressed provides hostPressed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(12.dp))
                } else if (icon != null) {
                    Icon(icon, null, Modifier.size(if (mode.isCar) 36.dp else 24.dp), tint = content)
                    Spacer(Modifier.width(10.dp))
                }
                val label = MaterialTheme.typography.labelLarge
                if (subtitle == null) {
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label, color = content)
                } else {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = label, color = content)
                        Text(
                            subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = label.copy(fontWeight = FontWeight.Normal), color = content.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoophisTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val mode = LocalLayoutMode.current
    val source = remember { MutableInteractionSource() }
    TextButton(
        onClick, modifier.heightIn(min = mode.target).pressScale(source),
        interactionSource = source,
        colors = ButtonDefaults.textButtonColors(contentColor = BoophisColors.Accent),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(if (mode.isCar) 32.dp else 20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val mode = LocalLayoutMode.current
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick,
        modifier.size(mode.target).pressScale(source, 0.88f).clip(CircleShape)
            // Raised glass in the palette (like the hero's info button): dark at night, pale leaf by day.
            // A fixed black disc turned into a muddy grey blob on the day palette.
            .background(BoophisColors.SurfaceHigh.copy(alpha = 0.78f)).border(1.dp, BoophisColors.Edge, CircleShape),
        interactionSource = source,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(if (mode.isCar) 40.dp else 26.dp),
            tint = BoophisColors.TextPrimary,
        )
    }
}

/** Title bar for non-Home list screens: back button + title. No own padding; row height = target. */
@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val mode = LocalLayoutMode.current
    Row(
        modifier.fillMaxWidth().heightIn(min = mode.target),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackButton(onBack)
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Card press feedback: springy squash plus an accent glow ring + tint held for a minimum time
 * (synthetic car taps are very short).
 */
@Composable
internal fun Modifier.cardClickable(onClick: () -> Unit): Modifier {
    val car = LocalLayoutMode.current.isCar
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) shown = true else { delay(180); shown = false }
    }
    val tint = BoophisColors.Accent.copy(alpha = if (car) 0.28f else 0.18f)
    return this
        .pressScale(source, 0.95f)
        .clip(CardShape)
        .drawWithContent {
            drawContent()
            if (shown) {
                drawRect(tint)
                drawRoundRect(
                    BoophisColors.AccentBrush,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
                )
            }
        }
        .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
}

/** Artwork frame: soft drop shadow + hairline top-lit edge. */
internal fun Modifier.artFrame(shape: androidx.compose.ui.graphics.Shape = CardShape): Modifier = this
    .shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)), shape)

@Composable
internal fun PosterCard(item: LibraryItem, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.cardClickable(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).artFrame()) {
            ArtImage(item.artwork.poster, item.title, Modifier.fillMaxSize())
            if (item is Show && item.unwatchedCount > 0) {
                Badge(item.unwatchedCount.toString(), Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Text(
            item.title,
            Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle, Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Small count badge in spot coral (unwatched episodes): the only place coral signals. */
@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier) {
    val car = LocalLayoutMode.current.isCar
    Box(
        modifier.heightIn(min = if (car) 34.dp else 22.dp).widthIn(min = if (car) 34.dp else 22.dp)
            .shadow(6.dp, CircleShape, spotColor = BoophisColors.Coral)
            .background(BoophisColors.Coral, RoundedCornerShape(50))
            .padding(horizontal = if (car) 10.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
        )
    }
}

/** Rounded gradient progress bar on a dark track. */
@Composable
internal fun GlowProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Color.Black.copy(alpha = 0.55f))) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                .background(BoophisColors.AccentBrushH, RoundedCornerShape(topEnd = height, bottomEnd = height)),
        )
    }
}

@Composable
internal fun WideCard(
    imagePath: String?,
    imageTitle: String,
    title: String,
    subtitle: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val car = LocalLayoutMode.current.isCar
    Column(modifier.cardClickable(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).artFrame()) {
            ArtImage(imagePath, imageTitle, Modifier.fillMaxSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)),
                ),
            )
            // The eye: its blue ring is the watch progress, so no separate bar is needed.
            EyeBadge(
                progress ?: 0f,
                Modifier.align(Alignment.BottomStart).padding(10.dp).size(if (car) 52.dp else 38.dp)
                    .shadow(10.dp, CircleShape, spotColor = BoophisColors.RingBlue),
            )
        }
        Text(
            title,
            Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SeeAllTile(width: Dp, height: Dp, onClick: () -> Unit) {
    Column(
        Modifier.width(width).height(height).cardClickable(onClick)
            .background(BoophisColors.SurfaceHigh.copy(alpha = 0.6f))
            .border(1.5.dp, BoophisColors.AccentBrush, CardShape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).background(BoophisColors.AccentBrush, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = BoophisColors.OnAccent, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("See all", style = MaterialTheme.typography.labelLarge, color = BoophisColors.Accent)
    }
}

/** Section title led by one coral skin spot. */
@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    val car = LocalLayoutMode.current.isCar
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SpotMark(Modifier.size(if (car) 14.dp else 10.dp))
        Spacer(Modifier.width(if (car) 14.dp else 10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** Titled horizontal row. Must be placed INSIDE a vertical scroller (car gesture rule). */
@Composable
internal fun MediaRow(title: String, index: Int = 0, content: LazyListScope.() -> Unit) {
    val mode = LocalLayoutMode.current
    Column(Modifier.entrance(index)) {
        SectionTitle(title, Modifier.padding(horizontal = mode.gutter).padding(bottom = 10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = mode.gutter, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (mode.isCar) 20.dp else 14.dp),
            content = content,
        )
    }
}

/** Centred empty/first-run layout: illustration left in car, on top on phone. */
@Composable
internal fun EmptyStateLayout(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    image: Boolean = true,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val mode = LocalLayoutMode.current
    val text: @Composable ColumnScope.(TextAlign) -> Unit = { align ->
        Text(
            title, style = MaterialTheme.typography.headlineSmall.copy(brush = BoophisColors.AccentBrushH),
            textAlign = align, modifier = Modifier.entrance(1),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align, modifier = Modifier.entrance(2),
        )
        Spacer(Modifier.height(20.dp))
        Column(Modifier.entrance(3)) { actions() }
    }
    ProvideEntrance {
        Box(modifier.fillMaxSize()) {
            Aurora(Modifier.matchParentSize())
            if (mode.isCar) {
                Row(
                    Modifier.fillMaxSize().padding(mode.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                ) {
                    if (image) {
                        Frog(FrogArt.Empty, Modifier.weight(0.4f).heightIn(max = 240.dp).aspectRatio(FrogArt.Empty.aspect).entrance(0))
                    }
                    Column(Modifier.weight(0.6f), horizontalAlignment = Alignment.Start) { text(TextAlign.Start) }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (image) {
                        Frog(FrogArt.Empty, Modifier.fillMaxWidth(0.8f).heightIn(max = 280.dp).aspectRatio(FrogArt.Empty.aspect).entrance(0))
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { text(TextAlign.Center) }
                }
            }
        }
    }
}

/** Backdrop (slow Ken Burns drift) with a gradient fading into the background, for detail headers. */
@Composable
internal fun BackdropBox(
    backdrop: String?,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Box(Modifier.matchParentSize().kenBurns(title.hashCode())) {
            ArtImage(backdrop, title, Modifier.fillMaxSize(), showTitle = false)
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    listOf(
                        BoophisColors.Background.copy(alpha = 0.25f),
                        BoophisColors.Background.copy(alpha = 0.75f),
                        BoophisColors.Background,
                    ),
                ),
            ),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    listOf(BoophisColors.Background.copy(alpha = 0.7f), Color.Transparent),
                ),
            ),
        )
        content()
    }
}
