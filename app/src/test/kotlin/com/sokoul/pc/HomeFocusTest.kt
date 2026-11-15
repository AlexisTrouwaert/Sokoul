package com.sokoul.pc

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.sokoul.pc.ui.CardRow
import com.sokoul.pc.ui.FocusCard
import org.junit.Test

/** Remote focus on home rows (openspec tv-home-redesign, home-focus-navigation). */
@OptIn(ExperimentalTestApi::class)
class HomeFocusTest {
    @Test
    fun coming_back_into_a_row_lands_on_the_card_left_there() = runComposeUiTest {
        val first = FocusRequester()
        setContent {
            Column {
                // Narrow cards above, wide ones below: the card nearest to where focus comes back from is not the one left.
                CardRow("A", (0 until 12).toList(), key = { it }, testTag = "row-a") { i, _ ->
                    FocusCard(100.dp, 150.dp, {}, tag = "a-$i", requester = if (i == 0) first else remember { FocusRequester() }) {}
                }
                CardRow("B", (0 until 12).toList(), key = { it }, testTag = "row-b") { i, _ ->
                    FocusCard(300.dp, 150.dp, {}, tag = "b-$i") {}
                }
            }
        }
        waitForIdle()
        runOnIdle { first.requestFocus() }
        waitForIdle()
        repeat(4) { focused().performKeyInput { pressKey(Key.DirectionRight) }; waitForIdle() }
        org.junit.Assert.assertEquals("a-4", tagOf())
        focused().performKeyInput { pressKey(Key.DirectionDown) }
        repeat(2) { focused().performKeyInput { pressKey(Key.DirectionRight) } }
        val below = tagOf()
        focused().performKeyInput { pressKey(Key.DirectionUp) }
        val back = tagOf()
        org.junit.Assert.assertEquals("below=$below", "a-4", back)
    }

    @Test
    fun a_dead_end_keeps_focus_where_it_is() = runComposeUiTest {
        val first = FocusRequester()
        setContent {
            CardRow("A", (0 until 3).toList(), key = { it }, testTag = "row-a") { i, _ ->
                FocusCard(100.dp, 150.dp, {}, tag = "a-$i", requester = if (i == 0) first else remember { FocusRequester() }) {}
            }
        }
        waitForIdle()
        runOnIdle { first.requestFocus() }
        waitForIdle()
        repeat(2) { focused().performKeyInput { pressKey(Key.DirectionRight) } }
        onNodeWithTag("a-2").assertIsFocused()
        focused().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithTag("a-2").assertIsFocused()
    }

    @Test
    fun loading_shapes_never_take_focus() = runComposeUiTest {
        val first = FocusRequester()
        setContent {
            Column {
                CardRow("A", (0 until 3).toList(), key = { it }, testTag = "row-a") { i, _ ->
                    FocusCard(100.dp, 150.dp, {}, tag = "a-$i", requester = if (i == 0) first else remember { FocusRequester() }) {}
                }
                com.sokoul.pc.ui.SkeletonRow(landscape = false)
            }
        }
        waitForIdle()
        runOnIdle { first.requestFocus() }
        waitForIdle()
        onNodeWithTag("skeleton-row").assertExists()
        focused().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        org.junit.Assert.assertEquals("a-0", tagOf())
    }

    private fun androidx.compose.ui.test.ComposeUiTest.tagOf(): String? =
        focused().fetchSemanticsNode().config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }

    private fun androidx.compose.ui.test.ComposeUiTest.focused(): SemanticsNodeInteraction = onNode(isFocused())
}
