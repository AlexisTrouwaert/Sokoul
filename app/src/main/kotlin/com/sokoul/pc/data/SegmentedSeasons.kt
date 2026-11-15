package com.sokoul.pc.data

import com.sokoul.iptv.data.net.TmdbEpisode
import kotlin.math.roundToInt

/**
 * Broadcast episode numbering for explicitly configured segmented cartoons.
 * Adapted from Movix's segmentedShows.ts and mergeSegmentedSeason.ts (CC BY-NC 4.0,
 * complete attribution/license in docs/third-party/MOVIX-LICENSE). Never infer this for other shows.
 */
object SegmentedSeasons {
    private val placeholder = Regex("(?:episode|épisode|folge|episodio)\\s*\\d+", RegexOption.IGNORE_CASE)
    private val loudSeason2 = mapOf(27 to "216A", 32 to "216B", 35 to "219B", 36 to "219A", 37 to "220A", 38 to "220B", 39 to "221A", 40 to "224", 41 to "221B", 44 to "222A", 45 to "222B")

    fun enabled(show: Int, season: Int) = show == 68073 && season > 0

    fun needsFallback(episodes: List<TmdbEpisode>) = episodes.any {
        it.name.isNullOrBlank() || placeholder.matches(it.name.orEmpty().trim()) || it.overview.isNullOrBlank()
    }

    fun season(show: Int, season: Int, episodes: List<TmdbEpisode>, fallback: List<TmdbEpisode> = emptyList()): List<TmdbEpisode> {
        if (!enabled(show, season) || episodes.any { it.sourceEpisodeNumbers.isNotEmpty() }) return episodes
        return merge(episodes, if (season == 2) loudSeason2 else emptyMap(), fallback)
    }

    private data class Code(val slot: String, val spans: Int, val half: Boolean)
    private fun code(raw: String?, runtime: Int?): Code? {
        val tokens = raw?.trim()?.split(Regex("[\\s,/+]+"))?.filter(String::isNotBlank).orEmpty()
        if (tokens.isEmpty()) return null
        val first = tokens.first().replace(Regex("[A-Za-z]+$"), "").takeIf(String::isNotBlank) ?: return null
        if (tokens.size > 1) return Code(first, tokens.size, false)
        if (tokens.first().last().isLetter()) return Code(first, 1, true)
        return Code(first, if ((runtime ?: 0) >= 33) ((runtime ?: 0) / 22.0).roundToInt().coerceAtLeast(1) else 1, false)
    }

    /** Missing production codes pair only with the immediate uncoded, short successor. */
    internal fun merge(input: List<TmdbEpisode>, fixes: Map<Int, String> = emptyMap(), fallback: List<TmdbEpisode> = emptyList()): List<TmdbEpisode> {
        val source = input.sortedBy { it.episode }
        val codes = source.map { code(fixes[it.episode] ?: it.productionCode, it.runtimeMin) }
        val halves = source.indices.map { i ->
            codes[i]?.half == true || ((codes[i] == null || codes[i]?.spans == 1) && (source[i].runtimeMin ?: 0) <= 12)
        }
        val alternate = fallback.associateBy { it.episode }
        val consumed = BooleanArray(source.size)
        val result = ArrayList<TmdbEpisode>()
        for (i in source.indices) {
            if (consumed[i]) continue
            consumed[i] = true
            val current = codes[i]
            val partner = if (!halves[i] || (current?.spans ?: 1) > 1) null
            else if (current == null) (i + 1).takeIf { it < source.size && !consumed[it] && codes[it] == null && halves[it] }
            else ((i + 1) until source.size).firstOrNull { !consumed[it] && halves[it] && codes[it]?.slot == current.slot && codes[it]?.spans == 1 }
            if (partner != null) consumed[partner] = true
            val entries = listOfNotNull(source[i], partner?.let(source::get))
            val enriched = entries.map { ep ->
                val other = alternate[ep.episode]
                ep.copy(
                    name = ep.name?.takeIf { it.isNotBlank() && !placeholder.matches(it.trim()) } ?: other?.name ?: ep.name,
                    overview = ep.overview?.takeIf(String::isNotBlank) ?: other?.overview,
                    still = ep.still ?: other?.still,
                )
            }
            val spans = current?.spans ?: 1
            repeat(spans) { part ->
                result += entries.first().copy(
                    episode = result.size + 1,
                    name = enriched.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" / ") + if (spans > 1) " (${part + 1}/$spans)" else "",
                    overview = enriched.mapNotNull { it.overview?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n\n").ifEmpty { null },
                    still = enriched.firstNotNullOfOrNull { it.still },
                    runtimeMin = (entries.sumOf { it.runtimeMin ?: 0 } / spans.toDouble()).roundToInt(),
                    airDate = entries.firstNotNullOfOrNull { it.airDate },
                    sourceEpisodeNumbers = entries.map { it.episode },
                    partIndex = (part + 1).takeIf { spans > 1 }, partCount = spans.takeIf { it > 1 },
                )
            }
        }
        return result
    }
}
