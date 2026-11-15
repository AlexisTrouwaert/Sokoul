package com.sokoul.pc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sokoul.pc.playback.ui.DrawerWidths
import com.sokoul.pc.playback.ui.EdgeDrawer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live drawers keep the picture in sight: stuck to the edge, full height, under half the screen (spec pc-live-tv). */
@OptIn(ExperimentalTestApi::class)
class LiveDrawersTest {
    @Test
    fun drawers_stay_under_half_the_screen() {
        for (width in listOf(1280, 1920, 3840)) {
            val w = DrawerWidths.of(width)
            assertTrue("channels at $width", w.channels * 2 < width)
            assertTrue("programs at $width", w.programs * 2 < width)
        }
    }

    @Test
    fun the_reference_screen_gets_the_tablet_proportions() {
        // 460 and 480 dp on a 1280 dp landscape tablet, in this app's units
        assertEquals(DrawerWidths(channels = 690, programs = 720), DrawerWidths.of(1920))
    }

    @Test
    fun a_narrow_window_shrinks_them() {
        val w = DrawerWidths.of(1280)
        assertEquals(614, w.channels)
        assertEquals(614, w.programs)
    }

    @Test
    fun a_drawer_touches_its_edge_over_the_whole_height() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                // The test window is 1024×768: the screen is drawn inside it.
                Box(Modifier.size(1000.dp, 700.dp)) {
                    EdgeDrawer(start = true, width = 300.dp, modifier = Modifier.align(Alignment.CenterStart).testTag("left")) {}
                    EdgeDrawer(start = false, width = 320.dp, modifier = Modifier.align(Alignment.CenterEnd).testTag("right")) {}
                }
            }
        }
        val left = onNodeWithTag("left").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, left.left, 0.5f)
        assertEquals(0f, left.top, 0.5f)
        assertEquals(700f, left.height, 0.5f)
        val right = onNodeWithTag("right").fetchSemanticsNode().boundsInRoot
        assertEquals(1000f, right.right, 0.5f)
        assertEquals(700f, right.height, 0.5f)
    }
}
