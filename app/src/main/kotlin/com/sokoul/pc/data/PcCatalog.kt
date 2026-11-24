package com.sokoul.pc.data

import com.sokoul.desktop.data.Catalog
import com.sokoul.desktop.data.CuratedSaga
import com.sokoul.desktop.data.CuratedSagas
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.data.Resume
import com.sokoul.iptv.data.net.TmdbEpisode
import com.sokoul.iptv.data.net.TmdbInfo
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.data.net.TmdbVideo
import com.sokoul.pc.data.providers.EpisodeFill
import com.sokoul.pc.data.providers.TvdbClient
import com.sokoul.pc.data.providers.WatchmodeClient
import com.sokoul.pc.data.providers.FanartClient
import com.sokoul.pc.data.providers.MovieOfTheNightClient
import com.sokoul.pc.data.providers.NextAiring
import com.sokoul.pc.data.providers.ProviderHttp
import com.sokoul.pc.data.providers.TasteDiveClient
import com.sokoul.pc.data.providers.TvmazeClient
import com.sokoul.pc.data.providers.WatchOffer
import com.sokoul.pc.data.providers.WikipediaClient
import com.sokoul.pc.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class HeroItem(val item: TmdbItem, val info: TmdbInfo?)

data class TitleExtras(val omdb: OmdbFacts? = null, val offers: List<WatchOffer> = emptyList(), val next: NextAiring? = null)

data class GenreTile(val id: Int, val kind: String, val name: String, val image: String?)

/** What the screens need from the catalog. Every call returns empty rather than failing: a failed row just goes away. */
interface CatalogApi {
    fun enabled(): Boolean
    val anime: com.sokoul.pc.data.anime.AnimeCatalog? get() = null
    /** Links anime to their ids in the anime databases, for the player and the episode notes. */
    val animeIdentity: com.sokoul.pc.data.anime.AnimeIdentityResolver? get() = null
    suspend fun hero(kind: PageKind): List<HeroItem>
    fun plan(kind: PageKind): List<PageRow>
    suspend fun rowItems(source: RowSource, page: Int = 1): List<TmdbItem>
    /** What a platform produced itself: its series, its films, or both when [kind] is null. */
    suspend fun originals(platform: Platform, kind: String?, page: Int): List<TmdbItem>
    /** What a platform offers by subscription in the user's region, films and series, most popular first. */
    suspend fun available(platform: Platform, kind: String?, page: Int): List<TmdbItem> = originals(platform, kind, page)
    /** "For you", the release calendar and the roulette; null without TMDB. */
    val discover: DiscoverData? get() = null
    fun allSagas(): List<SagaRef>
    suspend fun sagaTile(ref: SagaRef): SagaTile?
    suspend fun person(id: Int): com.sokoul.iptv.data.net.TmdbPersonInfo?
    suspend fun platformLogos(kind: String?): Map<Int, String>
    /** The studios' logos, by TMDB company id. */
    suspend fun studioLogos(): Map<Int, String> = emptyMap()
    /** What a studio made, films and series, the most watched first. */
    suspend fun studio(studio: Studio, page: Int): List<TmdbItem> = emptyList()
    /** One section of a studio's page. */
    suspend fun studioSection(studio: Studio, section: StudioSection, page: Int): List<TmdbItem> = emptyList()
    /** The logos of the services, by name. */
    suspend fun providerLogos(kind: String): Map<String, String> = emptyMap()
    suspend fun collections(): CollectionsPage
    /** Everything known about a saga, gathered from each of its films on TMDB and OMDb. */
    suspend fun sagaProfile(tile: SagaTile): SagaProfile
    /** The saga a title belongs to, if any. */
    suspend fun sagaOfTitle(kind: String, id: Int, info: TmdbInfo?): SagaTile?
    /** Ratings, awards and where to watch: whatever the other services know about a title. */
    suspend fun titleExtras(kind: String, id: Int, info: TmdbInfo?): TitleExtras = TitleExtras()
    suspend fun sagaDetail(tile: SagaTile): SagaDetail
    fun finishedMovies(): Set<Int>
    suspend fun details(kind: String, id: Int): TmdbInfo?
    /** Lighter details for cards: images only. */
    suspend fun artwork(kind: String, id: Int): TmdbInfo?
    suspend fun season(tvId: Int, season: Int): List<TmdbEpisode>
    suspend fun similar(kind: String, id: Int): List<TmdbItem>
    suspend fun search(query: String): List<TmdbItem>
    /** What the search screen shows: more than one page of results. Other callers keep [search], one page. */
    suspend fun searchMore(query: String, onFirstPage: suspend (List<TmdbItem>) -> Unit = {}): List<TmdbItem> =
        search(query).also { onFirstPage(it) }
    suspend fun searchSeries(query: String, onFirstPage: suspend (List<TmdbItem>) -> Unit = {}): List<TmdbItem> =
        searchMore(query) { onFirstPage(it.filter { item -> item.kind() == "tv" }) }.filter { it.kind() == "tv" }
    suspend fun genreName(kind: String, id: Int): String?
    suspend fun genreTiles(kind: String = "movie"): List<GenreTile>
    suspend fun byGenre(kind: String, id: Int, page: Int = 1): List<TmdbItem>
    fun continueWatching(): List<Resume>
    fun resumeOf(kind: String, id: Int): Resume?
    /** The last thing watched of a title, finished or not. */
    fun lastWatched(kind: String, id: Int): Resume? = resumeOf(kind, id)
    /** Every episode begun or finished of a series. */
    fun watchedEpisodes(id: Int): List<Resume> = emptyList()
}

