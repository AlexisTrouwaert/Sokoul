package com.sokoul.pc

import com.sokoul.pc.playback.ui.EpisodeOrder
import com.sokoul.iptv.data.net.TmdbEpisode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoNextTest {
    @Test
    fun next_uses_episode_numbers_then_the_first_episode_in_the_next_season() = runBlocking {
        val calls = mutableListOf<Int>()
        fun episode(season: Int, number: Int) = TmdbEpisode(season, number, null, null, null, null)
        val read: suspend (Int) -> List<TmdbEpisode> = { season ->
            calls += season
            if (season == 1) listOf(episode(1, 5), episode(1, 1), episode(2, 3))
            else listOf(episode(2, 8), episode(2, 2))
        }
        assertEquals(episode(1, 5), EpisodeOrder.next(1, 1, 2, read))
        assertEquals(listOf(1), calls)
        assertEquals(episode(2, 2), EpisodeOrder.next(1, 5, 2, read))
        assertEquals(listOf(1, 1, 2), calls)
        assertNull(EpisodeOrder.next(2, 8, 2, read))
        assertEquals(listOf(1, 1, 2, 2), calls)
    }

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
