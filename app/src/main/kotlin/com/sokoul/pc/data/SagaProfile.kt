package com.sokoul.pc.data

import com.sokoul.iptv.data.net.TmdbInfo
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.data.net.TmdbPerson
import com.sokoul.iptv.data.net.TmdbVideo
import com.sokoul.pc.i18n.Strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/** What OMDb knows about a film. Absent values stay null. */
data class OmdbFacts(
    val imdb: Double? = null,
    val rottenTomatoes: Int? = null,
    val oscarsWon: Int = 0,
    val oscarNominations: Int = 0,
    val awardsWon: Int = 0,
    val metacritic: Int? = null,
    /** OMDb's sentence, in English: "Won 6 Oscars. 65 wins & 31 nominations total". */
    val awardsText: String? = null,
)

/** OMDb answers, cached by the caller: its free plan allows 1000 calls a day. The key is never logged or cached. */
class OmdbClient(
    private val http: OkHttpClient,
    private val key: () -> String?,
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    fun facts(imdbId: String): OmdbFacts? {
        val cacheKey = "omdb:$imdbId"
        read(cacheKey)?.let { return parse(it) }
        val apiKey = key() ?: return null
        val url = BASE.toHttpUrl().newBuilder().addQueryParameter("i", imdbId).addQueryParameter("apikey", apiKey).build()
        val text = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        val answer = runCatching { parser.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        // A refusal, such as the daily limit, must not be remembered as "no data".
        if (answer["Response"]?.jsonPrimitive?.contentOrNull != "True") return null
        write(cacheKey, text)
        return parse(text)
    }

    companion object {
        private const val BASE = "https://www.omdbapi.com/"
        private val OSCARS_WON = Regex("""(?i)won (\d+) oscars?""")
        private val OSCARS_NOMINATED = Regex("""(?i)nominated for (\d+) oscars?""")
        private val WINS = Regex("""(?i)(\d+) wins?\b""")
        private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): OmdbFacts? {
            val o = runCatching { parser.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun str(name: String) = o[name]?.jsonPrimitive?.contentOrNull?.takeIf { it != "N/A" }
            val awards = str("Awards").orEmpty()
            val critics = (o["Ratings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { it["Source"]?.jsonPrimitive?.contentOrNull == "Rotten Tomatoes" }
                ?.get("Value")?.jsonPrimitive?.contentOrNull?.removeSuffix("%")?.toIntOrNull()
            fun count(regex: Regex) = regex.find(awards)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return OmdbFacts(str("imdbRating")?.toDoubleOrNull(), critics, count(OSCARS_WON), count(OSCARS_NOMINATED), count(WINS), str("Metascore")?.toIntOrNull(), awards.ifBlank { null })
        }
    }
}

/** One film of a saga, with whatever could be gathered about it. */
data class FilmFacts(val film: TmdbItem, val info: TmdbInfo?, val omdb: OmdbFacts?)

data class RecurringActor(val person: TmdbPerson, val films: Int)

data class SagaTrailer(val film: TmdbItem, val video: TmdbVideo)

/** A saga's page, gathered from every film. Each value is null or empty when no source knows it. */
data class SagaProfile(
    val overview: String?,
    val films: List<FilmFacts>,
    val boxOffice: Long?,
    val budget: Long?,
    val runtimeMin: Int?,
    val imdb: Double?,
    val rottenTomatoes: Int?,
    val oscarsWon: Int,
    val oscarNominations: Int,
    val awardsWon: Int,
    val best: FilmFacts?,
    val biggest: FilmFacts?,
    val cast: List<RecurringActor>,
    val directors: List<String>,
    val genres: List<String>,
    val trailers: List<SagaTrailer>,
    val metacritic: Int? = null,
    /** Box office for each dollar spent, on the films where both are known. */
    val returnOnBudget: Double? = null,
    val studios: List<String> = emptyList(),
    /** The directors and how many of the films each one made. */
    val directorPeople: List<RecurringActor> = emptyList(),
    /** Large images of the saga, without duplicates. */
    val gallery: List<String> = emptyList(),
    /** Set when the story comes from another source than TMDB, to credit it. */
    val overviewSource: String? = null,
    val similar: List<TmdbItem> = emptyList(),
)

/** Pure calculations of a saga's facts, tested. */
object SagaFacts {
    private const val MAX_STUDIOS = 4
    private const val MAX_CAST = 16
    private const val MIN_FILMS_FOR_RECURRING = 2

    fun of(overview: String?, films: List<FilmFacts>): SagaProfile {
        val revenues = films.mapNotNull { it.info?.revenue }
        val budgets = films.mapNotNull { it.info?.budget }
        val runtimes = films.mapNotNull { it.info?.runtimeMin?.takeIf { m -> m > 0 } }
        val imdbs = films.mapNotNull { it.omdb?.imdb }
        val critics = films.mapNotNull { it.omdb?.rottenTomatoes }
        return SagaProfile(
            overview = overview?.takeIf { it.isNotBlank() },
            films = films,
            boxOffice = revenues.takeIf { it.isNotEmpty() }?.sum(),
            budget = budgets.takeIf { it.isNotEmpty() }?.sum(),
            runtimeMin = runtimes.takeIf { it.isNotEmpty() }?.sum(),
            imdb = imdbs.takeIf { it.isNotEmpty() }?.average(),
            rottenTomatoes = critics.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            oscarsWon = films.sumOf { it.omdb?.oscarsWon ?: 0 },
            oscarNominations = films.sumOf { it.omdb?.oscarNominations ?: 0 },
            awardsWon = films.sumOf { it.omdb?.awardsWon ?: 0 },
            best = films.filter { it.omdb?.imdb != null }.maxByOrNull { it.omdb?.imdb ?: 0.0 },
            biggest = films.filter { it.info?.revenue != null }.maxByOrNull { it.info?.revenue ?: 0L },
            cast = recurring(films),
            directors = byFrequency(films.mapNotNull { it.info?.director }),
            genres = byFrequency(films.flatMap { it.info?.genres.orEmpty() }),
            trailers = films.mapNotNull { f -> f.info?.trailerVideos()?.firstOrNull()?.let { SagaTrailer(f.film, it) } },
            metacritic = films.mapNotNull { it.omdb?.metacritic }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            returnOnBudget = returnOnBudget(films),
            studios = byFrequency(films.flatMap { it.info?.studios.orEmpty() }).take(MAX_STUDIOS),
            directorPeople = directors(films),
        )
    }

    private fun returnOnBudget(films: List<FilmFacts>): Double? {
        val known = films.mapNotNull { f -> f.info?.let { i -> if (i.revenue != null && i.budget != null) i.revenue!! to i.budget!! else null } }
        val spent = known.sumOf { it.second }
        return if (spent > 0) known.sumOf { it.first }.toDouble() / spent else null
    }

    private fun directors(films: List<FilmFacts>): List<RecurringActor> {
        val seen = LinkedHashMap<Int, RecurringActor>()
        films.forEach { f ->
            f.info?.directors.orEmpty().distinctBy { it.id }.forEach { p ->
                val id = p.id ?: return@forEach
                seen[id] = seen[id]?.let { it.copy(films = it.films + 1) } ?: RecurringActor(p, 1)
            }
        }
        return seen.values.sortedByDescending { it.films }
    }

    private fun byFrequency(values: List<String>) = values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    /** Actors in at least two of the films, the most present first. */
    private fun recurring(films: List<FilmFacts>): List<RecurringActor> {
        val seen = LinkedHashMap<Int, RecurringActor>()
        films.forEach { f ->
            f.info?.cast.orEmpty().distinctBy { it.id }.forEach { p ->
                val id = p.id ?: return@forEach
                seen[id] = seen[id]?.let { it.copy(films = it.films + 1) } ?: RecurringActor(p, 1)
            }
        }
        return seen.values.filter { it.films >= MIN_FILMS_FOR_RECURRING }.sortedByDescending { it.films }.take(MAX_CAST)
    }

    /** "$9.9B" or "9,9 Md$". */
    fun money(dollars: Long, french: Boolean = Strings.pick("en", "fr") == "fr"): String {
        val locale = if (french) Locale.FRENCH else Locale.ENGLISH
        val billions = dollars >= BILLION
        val shown = if (billions) String.format(locale, "%.1f", dollars / BILLION.toDouble()) else String.format(locale, "%.0f", dollars / MILLION.toDouble())
        return when {
            french && billions -> "$shown Md$"
            french -> "$shown M$"
            billions -> "$" + shown + "B"
            else -> "$" + shown + "M"
        }
    }

    private const val BILLION = 1_000_000_000L
    private const val MILLION = 1_000_000L
}
