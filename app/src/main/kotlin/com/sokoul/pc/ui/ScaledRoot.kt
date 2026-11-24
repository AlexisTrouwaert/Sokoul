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

/**
 * A tablet held upright counts this many dp across on its browse pages: a body text of 29 then reads about 41 px on a
 * 1600 px wide screen and a poster row shows a little over four posters, as the Apple TV app does there.
 */
const val UPRIGHT_WIDTH = 1120f

/** The density of a browse page on a tablet held upright (see [UPRIGHT_WIDTH]). */
fun uprightDensity(widthPx: Int): Density = Density(density = widthPx / UPRIGHT_WIDTH, fontScale = 1f)

/** Makes one dp equal one pixel of a 1920-wide capture, whatever the window size. */
fun scaledDensity(widthPx: Int): Density = Density(density = widthPx / REFERENCE_WIDTH, fontScale = 1f)

/** A phone's density: its short side is [PHONE_SHORT_SIDE] dp. */
fun phoneDensity(widthPx: Int, heightPx: Int): Density = Density(density = minOf(widthPx, heightPx) / PHONE_SHORT_SIDE, fontScale = 1f)

@Composable
fun ScaledRoot(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A tablet held upright takes its own scale everywhere, not page by page: drawn as a 1920 frame squeezed into
        // 1600 px, the bar, TV, Sport and every page not adapted read 17 % smaller than on the TV (2026-11-23).
        val density = when {
            Form.isPhone -> phoneDensity(constraints.maxWidth, constraints.maxHeight)
            Form.tabletPortrait -> uprightDensity(constraints.maxWidth)
            else -> scaledDensity(constraints.maxWidth)
        }
        // Arabic reads right to left: rows, menus and text alignment follow the language.
        val direction = if (com.sokoul.pc.i18n.InterfaceLanguages.current().rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
        CompositionLocalProvider(LocalDensity provides density, androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
            content()
        }
    }
}