fun TmdbItem.kind(): String = mediaType ?: "movie"

/** Dubbed or re-uploaded by a local broadcaster, whatever language TMDB tagged it with. */
private val LOCALIZED_NAME = Regex("""(?i)\b(vf|vostfr|vost|canal\s*\+?|ocs|bande[- ]annonce|french|fran[cç]ais|dubbed|doubl[eé])\b""")

/**
 * Trailers and teasers, best guess first, whatever the system's language: the film's original language, the one TMDB
 * calls official, a trailer before a teaser, then the highest resolution announced. Local re-uploads come last.
 */
fun TmdbInfo.trailerVideos(): List<TmdbVideo> = TrailerOrder.sort(videos, originalLanguage)

object TrailerOrder {
    fun sort(videos: List<TmdbVideo>, originalLanguage: String?): List<TmdbVideo> {
        val original = originalLanguage?.take(2)?.lowercase()
        fun languageRank(v: TmdbVideo): Int = when {
            original != null && v.language?.lowercase() == original -> 0
            v.language?.lowercase() == "en" -> 1
            else -> 2
        }
        return videos.filter { it.type == "Trailer" || it.type == "Teaser" }.sortedWith(
            compareBy<TmdbVideo>(
                { original != "fr" && LOCALIZED_NAME.containsMatchIn(it.name) },
                ::languageRank,
                { !it.official },
                { it.type != "Trailer" },
                { -(it.size ?: 0) },
            ),
        )
    }
}

fun TmdbInfo.trailerKeys(): List<String> = trailerVideos().map { it.key }.ifEmpty { listOfNotNull(trailerYoutubeKey) }

/** What a row shows: titles with a poster, each once. TMDB lists can repeat a title, and a list needs unique keys. */
fun usableTitles(items: List<TmdbItem>) = items.filter { it.poster != null }.distinctBy { it.id }

class TmdbCatalog(private val db: DesktopDb, private val catalog: Catalog, private val configured: () -> Boolean) : CatalogApi {
    init {
        // Read at each request: titles, summaries and artwork follow the interface language as soon as it changes.
        catalog.tmdb.languageSource = { Strings.catalogLanguage() }
    }

