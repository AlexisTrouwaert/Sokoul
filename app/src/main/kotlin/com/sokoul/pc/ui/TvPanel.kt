package com.sokoul.pc.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sokoul.pc.theme.Corners
import com.sokoul.pc.theme.PanelType

/**
 * The two frames panels of the TV pages use, so opening one never changes the window's shape: [Standard] for actions,
 * settings and forms, [Wide] for the two-column search with its keyboard. Lists that belong to the guide slide in from
 * the side instead (the group column).
 */
enum class TvPanelSize(val width: Dp, val height: Dp) { Standard(920.dp, 860.dp), Wide(1540.dp, 880.dp) }

/**
 * A modal glass panel (see Panels.kt): header on the single text edge, a body the caller fills and scrolls. Back always
 * closes, so there is no footer repeating it; [footer] is kept for callers but not drawn.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvPanel(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    width: Dp = 720.dp,
    height: Dp = 820.dp,
    @Suppress("UNUSED_PARAMETER") footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tv = com.sokoul.pc.Platform.IS_TV
    // A panel opened while another surface releases focus can lose its own first request; then the remote drives
    // whatever is behind (the navigation bar). Until something inside holds focus, keep asking for it.
    val inside = remember { FocusRequester() }
    var holds by remember { mutableStateOf(false) }
    if (tv) androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(8) {
            kotlinx.coroutines.delay(90)
            if (holds) return@LaunchedEffect
            runCatching { inside.requestFocus() }
        }
    }
    Column(
        // Up to [height]: short action lists close around their rows instead of leaving an empty glass slab.
        modifier.width(width).heightIn(max = height)
            .panelEnter()
            .onFocusChanged { holds = it.hasFocus }
            .focusRequester(inside)
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
            .glassSurface(RoundedCornerShape(Corners.Modal), Corners.Modal),
    ) {
        PanelHeader(title, subtitle)
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().padding(start = PanelMetrics.Inset, end = PanelMetrics.Inset, bottom = PanelMetrics.Inset),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/** A full-width option with stable geometry for both pointer and remote focus (the shared [PanelOption]). */
@Composable
fun TvActionRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    requester: FocusRequester = remember { FocusRequester() },
    destructive: Boolean = false,
    trailing: String? = null,
    /** Artwork inside the focus pill (a channel logo), so lists keep one edge. */
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val lead: (@Composable (androidx.compose.ui.graphics.Color) -> Unit)? = leading?.let { art -> @Composable { _: androidx.compose.ui.graphics.Color -> art() } }
    PanelOption(
        title, modifier, subtitle = subtitle, value = trailing, selected = selected, destructive = destructive,
        requester = requester, titleLines = 2, leading = lead, onClick = onClick,
    )
}

/** Section titles stay on the same text axis as options and the panel header. */
@Composable
fun TvSectionLabel(text: String, modifier: Modifier = Modifier) = PanelSection(text, modifier)

/** Inline feedback without another floating card or a second focus surface. */
@Composable
fun TvPanelNotice(text: String, modifier: Modifier = Modifier, error: Boolean = false) = PanelNote(text, modifier, error)
