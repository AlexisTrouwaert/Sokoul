package com.sokoul.pc.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

const val REFERENCE_WIDTH = 1920f

/**
 * On a phone, the short side of the screen counts this many dp, whatever the orientation: the TV's text sizes (22 to 46)
 * then read like a phone's own (about 14 to 28 sp) and a rotation keeps every size.
 */
const val PHONE_SHORT_SIDE = 640f

/** Makes one dp equal one pixel of a 1920-wide capture, whatever the window size. */
fun scaledDensity(widthPx: Int): Density = Density(density = widthPx / REFERENCE_WIDTH, fontScale = 1f)

/** A phone's density: its short side is [PHONE_SHORT_SIDE] dp. */
fun phoneDensity(widthPx: Int, heightPx: Int): Density = Density(density = minOf(widthPx, heightPx) / PHONE_SHORT_SIDE, fontScale = 1f)

@Composable
fun ScaledRoot(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = if (Form.isPhone) phoneDensity(constraints.maxWidth, constraints.maxHeight) else scaledDensity(constraints.maxWidth)
        CompositionLocalProvider(LocalDensity provides density) {
            content()
        }
    }
}
