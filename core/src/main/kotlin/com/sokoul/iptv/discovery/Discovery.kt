package com.sokoul.iptv.discovery

import java.time.LocalDate
import kotlin.random.Random

/** One release in the calendar: a film out in cinemas or streaming, or a series episode. */
data class Release(
    val tmdbId: Int,
    val kind: String,
    val title: String,
    val image: String?,
    val day: LocalDate,
    val season: Int? = null,
    val episode: Int? = null,
    /** True for a series the profile follows. */
    val followed: Boolean = false,
)

object ReleaseCalendar {
    /** The days from [from] for [days] days, each with its releases, followed series first; empty days are dropped. */
    fun week(releases: List<Release>, from: LocalDate, days: Int = 7): List<Pair<LocalDate, List<Release>>> {
        val until = from.plusDays(days.toLong())
        return releases.filter { !it.day.isBefore(from) && it.day.isBefore(until) }
            .distinctBy { Triple(it.kind, it.tmdbId, it.episode) }
            .groupBy { it.day }
            .toSortedMap()
            .map { (day, list) -> day to list.sortedWith(compareByDescending<Release> { it.followed }.thenBy { it.title }) }
    }
}

/** Filters of the roulette, turned into TMDB discover parameters. */
data class RouletteFilters(
    val kind: String = "movie",
    val genre: Int? = null,
    val maxRuntimeMin: Int? = null,
    val minRating: Double? = null,
    val providerId: Int? = null,
    val region: String = "FR",
) {
    fun params(): Map<String, String> = buildMap {
        put("sort_by", "popularity.desc")
        put("vote_count.gte", "150")
        genre?.let { put("with_genres", it.toString()) }
        maxRuntimeMin?.let { put("with_runtime.lte", it.toString()) }
        minRating?.let { put("vote_average.gte", it.toString()) }
        providerId?.let {
            put("with_watch_providers", it.toString())
            put("watch_region", region)
            put("with_watch_monetization_types", "flatrate")
        }
    }
}

object Roulette {
    const val MEMORY = 5

    /** A page among the first ten, so the draw is not always among the same twenty titles. */
    fun page(random: Random, totalPages: Int?): Int = random.nextInt(1, (totalPages ?: 10).coerceIn(1, 10) + 1)

    /** One title not drawn in the last five, or null when nothing is left. */
    fun <T> draw(candidates: List<T>, recent: List<Int>, id: (T) -> Int, random: Random): T? {
        val fresh = candidates.filter { id(it) !in recent.takeLast(MEMORY) }
        return fresh.takeIf { it.isNotEmpty() }?.random(random)
    }

    fun remember(recent: List<Int>, drawn: Int): List<Int> = (recent + drawn).takeLast(MEMORY)
}
