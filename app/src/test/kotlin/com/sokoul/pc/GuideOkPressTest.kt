package com.sokoul.pc

import com.sokoul.pc.live.GuideOkPress
import org.junit.Assert.*
import org.junit.Test

class GuideOkPressTest {
    @Test fun `short ok fires once on release`() {
        val press = GuideOkPress()
        assertTrue(press.down("a")); assertFalse(press.down("a"))
        assertEquals("a", press.up()); assertNull(press.up())
        assertFalse(press.expire("a"))
    }
    @Test fun `held ok and repeats never play on release`() {
        val press = GuideOkPress()
        press.down("a")
        assertTrue(press.expire("a")); assertFalse(press.expire("a"))
        assertFalse(press.down("a")); assertNull(press.up())
    }
    @Test fun `selection change or focus loss cancels pending gesture`() {
        val press = GuideOkPress()
        press.down("a"); assertFalse(press.expire("b"))
        press.cancel(); assertNull(press.up()); assertFalse(press.expire("a"))
        assertTrue(press.down("b")); assertEquals("b", press.up())
    }
}
