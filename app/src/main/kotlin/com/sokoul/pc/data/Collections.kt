package com.sokoul.pc.data

import com.sokoul.iptv.data.net.TmdbItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/** A saga or franchise as a tile. The logo stays null unless a film really carries the saga's name. */
data class SagaTile(
    val key: String,
    val name: String,
    val films: List<TmdbItem>,
    val backdrop: String?,
    val poster: String?,
    val logo: String?,
    val extraMovieIds: List<Int> = emptyList(),
    val seriesIds: List<Int> = emptyList(),
    /** Films in the order of the story. Empty when it is the same as the release order. */
    val chronological: List<Int> = emptyList(),
    val collectionIds: List<Int> = emptyList(),
    val category: SagaCategory = SagaCategory.Action,
)

/** A saga on a shelf: already built, or known by its id until it comes into view. */
data class SagaEntry(val key: String, val tile: SagaTile? = null, val ref: SagaRef? = null) {
    constructor(tile: SagaTile) : this(tile.key, tile = tile)
    constructor(ref: SagaRef) : this("all-${ref.id}", ref = ref)

    suspend fun load(catalog: CatalogApi): SagaTile? = tile ?: ref?.let { catalog.sagaTile(it) }
}

/** One category of the collections page, its most voted sagas first. */
data class SagaShelf(val category: SagaCategory, val entries: List<SagaEntry>)

data class SagaDetail(val films: List<TmdbItem>, val series: List<TmdbItem>)

/** What the saga page's main button offers. */
sealed interface SagaAction {
    data object Start : SagaAction
    data class Resume(val film: TmdbItem) : SagaAction
    data class Next(val film: TmdbItem) : SagaAction
    data object Rewatch : SagaAction
}

/** Viewing order of a saga. Pure and tested. */
object SagaNames {
    private val WORDS = Regex("""(?i)\s*[-–:]?\s*\b(la saga|saga|collection|série de films|film series)\b\s*[-–:]?\s*""")

    /** "Moi, moche et méchant - Saga" gives "Moi, moche et méchant". */
    fun plain(name: String): String = name.replace(WORDS, " ").trim()
}

object SagaTimeline {
    /** Release order, or story order with any film it doesn't list placed after. */
    fun ordered(films: List<TmdbItem>, chronological: List<Int>, story: Boolean): List<TmdbItem> {
        val byYear = films.sortedWith(compareBy<TmdbItem> { it.year ?: 9999 }.thenBy { it.id })
        if (!story || chronological.isEmpty()) return byYear
        val rank = chronological.withIndex().associate { (i, id) -> id to i }
        return byYear.sortedBy { rank[it.id] ?: (chronological.size + byYear.indexOf(it)) }
    }

    /** First released film not seen to the end. */
    fun next(ordered: List<TmdbItem>, finished: Set<Int>, thisYear: Int = java.time.Year.now().value): TmdbItem? =
        ordered.firstOrNull { it.id !in finished && (it.year ?: 9999) <= thisYear }

    fun action(ordered: List<TmdbItem>, finished: Set<Int>, inProgress: TmdbItem?, next: TmdbItem?): SagaAction = when {
        inProgress != null -> SagaAction.Resume(inProgress)
        ordered.none { it.id in finished } -> SagaAction.Start
        next != null -> SagaAction.Next(next)
        else -> SagaAction.Rewatch
    }

}

/** A franchise made of several sagas, shown as its own row. */
data class CollectionUniverse(val name: String, val tiles: List<SagaTile>)

data class CollectionsPage(
    val featured: List<SagaTile>,
    val spotlight: SagaTile?,
    val universes: List<CollectionUniverse>,
    val shelves: List<SagaShelf>,
    val curated: List<SagaTile>,
)

/** A saga known only by its id until its tile comes into view. */
data class SagaRef(val id: Int, val name: String, val films: Int, val category: SagaCategory = SagaCategory.Action)

