package com.sokoul.pc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sokoul.pc.theme.Corners
import com.sokoul.pc.theme.Glass
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.PanelType

/*
 * Sokoul's panel system, after Apple TV: one glass material, one header, one option row, one section title and one way
 * of opening. Player menus, side sheets, modals and settings all draw with these, so they read as one product.
 */

/** Distances shared by every panel: the container's inset plus an option's padding is the single text edge. */
object PanelMetrics {
    val Inset = 14.dp
    val RowPad = 26.dp
    val Edge = Inset + RowPad
    val RowHeight = 76.dp
    val RowHeightTwoLines = 100.dp
    const val FocusScale = 1.04f
    const val FocusMs = 140
    const val OpenMs = 220
}

private val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val OPEN_GUARD_MS = 350L

/**
 * A diffuse shadow drawn as a few widening rounded rectangles: the same result on Skia and Android, and nothing like the
 * offset rectangle a large elevation shadow leaves under translucent glass.
 */
fun Modifier.softShadow(corner: Dp, spread: Dp = 28.dp, strength: Float = 0.30f): Modifier = drawBehind {
    val steps = 8
    val reach = spread.toPx()
    val radius = corner.toPx()
    for (i in 1..steps) {
        val grow = reach * i / steps
        drawRoundRect(
            Color.Black.copy(alpha = strength / steps),
            topLeft = androidx.compose.ui.geometry.Offset(-grow, -grow * 0.6f),
            size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 1.8f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius + grow),
        )
    }
}

/** The glass itself: soft shadow, translucent base, a sheen along the top edge and a hairline rim. */
fun Modifier.glassSurface(shape: Shape = RoundedCornerShape(Corners.Panel), corner: Dp = Corners.Panel): Modifier = this
    .softShadow(corner)
    .clip(shape)
    .background(Glass.Base)
    .drawWithContent {
        drawRect(Brush.verticalGradient(0f to Glass.Sheen, 0.18f to Color.Transparent, endY = size.height.coerceAtMost(420f)))
        drawContent()
    }
    .border(1.dp, Glass.Rim, shape)

/**
 * Grows a focused row from its centre, but never by more than [PanelMetrics.Inset] on each side, so a wide row stays
 * inside its panel instead of being cut by the panel's edge.
 */
internal fun Modifier.boundedScale(scale: Float): Modifier = graphicsLayer {
    if (scale == 1f || size.width <= 0f) return@graphicsLayer
    val limit = 1f + (PanelMetrics.Inset.toPx() * 1.6f) / size.width
    val s = 1f + (scale - 1f).coerceAtMost(limit - 1f)
    scaleX = s
    scaleY = s
}

/** Where a surface comes from when it opens. */
enum class PanelEntry { Fade, FromEnd, FromStart, FromBelow }

/** A short fade and slide when the surface first appears; only the drawing layer moves, nothing recomposes per frame. */
fun Modifier.panelEnter(entry: PanelEntry = PanelEntry.Fade): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(PanelMetrics.OpenMs, easing = Ease)) }
    graphicsLayer {
        val p = progress.value
        alpha = p
        val shift = (1f - p) * 48f
        when (entry) {
            PanelEntry.Fade -> { scaleX = 0.97f + 0.03f * p; scaleY = scaleX }
            PanelEntry.FromEnd -> translationX = shift
            PanelEntry.FromStart -> translationX = -shift
            PanelEntry.FromBelow -> translationY = shift
        }
    }
}

/** Title and optional subtitle of a panel, on the panel's text edge. */
@Composable
fun PanelHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier.fillMaxWidth().padding(start = PanelMetrics.Edge, end = PanelMetrics.Edge, top = if (compact) 26.dp else 34.dp, bottom = if (compact) 10.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(title, if (compact) PanelType.Headline else PanelType.Title, Palette.OnBackground, maxLines = 2)
        subtitle?.takeIf { it.isNotBlank() }?.let { Label(it, PanelType.Callout, Palette.Secondary, maxLines = 2) }
    }
}

/** A group title inside a panel's list, on the same edge as the options. */
@Composable
fun PanelSection(text: String, modifier: Modifier = Modifier) {
    Label(text, PanelType.Section, Palette.Secondary, modifier.fillMaxWidth().padding(start = PanelMetrics.RowPad, end = PanelMetrics.RowPad, top = 20.dp, bottom = 6.dp), maxLines = 2)
}

/** Inline information or error inside a panel's list. */
@Composable
fun PanelNote(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Label(text, PanelType.Callout, if (error) Palette.Error else Palette.Secondary, modifier.fillMaxWidth().padding(horizontal = PanelMetrics.RowPad, vertical = 12.dp), maxLines = 6)
}

/**
 * The one option row of every panel: transparent at rest, white and slightly lifted when focused, a check mark when it
 * is the current choice, a value or a chevron on the right.
 */
