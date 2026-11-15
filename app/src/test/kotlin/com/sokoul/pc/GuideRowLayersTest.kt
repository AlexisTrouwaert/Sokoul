package com.sokoul.pc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.live.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class GuideRowLayersTest {
    private val now = 1_800_000_000_000L
    private val channels = (0..1).map { i -> Channel(0, "test-$i", i + 1, "Channel $i", null,
        "sample", null, "test-$i", null, null, null, i) }
    private fun programs(short: Boolean = false) = channels.associate { c ->
        c.id to listOf(Program(c.id, now - (if (short) 2 else 10) * 60_000L,
            now + (if (short) 3 else 30) * 60_000L, "Programme", null))
    }

    @Composable
    private fun Grid(focus: GuideFocus, schedules: Map<String, List<Program>>) {
        val layers = List(12) { rememberGraphicsLayer() }
        val cache = remember(layers) { GuideRowLayers(layers) }
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current.density
        Canvas(Modifier.size(1000.dp, 260.dp).background(FormulerGuideStyle.background).testTag("grid")) {
            drawGuide(measurer, channels, { schedules[it].orEmpty() }, { _, _ -> false }, emptySet(),
                focus, emptyMap(), now, true, density, "", 0f, 0f, now - 20 * 60_000L,
                620f, 80f, 1f, guideSizes(GuideLevel.Guide, true), television = true, rowLayers = cache)
        }
    }

    @Test
    fun cached_rows_refresh_selection_without_retaining_the_old_fill() = runComposeUiTest {
        val focus = GuideFocus().apply { anchor = now }
        val schedules = programs()
        setContent { Grid(focus, schedules) }
        val before = onNodeWithTag("grid").captureToImage().toPixelMap()
        assertEquals(FormulerGuideStyle.selected.toArgb(), before[840, 110].toArgb())
        assertEquals(FormulerGuideStyle.current.toArgb(), before[840, 190].toArgb())
        runOnIdle { focus.row = 1 }
        val after = onNodeWithTag("grid").captureToImage().toPixelMap()
        assertEquals(FormulerGuideStyle.current.toArgb(), after[840, 110].toArgb())
        assertEquals(FormulerGuideStyle.selected.toArgb(), after[840, 190].toArgb())
    }

    @Test
    fun epg_refresh_replaces_cached_programme_widths() = runComposeUiTest {
        val focus = GuideFocus().apply { anchor = now }
        var schedules by mutableStateOf(programs())
        setContent { Grid(focus, schedules) }
        assertEquals(FormulerGuideStyle.selected.toArgb(), onNodeWithTag("grid").captureToImage().toPixelMap()[840, 110].toArgb())
        runOnIdle { schedules = programs(short = true) }
        val after = onNodeWithTag("grid").captureToImage().toPixelMap()
        assertEquals(FormulerGuideStyle.background.toArgb(), after[840, 110].toArgb())
        assertEquals(FormulerGuideStyle.selected.toArgb(), after[555, 110].toArgb())
        assertEquals(0, focus.row)
        assertEquals(now, focus.anchor)
    }
}