/** Every saga found on TMDB beyond the curated ones, most voted first (`all_sagas.json`, built by dev/SagaHarvest). */
object AllSagas {
    private val text: String by lazy {
        runCatching { AllSagas::class.java.getResourceAsStream("/all_sagas.json")?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty()
    }

    val all: List<SagaRef> by lazy { runCatching { parse(text) }.getOrDefault(emptyList()) }

    /** Categories of the curated sagas, by their key. */
    val curated: Map<String, SagaCategory> by lazy {
        runCatching {
            ((Json.parseToJsonElement(text) as? JsonObject)?.get("curated") as? JsonObject).orEmpty().mapValues { SagaCategory.named(it.value.jsonPrimitive.contentOrNull) }
        }.getOrDefault(emptyMap())
    }

    fun parse(text: String): List<SagaRef> =
        ((Json.parseToJsonElement(text) as? JsonObject)?.get("sagas") as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            SagaRef(x["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null, x["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), x["films"]?.jsonPrimitive?.intOrNull ?: 0, SagaCategory.named(x["category"]?.jsonPrimitive?.contentOrNull))
        }
}

/** What an actor's page lists. Pure and tested. */
object PersonCredits {
    /** Everything they appear in or directed, with a poster, each once, best known first. */
    fun of(person: com.sokoul.iptv.data.net.TmdbPersonInfo): List<TmdbItem> =
        (person.credits + person.directed).filter { it.poster != null }.distinctBy { it.kind() to it.id }.sortedByDescending { it.voteCount ?: 0 }
}

data class LogoSource(val index: Int, val english: Boolean)

/** Pure rules of the collections page, tested. */
object CollectionRules {
    const val FEATURED_COUNT = 8
    private const val UNIVERSE_MIN_COLLECTIONS = 3
    private val trailingWord = Regex("""(?i)\s*[-–:]?\s*(saga|collection)\s*$""")
    private val leadingWord = Regex("""(?i)^(la |les |the )?(saga|collection)\s+(des |de |du |d'|of )?""")

    /** "Iron Man - Saga" → "Iron Man", "La saga Rocky" → "Rocky". */
    fun cleanName(name: String): String = name.replace(trailingWord, "").replace(leadingWord, "").trim().ifEmpty { name }

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /** Index of the film whose logo can stand for [name]: its title starts with the saga's name, or contains it. */
    fun leadFilm(name: String, films: List<TmdbItem>): Int? {
        val wanted = normalized(cleanName(name))
        if (wanted.isEmpty()) return null
        fun titles(film: TmdbItem) = listOfNotNull(film.title, film.originalTitle).map(::normalized)
        val starting = films.indexOfFirst { film -> titles(film).any { it.startsWith(wanted) } }
        if (starting >= 0) return starting
        return films.indexOfFirst { film -> titles(film).any { it.contains(wanted) } }.takeIf { it >= 0 }
    }

    /** The film whose logo is the saga's name exactly. English when only its original title matches. */
    fun logoFilm(name: String, films: List<TmdbItem>): LogoSource? {
        val wanted = normalized(cleanName(name))
        if (wanted.isEmpty()) return null
        val original = films.indexOfFirst { normalized(it.originalTitle.orEmpty()) == wanted }
        if (original >= 0) return LogoSource(original, english = true)
        val local = films.indexOfFirst { normalized(it.title) == wanted }
        return if (local >= 0) LogoSource(local, english = false) else null
    }

    fun isUniverse(collectionCount: Int) = collectionCount >= UNIVERSE_MIN_COLLECTIONS

    /** Same order as the data: the first sagas are featured, the first of them in the spotlight; all are shelved by category. */
    fun page(tiles: List<SagaTile>, universes: List<CollectionUniverse>, refs: List<SagaRef> = emptyList()): CollectionsPage {
        val universeNames = universes.map { it.name }.toSet()
        val usable = tiles.filter { it.films.isNotEmpty() && it.name !in universeNames }
        val featured = usable.take(FEATURED_COUNT)
        val shelves = SagaCategory.entries.mapNotNull { category ->
            val entries = usable.filter { it.category == category }.map { SagaEntry(it) } + refs.filter { it.category == category }.map { SagaEntry(it) }
            if (entries.isEmpty()) null else SagaShelf(category, entries)
        }
        return CollectionsPage(featured, featured.firstOrNull(), universes.filter { it.tiles.isNotEmpty() }, shelves, usable)
    }
}
