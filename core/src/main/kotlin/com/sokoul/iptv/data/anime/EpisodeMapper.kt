package com.sokoul.iptv.data.anime

/** An episode in one database: the entry (a season there) and its number in it. */
data class EntryEpisode(val id: Int, val episode: Int)

/** Where an episode of the catalog is in each anime database. Each part is null when it cannot be told for sure. */
data class EpisodeRef(
    val season: Int,
    val episode: Int,
    /** Since the first episode of the series, specials not counted. */
    val absolute: Int?,
    val kitsu: EntryEpisode? = null,
    val mal: EntryEpisode? = null,
    val anidb: EntryEpisode? = null,
    val anilist: EntryEpisode? = null,
)

/**
 * The one place that converts the catalog's "season and episode" into the numbers of the anime databases, and back.
 * Nothing is guessed: a season whose size is unknown makes every number after it unknown.
 */
object EpisodeMapper {
    /**
     * @param seasonSizes episodes of each season of the catalog, from season 1 up to the one asked for; 0 or null when unknown
     * @param entries the anime entries of this series (from [AnimeIdentity.entries])
     */
    fun map(season: Int, episode: Int, seasonSizes: List<Int?>, entries: List<AnimeEntry>): EpisodeRef {
        val absolute = absolute(season, episode, seasonSizes)
        val entry = entryFor(season, episode, entries)
        val inEntry = when {
            entry == null -> null
            // Placed in its season: the number there, after its offset.
            entry.tmdbSeason != null -> episode - entry.tmdbOffset
            // One entry for the whole series (One Piece): it counts from the first episode.
            else -> absolute
        }?.takeIf { it > 0 }
        fun at(id: Int?) = if (id != null && inEntry != null) EntryEpisode(id, inEntry) else null
        return EpisodeRef(season, episode, absolute, at(entry?.kitsu), at(entry?.mal), at(entry?.anidb), at(entry?.anilist))
    }

    /** The number since the first season: the sizes of the seasons before it, all of them known. */
    fun absolute(season: Int, episode: Int, seasonSizes: List<Int?>): Int? {
        if (season < 1 || episode < 1) return null
        var before = 0
        for (s in 1 until season) {
            val size = seasonSizes.getOrNull(s - 1)
            if (size == null || size <= 0) return null
            before += size
        }
        // TMDB numbers One Piece's late seasons from the first episode (season 23 runs 1156 to 1180): a number past the
        // season's size that falls after the seasons before it is already the number since the first episode.
        val size = seasonSizes.getOrNull(season - 1)
        if (size != null && size > 0 && episode > size && episode > before && episode <= before + size) return episode
        return before + episode
    }

    /** Back from the number since the first season to a season and its episode. Null past the known seasons. */
    fun place(absolute: Int, seasonSizes: List<Int?>): Pair<Int, Int>? {
        if (absolute < 1) return null
        var left = absolute
        seasonSizes.forEachIndexed { i, size ->
            if (size == null || size <= 0) return null
            if (left <= size) return (i + 1) to left
            left -= size
        }
        return null
    }

    /**
     * The entry of the anime databases that holds this episode. Among the entries placed in its season, the one whose
     * offset is the largest still before it ("Part 2" starts at episode 13). A series kept as one single entry with no
     * season holds every episode. A film or special never stands for an episode.
     */
    fun entryFor(season: Int, episode: Int, entries: List<AnimeEntry>): AnimeEntry? {
        val series = entries.filter { it.type == AnimeType.TV || it.type == AnimeType.ONA }
        val placed = series.filter { it.tmdbSeason == season && it.tmdbOffset < episode }
        if (placed.isNotEmpty()) return placed.maxBy { it.tmdbOffset }
        if (series.any { it.tmdbSeason != null }) return null
        return series.singleOrNull()
    }
}
