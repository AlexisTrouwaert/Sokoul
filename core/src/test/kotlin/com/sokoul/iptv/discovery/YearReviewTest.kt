package com.sokoul.iptv.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class YearReviewTest {
    private fun row(kind: String, id: Int, minutes: Long, at: LocalDateTime, vararg genres: String) =
        ViewRow(kind, id, "T$id", null, minutes * 60, at, genres.toList())

    @Test
    fun sums_titles_genres_formats_and_days() {
        val rows = listOf(
            row("movie", 1, 120, LocalDateTime.of(2026, 3, 1, 21, 0), "Thriller"),
            row("tv", 2, 45, LocalDateTime.of(2026, 3, 2, 20, 0), "Drame"),
            row("tv", 2, 45, LocalDateTime.of(2026, 3, 3, 20, 0), "Drame"),
            row("movie", 3, 90, LocalDateTime.of(2025, 12, 31, 20, 0), "Comédie"),
        )
        val s = YearReview.of(rows, 2026)!!
        assertEquals(210, s.totalMinutes)
        assertEquals(2, s.uniqueTitles)
        assertEquals(3, s.activeDays)
        assertEquals(3, s.longestStreak)
        assertEquals(listOf(1, 2), s.topTitles.map { it.id })
        assertEquals(listOf("Thriller", "Drame"), s.topGenres.map { it.name })
        assertEquals(57, s.moviePercent)
        assertEquals(3, s.busiestMonth)
        assertEquals(Persona.MovieLover, s.persona)
    }

    @Test
    fun empty_year_has_no_summary() {
        assertNull(YearReview.of(listOf(row("movie", 1, 90, LocalDateTime.of(2025, 5, 1, 20, 0))), 2026))
    }

    @Test
    fun night_viewer_and_cinephile() {
        val night = (1..30).map { row("movie", it, 60, LocalDateTime.of(2026, 1, it, 23, 30)) }
        assertEquals(Persona.NightOwl, YearReview.of(night, 2026)!!.persona)
        val films = (1..10).map { row("movie", it, 100, LocalDateTime.of(2026, 2, it, 20, 0)) }
        assertEquals(Persona.Cinephile, YearReview.of(films, 2026)!!.persona)
    }

    @Test
    fun streak_counts_consecutive_days_only() {
        val d = LocalDate.of(2026, 4, 1)
        assertEquals(2, YearReview.longestStreak(listOf(d, d.plusDays(1), d.plusDays(3))))
        assertEquals(0, YearReview.longestStreak(emptyList()))
    }

    @Test
    fun eras_join_following_months_of_the_same_genre() {
        val rows = listOf(
            row("tv", 1, 300, LocalDateTime.of(2026, 1, 5, 20, 0), "Science-Fiction"),
            row("tv", 1, 300, LocalDateTime.of(2026, 2, 5, 20, 0), "Science-Fiction"),
            row("movie", 2, 200, LocalDateTime.of(2026, 6, 5, 20, 0), "Horreur"),
        )
        val eras = YearReview.eras(rows)
        assertEquals("Science-Fiction", eras.first().label)
        assertEquals(1 to 2, eras.first().fromMonth to eras.first().toMonth)
        assertEquals("Horreur", eras[1].label)
    }
}
