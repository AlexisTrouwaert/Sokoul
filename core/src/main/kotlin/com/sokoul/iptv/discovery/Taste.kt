package com.sokoul.iptv.discovery

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Personalized recommendations, ported from Movix `recommendationRoutes.js` (CC BY-NC 4.0):
 * a taste profile weighted by watch time and completion, then a score per candidate.
 * The collaborative part (other users) is left out: Sokoul has no shared server.
 */

/** One watched title with what TMDB says about it. */
data class Watched(
    val id: Int,
    val kind: String,
    val watchedSec: Long,
    val runtimeMin: Int?,
    val genres: List<Int>,
    val year: Int?,
    val keywords: List<Int> = emptyList(),
    val cast: List<Int> = emptyList(),
    val directors: List<Int> = emptyList(),
)

data class TasteProfile(
    val genres: Map<Int, Double>,
    val keywords: Map<Int, Double>,
    val people: Map<Int, Double>,
    val decades: Map<Int, Double>,
    val formats: Map<String, Double>,
    /** Most watched first, at most ten. */
    val top: List<Watched>,
) {
    val topGenres: List<Int> get() = genres.entries.sortedByDescending { it.value }.take(3).map { it.key }
}

/** A title that could be recommended. */
data class Candidate(
    val id: Int,
    val kind: String,
    val genres: List<Int>,
    val popularity: Double?,
    val year: Int?,
    val keywords: List<Int> = emptyList(),
    val people: List<Int> = emptyList(),
)

object Taste {
    /** The 30 most watched titles make the profile, as in Movix. Null when nothing was watched. */
    fun profile(history: List<Watched>): TasteProfile? {
        val rows = history.filter { it.watchedSec > 0 && (it.kind == "movie" || it.kind == "tv") }
            .sortedByDescending { it.watchedSec }.take(30)
        if (rows.isEmpty()) return null
        val maxDuration = rows.first().watchedSec.toDouble()
        val genres = HashMap<Int, Double>()
        val keywords = HashMap<Int, Double>()
        val people = HashMap<Int, Double>()
        val decades = HashMap<Int, Double>()
        val formats = hashMapOf("movie" to 0.0, "tv" to 0.0)
        for (w in rows) {
            val completion = min(w.watchedSec / ((w.runtimeMin ?: 90) * 60.0), 1.0)
            val completionWeight = when {
                completion >= 0.9 -> 3.0
                completion >= 0.5 -> 1.5
                completion >= 0.2 -> 1.0
                else -> 0.3
            }
            val weight = w.watchedSec / maxDuration * completionWeight
            w.genres.forEach { genres.merge(it, weight, Double::plus) }
            w.keywords.forEach { keywords.merge(it, weight, Double::plus) }
            w.cast.take(5).forEach { people.merge(it, weight, Double::plus) }
            w.directors.forEach { people.merge(it, weight * 1.5, Double::plus) }
            w.year?.let { decades.merge(it / 10 * 10, weight, Double::plus) }
            formats.merge(w.kind, weight, Double::plus)
        }
        return TasteProfile(genres, keywords, people, decades, formats, rows.take(10))
    }

    /** Movix weights: genres 35 %, keywords 25 %, people 15 %, popularity 10 %, recency 10 %, format 5 %. */
    fun score(c: Candidate, p: TasteProfile, currentYear: Int): Double {
        val maxGenre = max(p.genres.values.maxOrNull() ?: 1.0, 1.0)
        val genreScore = if (c.genres.isEmpty()) 0.0 else min(c.genres.sumOf { (p.genres[it] ?: 0.0) / maxGenre } / c.genres.size, 1.0)
        val maxKeyword = max(p.keywords.values.maxOrNull() ?: 1.0, 1.0)
        val keywordScore = if (c.keywords.isEmpty()) 0.0 else min(c.keywords.sumOf { (p.keywords[it] ?: 0.0) / maxKeyword } / c.keywords.size, 1.0)
        val maxPeople = max(p.people.values.maxOrNull() ?: 1.0, 1.0)
        val peopleScore = if (c.people.isEmpty()) 0.0 else min(c.people.sumOf { (p.people[it] ?: 0.0) / maxPeople } / min(c.people.size, 3), 1.0)
        val popularity = c.popularity?.let { min(log10(it + 1) / 3, 1.0) } ?: 0.0
        val recency = c.year?.let { max(1 - (currentYear - it) / 30.0, 0.0) } ?: 0.5
        val totalFormat = (p.formats["movie"] ?: 0.0) + (p.formats["tv"] ?: 0.0)
        val format = (p.formats[c.kind] ?: 0.0) / if (totalFormat > 0) totalFormat else 1.0
        return genreScore * 0.35 + keywordScore * 0.25 + peopleScore * 0.15 + popularity * 0.10 + recency * 0.10 + format * 0.05
    }

    /** Best candidates first, without duplicates or titles already finished. */
    fun rank(candidates: List<Candidate>, p: TasteProfile, finished: Set<Pair<String, Int>>, currentYear: Int, limit: Int = 20): List<Candidate> =
        candidates.distinctBy { it.kind to it.id }
            .filter { (it.kind to it.id) !in finished }
            .sortedByDescending { score(it, p, currentYear) }
            .take(limit)
}
