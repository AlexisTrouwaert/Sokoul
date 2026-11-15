package com.sokoul.pc.playback.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sokoul.pc.theme.Palette

/*
 * Live drawers, ported from the Android app (ui/screens/tv/TvDrawers.kt, EdgeDrawer and DrawerWidths): stuck to the
 * screen edge over its whole height, they fade into the picture instead of floating as a bubble.
 */

/** Full-height drawer on the left ([start]) or the right; [fade] blends its inner edge into the picture. */
@Composable
fun EdgeDrawer(start: Boolean, width: Dp, modifier: Modifier = Modifier, fade: Boolean = true, onSideKey: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    // It slides in from its edge while it fades in, instead of appearing at once: the remote's Left or Right is felt as
    // a move. Left or Right again, toward the picture or the edge, puts it away (onSideKey).
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.tween(DRAWER_IN_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { width.toPx() }
    val solid = com.sokoul.pc.theme.Glass.Base.copy(alpha = 0.94f)
    val stops = if (!fade) {
        arrayOf(0f to solid, 1f to solid)
    } else if (start) {
        arrayOf(0f to solid, 0.80f to com.sokoul.pc.theme.Glass.Base.copy(alpha = 0.86f), 1f to Color.Transparent)
    } else {
        arrayOf(0f to Color.Transparent, 0.20f to com.sokoul.pc.theme.Glass.Base.copy(alpha = 0.86f), 1f to solid)
    }
    Column(
        modifier
            .graphicsLayer {
                alpha = enter.value
                translationX = (1f - enter.value) * widthPx * (if (start) -DRAWER_SLIDE else DRAWER_SLIDE)
            }
            .let { m ->
                if (onSideKey == null) m else m.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                        onSideKey()
                        true
                    } else {
                        false
                    }
                }
            }
            .fillMaxHeight().width(width).background(Brush.horizontalGradient(*stops))
            .padding(start = if (start) 0.dp else FADE_PAD, end = if (start && fade) FADE_PAD else 0.dp, top = TOP_PAD, bottom = BOTTOM_PAD),
        content = content,
    )
}

/**
 * Widths of the live drawers for a screen [widthDp] wide. Android's formula, in this app's units (one dp is one pixel
 * of a 1920-wide screen, 1.5 times a landscape tablet's dp), and never half the screen or more: the picture stays seen.
 */
data class DrawerWidths(val channels: Int, val programs: Int) {
    companion object {
        private const val TABLET_SCALE = 1.5f
        private const val MAX_SHARE = 0.48f

        fun of(widthDp: Int): DrawerWidths {
            val room = widthDp - 24
            val cap = (widthDp * MAX_SHARE).toInt()
            return DrawerWidths(
                channels = minOf((460 * TABLET_SCALE).toInt(), room, cap),
                programs = minOf((480 * TABLET_SCALE).toInt(), room, cap),
            )
        }
    }
}

private val FADE_PAD = 42.dp
private val TOP_PAD = 54.dp
private val BOTTOM_PAD = 33.dp

private const val DRAWER_IN_MS = 240
private const val DRAWER_SLIDE = 0.18f
