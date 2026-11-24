package com.sokoul.pc

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.sokoul.pc.startup.PinPad
import org.junit.Assert.assertEquals
import org.junit.Test

/** The profile code pad: four digits, a wrong code is said and cleared, the right one goes through. */
class PinPadTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aWrongCodeIsSaidAndTheRightOneOpens() = runComposeUiTest {
        com.sokoul.pc.i18n.Strings.applyLanguage("en")
        var opened: String? = null
        var cancelled = false
        setContent { PinPad("Sam's code", null, check = { it == "4821" }, onDone = { opened = it }, onCancel = { cancelled = true }) }
        fun type(code: String) = code.forEach { onNodeWithTag("pin-key-$it").performClick() }
        type("1234")
        waitForIdle()
        onNodeWithText("Wrong code. Try again.").assertExists()
        assertEquals(null, opened)
        type("48")
        onNodeWithTag("pin-key-⌫").performClick()
        type("821")
        waitForIdle()
        assertEquals("4821", opened)
        onNodeWithTag("pin-cancel").performClick()
        assertEquals(true, cancelled)
    }
}
