package com.sokoul.iptv.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class DiscoveryTest {
    private val thriller = 53
    private val comedy = 35

    @Test
    fun profile_weights_finished_titles_and_genres() {
        val p = Taste.profile(listOf(
            Watched(1, "movie", watchedSec = 7_000, runtimeMin = 120, genres = listOf(thriller), year = 2019),
            Watched(2, "movie", watchedSec = 600, runtimeMin = 100, genres = listOf(comedy), year = 1995),
        ))!!
        assertTrue(p.genres.getValue(thriller) > p.genres.getValue(comedy) * 10)
        assertEquals(thriller, p.topGenres.first())
        assertEquals(1, p.top.first().id)
        assertEquals(setOf(2010, 1990), p.decades.keys)
    }

    @Test
    fun empty_history_has_no_profile() {
        assertNull(Taste.profile(emptyList()))
        assertNull(Taste.profile(listOf(Watched(1, "movie", 0, 90, listOf(comedy), 2020))))
    }

    @Test
    fun ranks_matching_genre_first_and_drops_finished() {
        val p = Taste.profile(listOf(Watched(1, "movie", 7_000, 120, listOf(thriller), 2020)))!!
        val ranked = Taste.rank(listOf(
            Candidate(10, "movie", listOf(comedy), popularity = 50.0, year = 2024),
            Candidate(11, "movie", listOf(thriller), popularity = 50.0, year = 2024),
            Candidate(12, "movie", listOf(thriller), popularity = 900.0, year = 2024),
            Candidate(12, "movie", listOf(thriller), popularity = 900.0, year = 2024),
        ), p, finished = setOf("movie" to 12), currentYear = 2026)
        assertEquals(listOf(11, 10), ranked.map { it.id })
    }

    @Test
    fun calendar_groups_by_day_followed_first_and_drops_out_of_week() {
        val monday = LocalDate.of(2026, 11, 9)
        val week = ReleaseCalendar.week(listOf(
            Release(1, "movie", "Zeta", null, monday),
            Release(2, "tv", "Alpha", null, monday, 1, 3, followed = true),
            Release(2, "tv", "Alpha", null, monday, 1, 3, followed = true),
            Release(3, "movie", "Later", null, monday.plusDays(7)),
            Release(4, "movie", "Before", null, monday.minusDays(1)),
            Release(5, "movie", "Friday", null, monday.plusDays(4)),
        ), monday)
        assertEquals(listOf(monday, monday.plusDays(4)), week.map { it.first })
        assertEquals(listOf("Alpha", "Zeta"), week.first().second.map { it.title })
    }

    @Test
    fun roulette_avoids_the_last_five_and_reports_empty() {
        val random = Random(7)
        val all = (1..6).toList()
        repeat(20) { assertEquals(6, Roulette.draw(all, listOf(1, 2, 3, 4, 5), { it }, random)) }
        assertNull(Roulette.draw(listOf(1), listOf(1), { it }, random))
        assertEquals(listOf(2, 3, 4, 5, 6), Roulette.remember(listOf(1, 2, 3, 4, 5), 6))
    }

    @Test
    fun roulette_filters_become_discover_params() {
        val params = RouletteFilters("movie", genre = comedy, maxRuntimeMin = 120, minRating = 7.0, providerId = 8).params()
        assertEquals("35", params["with_genres"])
        assertEquals("120", params["with_runtime.lte"])
        assertEquals("7.0", params["vote_average.gte"])
        assertEquals("8", params["with_watch_providers"])
        assertEquals("FR", params["watch_region"])
    }
}