@Composable
fun PanelOption(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    selected: Boolean = false,
    chevron: Boolean = false,
    destructive: Boolean = false,
    subtitleError: Boolean = false,
    requester: FocusRequester = remember { FocusRequester() },
    titleLines: Int = 1,
    /** Fill when not focused: transparent in panels, [Glass.RowRest] for settings lists. */
    restFill: Color = Color.Transparent,
    titleStyle: androidx.compose.ui.text.TextStyle = PanelType.Option,
    onFocused: (Boolean) -> Unit = {},
    leading: (@Composable (Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else restFill, tween(PanelMetrics.FocusMs), label = "option-fill")
    val scale by animateFloatAsState(if (focused) PanelMetrics.FocusScale else 1f, tween(PanelMetrics.FocusMs, easing = Ease), label = "option-scale")
    val ink = when {
        focused -> Palette.OnFocus
        destructive -> Palette.Error
        else -> Palette.OnBackground
    }
    val soft = when {
        focused -> Palette.OnFocus.copy(alpha = 0.62f)
        subtitleError -> Palette.Error
        else -> Palette.Secondary
    }
    val shape = RoundedCornerShape(Corners.Row)
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = if (subtitle.isNullOrBlank()) PanelMetrics.RowHeight else PanelMetrics.RowHeightTwoLines)
            .boundedScale(scale)
            .clip(shape).background(fill)
            .semantics(mergeDescendants = true) { this.selected = selected; role = Role.Button }
            .tvFocusable(onFocusChanged = { focused = it; onFocused(it) }, onClick = onClick, requester = requester)
            .padding(horizontal = PanelMetrics.RowPad, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        leading?.let { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { it(ink) } }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label(title, titleStyle, ink, maxLines = titleLines)
            subtitle?.takeIf { it.isNotBlank() }?.let { Label(it, PanelType.Callout, soft, maxLines = 2) }
        }
        value?.takeIf { it.isNotBlank() }?.let { Label(it, PanelType.Callout, soft, Modifier.widthIn(max = 320.dp), maxLines = 1) }
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(30.dp), tint = ink)
        else if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(34.dp), tint = soft)
    }
}

/**
 * A glass menu (player popover, side sheet, list panel): fixed header, then a list that scrolls under it. Callers place it
 * with [modifier] and keep focus inside it themselves.
 */
@Composable
fun GlassMenu(
    title: String?,
    modifier: Modifier = Modifier,
    width: Dp = 600.dp,
    maxHeight: Dp = 860.dp,
    subtitle: String? = null,
    entry: PanelEntry = PanelEntry.FromEnd,
    scroll: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // The OK that opened the menu can still arrive on the TV (key repeat, centre mapped to Enter on release): for a
    // short moment it must not choose the row that just took focus.
    val openedAt = remember { System.currentTimeMillis() }
    Column(
        modifier.width(width).heightIn(max = maxHeight)
            .onPreviewKeyEvent { e ->
                System.currentTimeMillis() - openedAt < OPEN_GUARD_MS &&
                    (e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter || e.key == androidx.compose.ui.input.key.Key.DirectionCenter)
            }
            .panelEnter(entry).glassSurface(),
    ) {
        if (title != null) PanelHeader(title, subtitle, compact = true)
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll)
                .padding(start = PanelMetrics.Inset, end = PanelMetrics.Inset, top = if (title == null) PanelMetrics.Inset else 0.dp, bottom = PanelMetrics.Inset),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/** A focusable row with free content (several lines, badges) that behaves exactly like [PanelOption]. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    requester: FocusRequester = remember { FocusRequester() },
    selected: Boolean = false,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color.Transparent, tween(PanelMetrics.FocusMs), label = "card-fill")
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, tween(PanelMetrics.FocusMs, easing = Ease), label = "card-scale")
    val shape = RoundedCornerShape(Corners.Row)
    Row(
        modifier.fillMaxWidth()
            .boundedScale(scale)
            .clip(shape).background(fill)
            .semantics(mergeDescendants = true) { this.selected = selected; role = Role.Button }
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester)
            .padding(horizontal = PanelMetrics.RowPad, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) { content(focused) }
}

/** A capsule filter or compact action: translucent at rest, brighter when chosen, white and lifted when focused. */
@Composable
fun PanelChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    requester: FocusRequester = remember { FocusRequester() },
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when { focused -> Palette.FocusFill; selected -> Color(0x52FFFFFF); else -> Color(0x1FFFFFFF) },
        tween(PanelMetrics.FocusMs), label = "chip-fill",
    )
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(PanelMetrics.FocusMs, easing = Ease), label = "chip-scale")
    val ink = if (focused) Palette.OnFocus else Palette.OnBackground
    Row(
        modifier.height(54.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50)).background(fill)
            .semantics(mergeDescendants = true) { this.selected = selected; role = Role.Button }
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(24.dp), tint = ink) }
        Label(label, PanelType.Callout.copy(fontWeight = if (selected || focused) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium), ink, maxLines = 1)
    }
}

/** A small capsule tag (language, quality, origin) used on rows and info lines. */
@Composable
fun PanelBadge(text: String, modifier: Modifier = Modifier, strong: Boolean = false, onFocus: Boolean = false) {
    val fill = when {
        onFocus -> Palette.OnFocus.copy(alpha = if (strong) 0.85f else 0.10f)
        strong -> Palette.OnBackground
        else -> Color(0x2EFFFFFF)
    }
    val ink = when {
        onFocus && strong -> Palette.FocusFill
        onFocus -> Palette.OnFocus
        strong -> Palette.OnFocus
        else -> Palette.OnBackground
    }
    Box(modifier.clip(RoundedCornerShape(Corners.Chip)).background(fill).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Label(text, PanelType.Caption, ink, maxLines = 1)
    }
}
