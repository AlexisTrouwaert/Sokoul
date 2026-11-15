package com.sokoul.iptv.data.anime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What an anime is: TV series, film, OVA… as the anime databases say. */
enum class AnimeType { TV, MOVIE, OVA, ONA, SPECIAL, OTHER;
    companion object {
        fun of(s: String?): AnimeType = when (s?.uppercase()) {
            "TV", "TV_SHORT" -> TV
            "MOVIE" -> MOVIE
            "OVA" -> OVA
            "ONA" -> ONA
            "SPECIAL", "TV_SPECIAL" -> SPECIAL
            else -> OTHER
        }
    }
}

/**
 * One entry of the anime databases (a season, a film or a special) and where it is in each of them. [tmdbSeason] and
 * [tmdbOffset] place it in the catalog's series: "season 3, after its first 11 episodes".
 */
data class AnimeEntry(
    val type: AnimeType,
    val anilist: Int? = null,
    val mal: Int? = null,
    val kitsu: Int? = null,
    val anidb: Int? = null,
    val tvdb: Int? = null,
    val imdb: List<String> = emptyList(),
    val tmdbTv: Int? = null,
    val tmdbMovies: List<Int> = emptyList(),
    val tmdbSeason: Int? = null,
    val tmdbOffset: Int = 0,
    /** Episodes and the year it started, when the source knows them. */
    val episodes: Int? = null,
    val year: Int? = null,
) {
    /** Two entries agree when they share an id of the same database. */
    fun agreesWith(o: AnimeEntry): Boolean =
        same(anilist, o.anilist) || same(mal, o.mal) || same(kitsu, o.kitsu) || same(anidb, o.anidb)

    private fun same(a: Int?, b: Int?) = a != null && a == b

    /** What [o] knows and this one does not. */
    fun fill(o: AnimeEntry): AnimeEntry = copy(
        anilist = anilist ?: o.anilist, mal = mal ?: o.mal, kitsu = kitsu ?: o.kitsu, anidb = anidb ?: o.anidb, tvdb = tvdb ?: o.tvdb,
        imdb = (imdb + o.imdb).distinct(), tmdbTv = tmdbTv ?: o.tmdbTv, tmdbMovies = (tmdbMovies + o.tmdbMovies).distinct(),
        tmdbSeason = tmdbSeason ?: o.tmdbSeason, episodes = episodes ?: o.episodes, year = year ?: o.year,
    )
}

/** Another work with the same name: the live-action series, a remake. Its versions are never the anime. */
data class Homonym(val tmdbId: Int, val title: String, val year: Int?, val animated: Boolean, val networks: List<String> = emptyList())

/** A title of the catalog, linked for sure to the anime databases. */
data class AnimeIdentity(
    val tmdbId: Int,
    val series: Boolean,
    /** Every entry of this title: its seasons, its films and specials. */
    val entries: List<AnimeEntry>,
    val homonyms: List<Homonym> = emptyList(),
    /** Which sources agreed, for the log. */
    val sources: List<String> = emptyList(),
    /** Its names in the anime databases (English, romaji, native): what release names are written with. */
    val names: List<String> = emptyList(),
) {
    /** The entry that stands for the whole: the first TV season, else the first entry. */
    val main: AnimeEntry get() = entries.filter { it.type == AnimeType.TV || it.type == AnimeType.ONA }
        .minByOrNull { (it.tmdbSeason ?: 1) * 10_000 + it.tmdbOffset } ?: entries.first()
}

