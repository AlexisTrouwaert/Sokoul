package com.sokoul.pc.live

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Reference pixels under ScaledRoot. These tokens belong to the TV guide only. */
internal object FormulerGuideStyle {
    val background = Color(0xFF252527)
    val surface = Color(0xFF303033)
    val current = Color(0xFF3C3C40)
    val selected = Color(0xFF48484E)
    val ink = Color(0xFFF3F3F4)
    val secondary = Color(0xFFC2C2C7)
    val muted = Color(0xFFADADB5)
    val line = Color(0xFF55555C)
    val live = Color(0xFFFF6961)
    /** Behind a panel opened over the guide: the guide stays as faint context, never competing text. */
    val scrim = Color(0xD9000000)
    /** The group column sliding over the guide. */
    val sheet = Color(0xFF1C1C1E)
    val headerHeight = 232.dp
    const val rowHeight = 80f
    const val focusMs = 110
    const val panelMs = 140
}