    private val tmdb get() = catalog.tmdb
    private val sagaTiles = ConcurrentHashMap<Int, SagaTile>()
    private val curatedBuilt = ConcurrentHashMap<String, Pair<SagaTile, CollectionUniverse?>>()
    private val omdb = OmdbClient(
        catalog.http,
        { ProviderConfig.key(db, "omdb") },
        { k -> db.tmdbGet(k)?.takeIf { System.currentTimeMillis() - it.second < OMDB_TTL_MS }?.first },
        { k, text -> db.tmdbPut(k, text, System.currentTimeMillis()) },
    )
    private val providers = ProviderHttp(catalog.http, db::tmdbGet, db::tmdbPut)
    private val fanart = FanartClient(providers) { ProviderConfig.key(db, "fanart") }
    private val wikipedia = WikipediaClient(providers) { Strings.isFrench() }
    override val anime by lazy { com.sokoul.pc.data.anime.AnimeCatalog(com.sokoul.pc.data.anime.AniListClient(providers), com.sokoul.pc.data.anime.FanSources(providers), this, db, com.sokoul.pc.data.anime.GhibliClient(providers)) }
    override val animeIdentity by lazy {
        val lists = com.sokoul.pc.data.anime.AnimeLists(java.io.File(com.sokoul.desktop.data.AppDirs.data, "anime"), catalog.http, withNames = !com.sokoul.pc.Platform.IS_TV)
        lists.warmUp()
        val self = this
        com.sokoul.pc.data.anime.AnimeIdentityResolver(
            lists, providers,
            object : com.sokoul.pc.data.anime.AnimeTmdb {
                override suspend fun details(kind: String, id: Int) = self.details(kind, id)
                override suspend fun seasonSize(tvId: Int, season: Int) = self.season(tvId, season).size.takeIf { it > 0 }
                override suspend fun search(query: String) = self.search(query)
            },
            anime.client,
            enabled = { db.setting(ANIME_IDENTITY) != "off" },
        )
    }
    private val motn = MovieOfTheNightClient(providers) { ProviderConfig.key(db, "motn") }
    private val tasteDive = TasteDiveClient(providers) { ProviderConfig.key(db, "tastedive") }
    private val tvmaze = TvmazeClient(providers)
    private val tvdb = TvdbClient(providers) { ProviderConfig.key(db, "tvdb") }
    private val watchmode = WatchmodeClient(providers) { ProviderConfig.key(db, "watchmode") }
    private fun key() = catalog.key()

    override fun enabled() = configured() && catalog.hasKey()