/** Reading the answers of the id services. Text in, entries out: a wrong answer is no entry, never an exception. */
object AnimeIdsParse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun ints(e: JsonElement?): List<Int> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        is JsonPrimitive -> listOfNotNull(e.intOrNull)
        else -> emptyList()
    }
    private fun texts(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.startsWith("tt") } }
        is JsonPrimitive -> listOfNotNull(e.contentOrNull?.takeIf { it.startsWith("tt") })
        else -> emptyList()
    }

    /** One entry of Fribb's anime-list-full.json. */
    fun fribb(o: JsonObject): AnimeEntry {
        val tmdb = o["themoviedb_id"]
        val tv = (tmdb as? JsonObject)?.get("tv")?.let(::ints)?.firstOrNull() ?: (tmdb as? JsonPrimitive)?.intOrNull?.takeIf { AnimeType.of(o.text("type")) != AnimeType.MOVIE }
        val movies = (tmdb as? JsonObject)?.get("movie")?.let(::ints).orEmpty()
        return AnimeEntry(
            type = AnimeType.of(o.text("type")),
            anilist = o.int("anilist_id"), mal = o.int("mal_id"), kitsu = o.int("kitsu_id"), anidb = o.int("anidb_id"), tvdb = o.int("tvdb_id"),
            imdb = texts(o["imdb_id"]), tmdbTv = tv, tmdbMovies = movies,
            tmdbSeason = (o["season"] as? JsonObject)?.int("tmdb"),
            tmdbOffset = (o["episode_offset"] as? JsonObject)?.int("tmdb") ?: 0,
        )
    }

    /** ARM (arm.haglund.dev): one object, or a list of them. */
    fun arm(text: String): List<AnimeEntry> = objects(text).map { o ->
        AnimeEntry(
            type = AnimeType.of(o.text("media")),
            anilist = o.int("anilist"), mal = o.int("myanimelist"), kitsu = o.int("kitsu"), anidb = o.int("anidb"), tvdb = o.int("thetvdb"),
            imdb = texts(o["imdb"]),
            tmdbTv = o.int("themoviedb").takeIf { AnimeType.of(o.text("media")) != AnimeType.MOVIE },
            tmdbMovies = listOfNotNull(o.int("themoviedb").takeIf { AnimeType.of(o.text("media")) == AnimeType.MOVIE }),
            tmdbSeason = o.int("themoviedb-season"),
        )
    }

    /** animeapi (animeapi.my.id): the same, with other names. */
    fun animeApi(text: String): List<AnimeEntry> = objects(text).filter { it["error"] == null }.map { o ->
        val movie = o.text("themoviedb_type") == "movie"
        AnimeEntry(
            type = if (movie) AnimeType.MOVIE else AnimeType.TV,
            anilist = o.int("anilist"), mal = o.int("myanimelist"), kitsu = o.int("kitsu"), anidb = o.int("anidb"), tvdb = o.int("thetvdb"),
            imdb = texts(o["imdb"]),
            tmdbTv = o.int("themoviedb").takeIf { !movie }, tmdbMovies = listOfNotNull(o.int("themoviedb").takeIf { movie }),
            tmdbSeason = o.int("themoviedb_season"),
        )
    }.filter { it.anilist != null || it.mal != null || it.kitsu != null }

    /** Kitsu `anime/{id}/mappings`: the ids it knows in the other databases. */
    fun kitsuMappings(kitsuId: Int, text: String): AnimeEntry? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonArray ?: return null
        var e = AnimeEntry(AnimeType.OTHER, kitsu = kitsuId)
        for (m in data) {
            val a = ((m as? JsonObject)?.get("attributes") as? JsonObject) ?: continue
            val id = a.text("externalId") ?: continue
            when (a.text("externalSite")) {
                "myanimelist/anime" -> e = e.copy(mal = e.mal ?: id.toIntOrNull())
                "anilist/anime" -> e = e.copy(anilist = e.anilist ?: id.toIntOrNull())
                "anidb" -> e = e.copy(anidb = e.anidb ?: id.toIntOrNull())
                "thetvdb/series", "thetvdb" -> e = e.copy(tvdb = e.tvdb ?: id.substringBefore('/').toIntOrNull())
            }
        }
        return e.takeIf { it.mal != null || it.anilist != null || it.anidb != null }
    }

    /** Kitsu `anime/{id}`: its names (English, romaji, Japanese, short ones), what release groups write. */
    fun kitsuNames(text: String): List<String> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val a = (root["data"] as? JsonObject)?.get("attributes") as? JsonObject ?: return emptyList()
        val titles = (a["titles"] as? JsonObject)?.values?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }.orEmpty()
        val short = (a["abbreviatedTitles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return (listOfNotNull(a.text("canonicalTitle")) + titles + short).filter { it.isNotBlank() }.distinct()
    }

    /** Kitsu `anime/{id}`: the length of an episode, in minutes. */
    fun kitsuEpisodeMinutes(text: String): Int? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return ((root["data"] as? JsonObject)?.get("attributes") as? JsonObject)?.int("episodeLength")?.takeIf { it > 0 }
    }

    /** One entry of manami's anime-offline-database: its ids come from the links to each site. */
    fun manami(o: JsonObject): Pair<AnimeEntry, List<String>>? {
        val sources = (o["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        fun id(host: String) = sources.firstOrNull { it.contains(host) }?.substringAfterLast('/')?.toIntOrNull()
        val entry = AnimeEntry(
            type = AnimeType.of(o.text("type")),
            anilist = id("anilist.co/anime/"), mal = id("myanimelist.net/anime/"), kitsu = id("kitsu.app/anime/") ?: id("kitsu.io/anime/"),
            anidb = id("anidb.net/anime/"),
            episodes = o.int("episodes")?.takeIf { it > 0 },
            year = (o["animeSeason"] as? JsonObject)?.int("year"),
        )
        if (entry.anilist == null && entry.mal == null && entry.kitsu == null) return null
        val names = listOfNotNull(o.text("title")) + (o["synonyms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return entry to names
    }

    private fun objects(text: String): List<JsonObject> = when (val e = runCatching { json.parseToJsonElement(text) }.getOrNull()) {
        is JsonObject -> listOf(e)
        is JsonArray -> e.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }
}
