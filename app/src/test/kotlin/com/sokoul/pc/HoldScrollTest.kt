package com.sokoul.pc

import com.sokoul.pc.playback.ui.HoldScroll
import org.junit.Assert.assertEquals
import org.junit.Test

/** Holding a key in the channel list speeds up after half a second, and stops when let go (spec pc-live-tv). */
class HoldScrollTest {
    private var now = 0L
    private val hold = HoldScroll { now }

    @Test
    fun presses_move_one_row_each() {
        repeat(5) {
            assertEquals(1, hold.down())
            hold.up()
            now += 100
        }
    }

    @Test
    fun a_held_key_speeds_up_after_half_a_second() {
        assertEquals(1, hold.down())
        now += 250
        assertEquals(1, hold.down())
        now += 250
        assertEquals(HoldScroll.FAST_STEP, hold.down())
        now += 50
        assertEquals(HoldScroll.FAST_STEP, hold.down())
    }

    @Test
    fun letting_go_starts_slow_again() {
        hold.down()
        now += 600
        assertEquals(HoldScroll.FAST_STEP, hold.down())
        hold.up()
        assertEquals(1, hold.down())
    }
}
