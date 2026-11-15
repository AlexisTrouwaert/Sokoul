package com.sokoul.pc

import com.sokoul.pc.title.HeroLogic
import com.sokoul.pc.title.NextEp
import com.sokoul.pc.title.PlayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HeroTest {
    private val today = LocalDate.of(2026, 11, 4)
    private fun next(air: String?) = NextEp(1, 4, 52, air)

    @Test
    fun the_main_button_follows_what_was_watched() {
        assertEquals(PlayKind.Watch, HeroLogic.playKind(false, false, false, null, today))
        assertEquals(PlayKind.Resume, HeroLogic.playKind(false, true, false, null, today))
        assertEquals(PlayKind.Rewatch, HeroLogic.playKind(false, true, true, null, today))
        assertEquals("a finished film has no next episode", PlayKind.Rewatch, HeroLogic.playKind(false, true, true, next("2020-01-01"), today))
        assertEquals(PlayKind.Next, HeroLogic.playKind(true, true, true, next("2020-01-01"), today))
        assertEquals(PlayKind.New, HeroLogic.playKind(true, true, true, next("2026-11-01"), today))
        assertEquals("nothing after the last episode", PlayKind.Rewatch, HeroLogic.playKind(true, true, true, null, today))
    }

    @Test
    fun a_new_episode_has_aired_in_the_last_two_weeks_and_not_in_the_future() {
        assertTrue(HeroLogic.isRecent("2026-11-04", today))
        assertTrue(HeroLogic.isRecent("2026-10-21", today))
        assertEquals(false, HeroLogic.isRecent("2026-10-20", today))
        assertEquals(false, HeroLogic.isRecent("2026-11-05", today))
        assertEquals(false, HeroLogic.isRecent(null, today))
        assertEquals(false, HeroLogic.isRecent("soon", today))
    }

    @Test
    fun the_glow_takes_the_lively_colour_and_ignores_grey_and_black() {
        fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        val red = IntArray(400) { px(200, 30, 30) } + IntArray(2000) { px(120, 120, 120) } + IntArray(2000) { px(5, 5, 5) }
        val c = HeroLogic.dominant(red)
        assertNotNull(c)
        assertTrue("red wins over grey", c!!.red > c.green * 3 && c.red > c.blue * 3)
        assertNull("a grey picture has no glow", HeroLogic.dominant(IntArray(1000) { px(120, 120, 120) }))
    }

    @Test
    fun a_language_is_named_in_the_interface_language() {
        assertNotNull(HeroLogic.languageName("en"))
        assertNull(HeroLogic.languageName(null))
        assertNull(HeroLogic.languageName(""))
    }

    @Test
    fun genres_fit_the_row() {
        assertEquals(listOf("Action", "Science-Fiction", "Crime"), com.sokoul.pc.title.fitGenres(listOf("Action", "Science-Fiction", "Crime", "Drame")))
        assertEquals(listOf("Action & Adventure", "Science-Fiction & Fantastique"), com.sokoul.pc.title.fitGenres(listOf("Action & Adventure", "Science-Fiction & Fantastique", "Animation")))
        assertEquals(emptyList<String>(), com.sokoul.pc.title.fitGenres(emptyList()))
    }
}
