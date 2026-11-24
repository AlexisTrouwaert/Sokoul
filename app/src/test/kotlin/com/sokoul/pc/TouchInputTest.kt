package com.sokoul.pc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.sokoul.pc.ui.FocusSource
import com.sokoul.pc.ui.LocalHoverOnly
import com.sokoul.pc.ui.tvFocusable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** openspec/changes/settings-redesign/specs/touch-input: a finger never leaves a highlight behind. */
@OptIn(ExperimentalTestApi::class)
class TouchInputTest {
    private fun rows(lit: MutableList<Boolean>, clicks: MutableList<Int>) = @androidx.compose.runtime.Composable {
        CompositionLocalProvider(LocalHoverOnly provides true) {
            Column {
                for (i in 0 until 3) {
                    Box(Modifier.size(300.dp, 60.dp).testTag("row$i").tvFocusable(onFocusChanged = { lit[i] = it }, onClick = { clicks += i }))
                }
            }
        }
    }

    @Test
    fun twoTapsAndAScrollLeaveNoRowLit() = runComposeUiTest {
        val lit = mutableStateListOf(false, false, false)
        val clicks = mutableListOf<Int>()
        FocusSource.lastWasMouse = false
        setContent { rows(lit, clicks)() }
        onNodeWithTag("row0").performTouchInput { down(center); moveBy(Offset(0f, 4f)); up() }
        onNodeWithTag("row1").performTouchInput { down(center); moveBy(Offset(2f, 3f)); up() }
        // a drag across rows, as a scroll would do
        onNodeWithTag("row2").performTouchInput { down(center); moveBy(Offset(0f, -40f)); moveBy(Offset(0f, -40f)); up() }
        onNodeWithTag("row2").performTouchInput { down(center); up() }
        waitForIdle()
        assertEquals(listOf(false, false, false), lit.toList())
        assertEquals(listOf(0, 1, 2), clicks)
        assertFalse("a touch must not switch the app to mouse mode", FocusSource.lastWasMouse)
    }

    @Test
    fun aTapDropsTheRemoteFocusAPageGaveWhenItOpened() = runComposeUiTest {
        val lit = mutableStateListOf(false, false, false)
        val first = androidx.compose.ui.focus.FocusRequester()
        setContent {
            CompositionLocalProvider(LocalHoverOnly provides true) {
                Column {
                    for (i in 0 until 3) {
                        Box(Modifier.size(300.dp, 60.dp).testTag("row$i").tvFocusable(onFocusChanged = { lit[i] = it }, onClick = {}, requester = if (i == 0) first else androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }))
                    }
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { first.requestFocus() }
        }
        waitForIdle()
        assertEquals(true, lit[0])
        onNodeWithTag("row2").performTouchInput { down(center); up() }
        waitForIdle()
        assertEquals(listOf(false, false, false), lit.toList())
    }

    @Test
    fun aMouseStillHighlightsTheRowUnderItAndClearsItOnLeaving() = runComposeUiTest {
        val lit = mutableStateListOf(false, false, false)
        setContent { rows(lit, mutableListOf())() }
        onNodeWithTag("row0").performMouseInput { moveTo(center) }
        onNodeWithTag("row0").performMouseInput { moveBy(Offset(1f, 0f)) }
        waitForIdle()
        assertEquals(true, lit[0])
        onNodeWithTag("row1").performMouseInput { moveTo(center) }
        onNodeWithTag("row1").performMouseInput { moveBy(Offset(1f, 0f)) }
        waitForIdle()
        assertEquals(listOf(false, true, false), lit.toList())
    }
}