    override suspend fun hero(kind: PageKind): List<HeroItem> = coroutineScope {
        val k = key()
        val lazyAnimeHero = kind == PageKind.Anime && !com.sokoul.pc.Platform.IS_TV
        val source = when (kind) {
            PageKind.Home -> tmdb.trendingDay(k, "all")
            PageKind.Movies -> tmdb.trending(k, "movie")
            PageKind.Series -> tmdb.trending(k, "tv")
            PageKind.Anime -> anime.items(
                com.sokoul.pc.data.anime.AniQuery(
                    com.sokoul.pc.data.anime.MediaKind.ANIME,
                    perPage = if (lazyAnimeHero) PC_ANIME_HERO_CANDIDATES else HERO_COUNT,
                ),
            )
        }
        // An anime matched from AniList often has no French summary in the list, only in its details or in English: it is
        // judged once they are known, or the Anime page had no hero at all and opened on its first row.
        source.filter { it.backdrop != null }.take(HERO_COUNT * 2).map { item ->
            // One title whose details fail must not take the whole hero with it (the page waits for it, and stayed empty).
            async {
                val info = if (lazyAnimeHero) null else runCatching { details(item.kind(), item.id) }
                    .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }
                HeroItem(item, info)
            }
        }.map { it.await() }
            .filter { h -> lazyAnimeHero || !h.item.overview.isNullOrBlank() || !h.info?.overview.isNullOrBlank() || !h.info?.overviewEn.isNullOrBlank() }
            .take(HERO_COUNT)
    }

    override fun plan(kind: PageKind) = PagePlans.of(kind)

    override suspend fun originals(platform: Platform, kind: String?, page: Int): List<TmdbItem> = coroutineScope {
        val k = key()
        val popular = "sort_by" to "popularity.desc"
        val series = async { if (kind == "movie") emptyList() else tmdb.discover(k, "tv", mapOf("with_networks" to platform.networks, popular), page) }
        val films = async {
            val company = platform.companies
            if (kind == "tv" || company == null) emptyList() else tmdb.discover(k, "movie", mapOf("with_companies" to company, popular), page)
        }
        (series.await() + films.await()).filter { it.poster != null }.distinctBy { it.kind() to it.id }
    }

    override val discover by lazy { DiscoverData(db, catalog) }

    override suspend fun available(platform: Platform, kind: String?, page: Int): List<TmdbItem> = coroutineScope {
        val k = key()
        val kinds = listOfNotNull("movie".takeIf { kind != "tv" }, "tv".takeIf { kind != "movie" })
        val lists = kinds.map { type -> async { tmdb.discover(k, type, PlatformCatalog.params(platform, Strings.region()), page) } }
            .map { it.await() }
        PlatformCatalog.interleave(lists).filter { it.poster != null }.distinctBy { it.kind() to it.id }
    }

    override fun allSagas() = AllSagas.all

    override suspend fun sagaTile(ref: SagaRef): SagaTile? {
        sagaTiles[ref.id]?.let { return it }
        val local = tmdb.collection(key(), ref.id) ?: return null
        val english = tmdb.collection(key(), ref.id, "en-US")
        return tile("all-${ref.id}", local.name, local.parts, english?.parts.orEmpty(), matchName = english?.name ?: ref.name)
            .copy(collectionIds = listOf(ref.id), category = ref.category)
            .also { sagaTiles[ref.id] = it }
    }

    override suspend fun person(id: Int) = tmdb.person(key(), id)

    override suspend fun rowItems(source: RowSource, page: Int): List<TmdbItem> {
        val k = key()
        return usableTitles(
            when (source) {
                is RowSource.Trending -> if (page == 1) tmdb.trending(k, source.kind) else emptyList()
                is RowSource.TmdbList -> if (page == 1) tmdb.list(k, source.kind, source.name) else emptyList()
                is RowSource.Fixed -> if (page == 1) source.items else emptyList()
                is RowSource.OnPlatform -> originals(source.platform, source.kind, page)
                is RowSource.ForYou -> if (page == 1) discover.forYou() else emptyList()
                is RowSource.Anime -> anime.items(source.query.copy(page = page))
                is RowSource.AnimeToday -> anime.todayItems()
                is RowSource.Ghibli -> if (page == 1) anime.ghibliItems() else emptyList()
                is RowSource.Spec -> {
                    val ids = source.spec.query.keywords.mapNotNull { tmdb.keywordId(k, it) }
                    // Every keyword unknown to TMDB: without this the row would ignore its theme.
                    if (source.spec.query.keywords.isNotEmpty() && ids.isEmpty()) emptyList()
                    else tmdb.discover(k, source.spec.kind, RowQueries.params(source.spec.query, source.spec.kind, ids), page)
                }
            },
        )
    }

    /** The US list completes the local one: a platform not offered here, like Hulu in France, still has its logo. */
    override suspend fun providerLogos(kind: String): Map<String, String> {
        val type = if (kind == "tv") "tv" else "movie"
        return tmdb.watchProviderNames(key(), type, "US") + tmdb.watchProviderNames(key(), type, Strings.region())
    }

    override suspend fun studioLogos(): Map<Int, String> = coroutineScope {
        val k = key()
        Studio.entries.map { s -> async { s.companyId to tmdb.companyLogo(k, s.companyId) } }.mapNotNull { d -> d.await().let { (id, logo) -> logo?.let { id to it } } }.toMap()
    }

    override suspend fun studio(studio: Studio, page: Int): List<TmdbItem> = coroutineScope {
        val k = key()
        val params = mapOf("with_companies" to studio.companyId.toString(), "sort_by" to "popularity.desc")
        val films = async { tmdb.discover(k, "movie", params, page) }
        val series = async { tmdb.discover(k, "tv", params, page) }
        (films.await() + series.await()).filter { it.poster != null }.distinctBy { it.kind() to it.id }
    }

    override suspend fun studioSection(studio: Studio, section: StudioSection, page: Int): List<TmdbItem> = coroutineScope {
        val k = key()
        val company = "with_companies" to studio.companyId.toString()
        val today = java.time.LocalDate.now().toString()
        suspend fun films(vararg p: Pair<String, String>) = tmdb.discover(k, "movie", mapOf(company, *p), page)
        suspend fun series(vararg p: Pair<String, String>) = tmdb.discover(k, "tv", mapOf(company, *p), page)
        val list = when (section) {
            StudioSection.Popular -> studio(studio, page)
            // Out already, newest first: a date to come would show posters nobody can watch.
            StudioSection.Latest -> {
                val m = async { films("sort_by" to "primary_release_date.desc", "primary_release_date.lte" to today, "vote_count.gte" to "30") }
                val t = async { series("sort_by" to "first_air_date.desc", "first_air_date.lte" to today, "vote_count.gte" to "10") }
                (m.await() + t.await()).sortedByDescending { it.year ?: 0 }
            }
            StudioSection.Acclaimed -> films("sort_by" to "vote_average.desc", "vote_count.gte" to "800")
            StudioSection.Series -> series("sort_by" to "popularity.desc")
            StudioSection.Classics -> films("sort_by" to "vote_count.desc", "primary_release_date.lte" to "1999-12-31")
        }
        list.filter { it.poster != null }.distinctBy { it.kind() to it.id }
    }

    override suspend fun platformLogos(kind: String?): Map<Int, String> {
        val type = kind ?: "movie"
        return tmdb.watchProviderLogos(key(), type, "US") + tmdb.watchProviderLogos(key(), type, Strings.region())
    }

    /** The image comes from the film carrying the saga's name; the logo from the curated list, then from that film. */
    private suspend fun tile(
        key: String,
        name: String,
        films: List<TmdbItem>,
        englishFilms: List<TmdbItem>,
        curatedLogo: String? = null,
        extra: List<Int> = emptyList(),
        series: List<Int> = emptyList(),
        chronological: List<Int> = emptyList(),
        matchName: String = name,
    ): SagaTile {
        val released = films.filter { it.year != null && (it.backdrop != null || it.poster != null) }
        // Names are matched in English, the base language: localized titles rarely carry the saga's name.
        val leadId = CollectionRules.leadFilm(matchName, englishFilms)?.let { englishFilms[it].id }
        val shown = released.firstOrNull { it.id == leadId } ?: released.firstOrNull()
        val info = shown?.let { tmdb.details(key(), "movie", it.id, full = false) }
        // No logo exists for collections on TMDB: the film named like the saga, else the one leading it, else the first.
        val logoSource = englishFilms.getOrNull(CollectionRules.logoFilm(matchName, englishFilms)?.index ?: -1)
            ?: leadId?.let { id -> englishFilms.firstOrNull { it.id == id } }
            ?: englishFilms.filter { it.year != null }.minByOrNull { it.year ?: 9999 }
        val logo = curatedLogo ?: logoSource?.let { source ->
            tmdb.details(key(), "movie", source.id, full = false)?.let { it.logoEn ?: it.logo }
        }
        return SagaTile(
            key, CollectionRules.cleanName(name), released, info?.backdrop ?: shown?.backdrop, info?.cleanPoster ?: shown?.poster,
            logo, extra, series, chronological,
        )
    }

    private suspend fun curated(saga: CuratedSaga): Pair<SagaTile, CollectionUniverse?> = curatedBuilt[saga.key] ?: coroutineScope {
        val k = key()
        val parts = saga.collectionIds.map { id -> async { tmdb.collection(k, id) } }.map { it.await() }.filterNotNull()
        val english = saga.collectionIds.map { id -> async { tmdb.collection(k, id, "en-US") } }.map { it.await() }.filterNotNull()
        val films = parts.flatMap { it.parts }.distinctBy { it.id }.sortedBy { it.year ?: 9999 }
        val englishFilms = english.flatMap { it.parts }.distinctBy { it.id }
        val whole = tile(
            saga.key, Strings.pick(saga.name, saga.nameFr), films, englishFilms, saga.logo,
            saga.extraMovieIds, saga.relatedSeriesTmdbIds, saga.chronologicalOrderIds, matchName = saga.name,
        ).copy(collectionIds = saga.collectionIds, category = AllSagas.curated[saga.key] ?: SagaCategory.Action)
        val universe = if (CollectionRules.isUniverse(saga.collectionIds.size)) {
            CollectionUniverse(
                whole.name,
                parts.map { c ->
                    async {
                        val en = english.firstOrNull { it.id == c.id }
                        tile("${saga.key}-${c.id}", c.name, c.parts, en?.parts.orEmpty(), saga.collectionLogos[c.id], matchName = en?.name ?: c.name)
                            .copy(collectionIds = listOf(c.id), category = whole.category)
                    }
                }.map { it.await() },
            )
        } else {
            null
        }
        (whole to universe).also { if (films.isNotEmpty()) curatedBuilt[saga.key] = it }
    }

    override suspend fun collections(): CollectionsPage = coroutineScope {
        val built = CuratedSagas.all.map { saga -> async { curated(saga) } }.map { it.await() }
        CollectionRules.page(built.map { it.first }, built.mapNotNull { it.second }, AllSagas.all)
    }

    override suspend fun sagaOfTitle(kind: String, id: Int, info: TmdbInfo?): SagaTile? {
        val collection = info?.collectionId
        val saga = CuratedSagas.all.firstOrNull { s ->
            (collection != null && collection in s.collectionIds) ||
                (kind == "movie" && id in s.extraMovieIds) ||
                (kind == "tv" && id in s.relatedSeriesTmdbIds)
        }
        if (saga != null) return curated(saga).first
        if (collection == null) return null
        val ref = AllSagas.all.firstOrNull { it.id == collection } ?: SagaRef(collection, info.collectionName.orEmpty(), 0)
        return sagaTile(ref)?.takeIf { it.films.size >= MIN_SAGA_FILMS }
    }

    override suspend fun sagaProfile(tile: SagaTile): SagaProfile = coroutineScope {
        val k = key()
        val gate = Semaphore(PROFILE_CALLS)
        val facts = sagaDetail(tile).films.map { film ->
            async {
                gate.withPermit {
                    val info = tmdb.details(k, "movie", film.id, full = true)
                    FilmFacts(film, info, info?.imdbId?.let { imdb -> withContext(Dispatchers.IO) { omdb.facts(imdb) } })
                }
            }
        }.map { it.await() }
        fun clean(text: String?) = text?.takeIf { it.isNotBlank() }
        val collections = tile.collectionIds.mapNotNull { tmdb.collection(k, it) }
        val localStory = collections.firstNotNullOfOrNull { clean(it.overview) }
        val englishStory = if (localStory == null) tile.collectionIds.firstNotNullOfOrNull { clean(tmdb.collection(k, it, "en-US")?.overview) } else null
        // TMDB often has only a line: the encyclopedia tells more, when a page really carries the saga's name. The name
        // kept here is often the English one, and the page is under the name people know locally.
        val richer = if ((localStory?.length ?: 0) < RICH_STORY_LENGTH) {
            (listOf(tile.name) + collections.map { SagaNames.plain(it.name) }).distinct().firstNotNullOfOrNull { wikipedia.summary(it) }
        } else null
        val firstFilm = tile.films.sortedBy { it.year ?: 9999 }.firstNotNullOfOrNull { clean(it.overview) }
        val overview = when {
            richer != null && richer.length > (localStory?.length ?: 0) -> richer
            localStory != null && localStory.length >= POOR_STORY_LENGTH -> localStory
            // A line saying nothing ("The Cocoon film series"), or text in another language: the first film tells the start.
            firstFilm != null -> firstFilm
            else -> localStory ?: englishStory
        }
        val images = async { gallery(tile, facts.map { it.film }) }
        val lead = (facts.maxByOrNull { it.film.voteCount ?: 0 })?.film
        val similar = async { lead?.let { similarTitles(it) }.orEmpty() }
        SagaFacts.of(overview, facts).copy(
            gallery = images.await(),
            overviewSource = if (overview == richer && richer != null) "Wikipedia" else null,
            similar = similar.await().filter { s -> facts.none { it.film.id == s.id } },
        )
    }

    /** Images of the collection and its films from TMDB, then fanart.tv's, which are often the finest. */
    private suspend fun gallery(tile: SagaTile, films: List<TmdbItem>): List<String> = coroutineScope {
        val k = key()
        val fromCollections = tile.collectionIds.map { id -> async { tmdb.backdrops(k, "collection/$id", GALLERY_PER_SOURCE) } }
        val gate = Semaphore(PROFILE_CALLS)
        val fromFilms = films.take(GALLERY_FILMS).map { f ->
            async {
                gate.withPermit {
                    val art = fanart.movie(f.id)
                    art?.backgrounds.orEmpty().take(2) + tmdb.backdrops(k, "movie/${f.id}", 2)
                }
            }
        }
        (fromCollections.map { it.await() }.flatten() + fromFilms.map { it.await() }.flatten()).distinct().take(GALLERY_SIZE)
    }

    /** TMDB's recommendations, with TasteDive's for what they do not cover. */
    private suspend fun similarTitles(lead: TmdbItem): List<TmdbItem> {
        val k = key()
        val fromTmdb = usableTitles(tmdb.recommendations(k, "movie", lead.id))
        val names = tasteDive.similar(lead.originalTitle ?: lead.title, "movie").take(TASTE_NAMES)
        val fromTaste = coroutineScope { names.map { n -> async { tmdb.findByName(k, "movie", n) } }.map { it.await() } }.filterNotNull()
        return usableTitles(fromTaste + fromTmdb).take(SIMILAR_SIZE)
    }

    override suspend fun titleExtras(kind: String, id: Int, info: TmdbInfo?): TitleExtras = coroutineScope {
        val imdb = info?.imdbId
        val ratings = async { imdb?.let { withContext(Dispatchers.IO) { omdb.facts(it) } } }
        val offers = async { motn.offers(kind, id, Strings.region()).ifEmpty { watchmode.offers(kind, id, Strings.region()) } }
        val next = async { if (kind == "tv") tvmaze.next(imdb) else null }
        TitleExtras(ratings.await(), offers.await(), next.await())
    }

    override suspend fun sagaDetail(tile: SagaTile): SagaDetail = coroutineScope {
        val k = key()
        fun item(kind: String, id: Int, info: TmdbInfo) = TmdbItem(id, info.title, info.originalTitle, info.year, info.poster, info.backdrop, info.rating, info.overview, info.genreIds, info.originalLanguage, info.voteCount, kind)
        val extra = tile.extraMovieIds.map { id -> async { tmdb.details(k, "movie", id, full = false)?.let { item("movie", id, it) } } }
        val series = tile.seriesIds.map { id -> async { tmdb.details(k, "tv", id, full = false)?.let { item("tv", id, it) } } }
        SagaDetail((tile.films + extra.mapNotNull { it.await() }).distinctBy { it.id }, series.mapNotNull { it.await() })
    }

    override fun finishedMovies(): Set<Int> = db.finishedMovies()

    override suspend fun details(kind: String, id: Int) = tmdb.details(key(), kind, id, full = true)?.let { withOriginalVideos(kind, id, it) }

    /** TMDB only sends the videos of the languages asked for: a Korean film's Korean trailer takes its own request. */
    private suspend fun withOriginalVideos(kind: String, id: Int, info: TmdbInfo): TmdbInfo {
        val original = info.originalLanguage?.take(2)?.lowercase() ?: return info
        if (info.videos.any { it.language?.lowercase() == original && it.type == "Trailer" }) return info
        val more = tmdb.videos(key(), kind, id, original)
        return if (more.isEmpty()) info else info.copy(videos = (more + info.videos).distinctBy { it.key })
    }

    override suspend fun artwork(kind: String, id: Int) = tmdb.details(key(), kind, id, full = false)

    /** TMDB's episodes, with TheTVDB's texts and images for what they lack: often the case in French. */
    override suspend fun season(tvId: Int, season: Int): List<TmdbEpisode> {
        val episodes = tmdb.season(key(), tvId, season)
        // Match text enrichment to the original segment numbers before renumbering broadcast episodes.
        val filled = if (EpisodeFill.needed(episodes)) {
            val tvdbId = tmdb.details(key(), "tv", tvId, full = true)?.tvdbId
            if (tvdbId == null) episodes else EpisodeFill.fill(episodes, tvdb.episodes(tvdbId, if (Strings.isFrench()) "fra" else "eng"))
        } else episodes
        val fallback = if (SegmentedSeasons.enabled(tvId, season) && SegmentedSeasons.needsFallback(filled)) {
            tmdb.season(key(), tvId, season, "en-US")
        } else emptyList()
        return SegmentedSeasons.season(tvId, season, filled, fallback)
    }

    override suspend fun similar(kind: String, id: Int) = usableTitles(tmdb.recommendations(key(), kind, id))

    override suspend fun search(query: String) = tmdb.searchMulti(key(), query).filter { it.poster != null }.distinctBy { it.kind() to it.id }

    override suspend fun searchMore(query: String, onFirstPage: suspend (List<TmdbItem>) -> Unit): List<TmdbItem> =
        titleSearch(query, seriesOnly = false, onFirstPage = onFirstPage)

    override suspend fun searchSeries(query: String, onFirstPage: suspend (List<TmdbItem>) -> Unit): List<TmdbItem> =
        titleSearch(query, seriesOnly = true, onFirstPage = onFirstPage)

    private suspend fun titleSearch(query: String, seriesOnly: Boolean, onFirstPage: suspend (List<TmdbItem>) -> Unit): List<TmdbItem> = coroutineScope {
        val apiKey = key()
        val first = tmdb.searchPage(apiKey, query, 1, seriesOnly)
        onFirstPage(first.items.distinctBy { it.kind() to it.id })
        val more = (2..minOf(first.totalPages, SEARCH_PAGES)).map { page ->
            async { tmdb.searchPage(apiKey, query, page, seriesOnly).items }
        }.awaitAll().flatten()
        (first.items + more).distinctBy { it.kind() to it.id }
    }

    override suspend fun genreName(kind: String, id: Int) = tmdb.genres(key(), kind)[id]

    override suspend fun genreTiles(kind: String): List<GenreTile> = coroutineScope {
        val asked = tmdb.genres(key(), kind).entries.take(GENRE_TILES).map { (id, name) ->
            async { Triple(id, name, tmdb.discover(key(), kind, mapOf("with_genres" to "$id", "sort_by" to "popularity.desc")).filter { it.poster != null }) }
        }.map { it.await() }
        // A title that tops two genres (a crime film that is also an action film) shows on the first one only: the next
        // genre takes its own best poster, so no two tiles of the row show the same picture.
        val taken = HashSet<Int>()
        asked.map { (id, name, titles) ->
            val pick = titles.firstOrNull { taken.add(it.id) }
            GenreTile(id, kind, name, pick?.poster)
        }
    }

    override suspend fun byGenre(kind: String, id: Int, page: Int) =
        usableTitles(tmdb.discover(key(), kind, mapOf("with_genres" to "$id", "sort_by" to "popularity.desc"), page))

    // Asked again by the home page while the window closes: the database may already be shut, which is no crash.
    override fun continueWatching(): List<Resume> =
        runCatching { db.resumeList(CONTINUE_COUNT) }.getOrElse { if (it is java.sql.SQLException) emptyList() else throw it }
            .filter { !it.finished && it.durationMs > 0 }

    override fun watchedEpisodes(id: Int): List<Resume> = db.allOf("tv", id)
    override fun lastWatched(kind: String, id: Int): Resume? = db.lastOf(kind, id)?.takeIf { it.finished || it.positionMs > 30_000 }
    override fun resumeOf(kind: String, id: Int): Resume? = db.lastOf(kind, id)?.takeIf { !it.finished && it.positionMs > 30_000 }

    private companion object {
        /** "off" puts every title back on the plain IMDb search, as before the anime ids. */
        const val ANIME_IDENTITY = "pc.anime.identity"
        const val HERO_COUNT = 8
        /** Pages of TMDB search results the search screen asks for. */
        const val SEARCH_PAGES = 3
        /** Keep the PC Anime hero responsive; slide details load only for the slide currently on screen. */
        private const val PC_ANIME_HERO_CANDIDATES = 4
        const val CONTINUE_COUNT = 15
        const val GENRE_TILES = 16
        const val RICH_STORY_LENGTH = 300
        const val POOR_STORY_LENGTH = 60
        const val GALLERY_PER_SOURCE = 8
        const val GALLERY_FILMS = 10
        const val GALLERY_SIZE = 24
        const val TASTE_NAMES = 8
        const val SIMILAR_SIZE = 20
        const val PROFILE_CALLS = 6
        const val MIN_SAGA_FILMS = 2
        const val OMDB_TTL_MS = 30L * 24 * 3_600_000
    }
}
