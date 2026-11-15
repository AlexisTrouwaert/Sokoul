package com.sokoul.iptv.discovery

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The year in review of one profile, ported from Movix `wrappedRoutes.js` and `utils/wrappedStory.js`
 * (CC BY-NC 4.0), without the community and percentile parts.
 *
 * Sokoul keeps one row per film or episode (last position and when it was saved), not every session:
 * active days, streaks and peak hour come from those times.
 */
data class ViewRow(
    val kind: String,
    val id: Int,
    val title: String,
    val image: String?,
    val seconds: Long,
    val at: LocalDateTime,
    val genres: List<String> = emptyList(),
)

data class TitleTime(val kind: String, val id: Int, val title: String, val image: String?, val minutes: Long)
data class GenreShare(val name: String, val minutes: Long, val percent: Int)
data class Era(val fromMonth: Int, val toMonth: Int, val kind: String, val label: String, val share: Int, val title: TitleTime)

enum class Persona { NightOwl, EarlyBird, Cinephile, Binger, SeriesAddict, StreakMachine, Explorer, Omnivore, MovieLover, SeriesFan }

data class YearSummary(
    val year: Int,
    val totalMinutes: Long,
    val uniqueTitles: Int,
    val activeDays: Int,
    val longestStreak: Int,
    val peakHour: Int,
    val busiestMonth: Int,
    val moviePercent: Int,
    val tvPercent: Int,
    val topTitles: List<TitleTime>,
    val topGenres: List<GenreShare>,
    val eras: List<Era>,
    val persona: Persona,
)

object YearReview {
    /** Null when nothing was watched that year. */
    fun of(rows: List<ViewRow>, year: Int): YearSummary? {
        val inYear = rows.filter { it.at.year == year && it.seconds > 0 }
        if (inYear.isEmpty()) return null
        val totalSeconds = inYear.sumOf { it.seconds }
        val totalMinutes = totalSeconds / 60
        val byTitle = inYear.groupBy { it.kind to it.id }.map { (_, list) ->
            val first = list.maxBy { it.at }
            TitleTime(first.kind, first.id, first.title, first.image, list.sumOf { it.seconds } / 60) to list
        }
        val topTitles = byTitle.map { it.first }.sortedWith(compareByDescending<TitleTime> { it.minutes }.thenBy { it.id }).take(5)
        val days = inYear.map { it.at.toLocalDate() }.toSortedSet()
        val hours = LongArray(24).also { h -> inYear.forEach { h[it.at.hour] += it.seconds } }
        val months = LongArray(13).also { m -> inYear.forEach { m[it.at.monthValue] += it.seconds } }
        val genreSeconds = HashMap<String, Long>()
        inYear.forEach { r -> r.genres.forEach { genreSeconds.merge(it, r.seconds, Long::plus) } }
        val topGenres = genreSeconds.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(3)
            .map { GenreShare(it.key, it.value / 60, (it.value * 100 / totalSeconds).toInt()) }
        val movieSeconds = inYear.filter { it.kind == "movie" }.sumOf { it.seconds }
        val moviePercent = (movieSeconds * 100 / totalSeconds).toInt()
        val tvPercent = 100 - moviePercent
        val streak = longestStreak(days)
        val night = (hours.slice(22..23) + hours.slice(0..4)).sum()
        val morning = hours.slice(5..8).sum()
        val episodesPerSeries = byTitle.filter { it.first.kind == "tv" }.map { it.second.size }
        val summary = YearSummary(
            year, totalMinutes, byTitle.size, days.size, streak,
            peakHour = hours.indices.maxBy { hours[it] },
            busiestMonth = (1..12).maxBy { months[it] },
            moviePercent = moviePercent, tvPercent = tvPercent,
            topTitles = topTitles, topGenres = topGenres,
            eras = eras(inYear),
            persona = Persona.MovieLover,
        )
        return summary.copy(persona = persona(summary, night * 100 / totalSeconds, morning * 100 / totalSeconds, episodesPerSeries.maxOrNull() ?: 0))
    }

    fun longestStreak(days: Collection<LocalDate>): Int {
        var best = 0
        var current = 0
        var previous: LocalDate? = null
        for (day in days.sorted()) {
            current = if (previous != null && previous.plusDays(1) == day) current + 1 else 1
            best = maxOf(best, current)
            previous = day
        }
        return best
    }

    /** Movix `determinePersona`, first matching rule wins; hour thresholds scaled from Movix's 100 h to 20 h. */
    private fun persona(s: YearSummary, nightPercent: Long, morningPercent: Long, mostEpisodes: Int): Persona {
        val hours = s.totalMinutes / 60
        return when {
            nightPercent > 30 && hours > 20 -> Persona.NightOwl
            morningPercent > 30 && hours > 20 -> Persona.EarlyBird
            s.moviePercent > 70 -> Persona.Cinephile
            s.tvPercent > 70 && mostEpisodes >= 10 -> Persona.Binger
            s.tvPercent > 50 -> Persona.SeriesAddict
            s.longestStreak >= 14 -> Persona.StreakMachine
            s.uniqueTitles > 50 -> Persona.Explorer
            maxOf(s.moviePercent, s.tvPercent) < 55 -> Persona.Omnivore
            s.moviePercent >= s.tvPercent -> Persona.MovieLover
            else -> Persona.SeriesFan
        }
    }

    /** Movix `buildEras`: months led by one genre (40 %+) or one format (60 %+), joined when they follow, three at most. */
    internal fun eras(rows: List<ViewRow>): List<Era> {
        data class Run(var from: Int, var to: Int, val kind: String, val label: String, var seconds: Long, var labelSeconds: Long, var title: Pair<ViewRow, Long>)
        val runs = mutableListOf<Run>()
        for (month in 1..12) {
            val list = rows.filter { it.at.monthValue == month }
            val total = list.sumOf { it.seconds }
            if (total == 0L) continue
            val withGenres = list.filter { it.genres.isNotEmpty() }
            val covered = withGenres.sumOf { it.seconds }
            val genre = withGenres.flatMap { r -> r.genres.map { it to r.seconds } }.groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).firstOrNull()
            val format = list.groupBy { it.kind }.mapValues { e -> e.value.sumOf { it.seconds } }.entries.maxByOrNull { it.value }
            val choice: Triple<String, String, Long> = when {
                genre != null && covered * 100 / total >= 70 && genre.value * 100 / total >= 40 -> Triple("genre", genre.key, genre.value)
                format != null && format.value * 100 / total >= 60 -> Triple("format", format.key, format.value)
                else -> null
            } ?: continue
            val best = list.filter { if (choice.first == "genre") choice.second in it.genres else it.kind == choice.second }.maxBy { it.seconds }
            val prior = runs.lastOrNull()
            if (prior != null && prior.to == month - 1 && prior.kind == choice.first && prior.label == choice.second) {
                prior.to = month
                prior.seconds += total
                prior.labelSeconds += choice.third
                if (best.seconds > prior.title.second) prior.title = best to best.seconds
            } else {
                runs += Run(month, month, choice.first, choice.second, total, choice.third, best to best.seconds)
            }
        }
        return runs.sortedWith(compareByDescending<Run> { it.seconds }.thenBy { it.from })
            .distinctBy { it.kind to it.label }.take(3)
            .map { r ->
                val t = r.title.first
                Era(r.from, r.to, r.kind, r.label, (r.labelSeconds * 100 / r.seconds).toInt(), TitleTime(t.kind, t.id, t.title, t.image, r.title.second / 60))
            }
    }
}
