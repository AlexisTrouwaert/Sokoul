package com.sokoul.pc

import com.sokoul.pc.playback.ui.EpisodeOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoNextTest {
    @Test
    fun counts_down_ten_seconds_then_zero() {
        assertEquals(10, EpisodeOrder.countdown(0, autoNext = true, dismissed = false))
        assertEquals(4, EpisodeOrder.countdown(6_200, autoNext = true, dismissed = false))
        assertEquals(0, EpisodeOrder.countdown(10_000, autoNext = true, dismissed = false))
        assertEquals(0, EpisodeOrder.countdown(25_000, autoNext = true, dismissed = false))
    }

    @Test
    fun nothing_starts_when_setting_off_or_card_dismissed() {
        assertNull(EpisodeOrder.countdown(10_000, autoNext = false, dismissed = false))
        assertNull(EpisodeOrder.countdown(10_000, autoNext = true, dismissed = true))
    }
}
