package com.sokoul.pc.live

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Reference pixels under ScaledRoot. The Apple TV dark palette: black page, system greys for cells (no navy). */
internal object FormulerGuideStyle {
    val header = Color(0xFF000000)
    val background = Color(0xFF000000)
    val surface = Color(0xFF1C1C1E)
    val current = Color(0xFF2C2C2E)
    val selected = Color(0xFF3A3A3C)
    val ink = Color(0xFFF3F3F4)
    val secondary = Color(0xFFC2C2C7)
    val muted = Color(0xFFADADB5)
    val line = Color(0x33FFFFFF)
    val live = Color(0xFFFF6961)
    /** Behind a panel opened over the guide: the guide stays as faint context, never competing text. */
    val scrim = Color(0xD9000000)
    /** The group column sliding over the guide. */
    val sheet = Color(0xFF1C1C1E)
    val headerHeight = 322.dp
    const val rowHeight = 80f
    const val focusMs = 110
    const val panelMs = 140
}
