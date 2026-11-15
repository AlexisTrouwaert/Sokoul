package com.sokoul.pc.data.anime

import com.sokoul.iptv.data.anime.EpisodeMapper
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.CatalogApi
import com.sokoul.pc.i18n.Strings
import java.text.Normalizer
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import com.sokoul.pc.data.kind
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit

/** A kind of story the categories are sorted into. */
enum class CategoryGroup { Genres, Audience, Characters, Worlds, Action, Fantasy, SciFi, Drama, Comedy, Romance, SliceOfLife, Sports, Arts, Society }

data class AniCategory(val name: String, val group: CategoryGroup, val isGenre: Boolean)

/** Every category that exists for anime and manga, in the viewer's language. */
object AnimeCategories {
    /** Never offered, even when the database does not call them adult. */
    val HIDDEN = setOf("Hentai", "Incest", "Psychosexual", "Ero Guro", "Nudity")

    val GENRES = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
        "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
    )

    fun groupOf(anilistCategory: String): CategoryGroup = when {
        anilistCategory == "Demographic" -> CategoryGroup.Audience
        anilistCategory.startsWith("Cast") -> CategoryGroup.Characters
        anilistCategory.startsWith("Setting") -> CategoryGroup.Worlds
        anilistCategory.startsWith("Theme-Action") -> CategoryGroup.Action
        anilistCategory.startsWith("Theme-Fantasy") -> CategoryGroup.Fantasy
        anilistCategory.startsWith("Theme-Sci-Fi") -> CategoryGroup.SciFi
        anilistCategory.startsWith("Theme-Drama") -> CategoryGroup.Drama
        anilistCategory.startsWith("Theme-Comedy") -> CategoryGroup.Comedy
        anilistCategory.startsWith("Theme-Romance") -> CategoryGroup.Romance
        anilistCategory.startsWith("Theme-Slice") -> CategoryGroup.SliceOfLife
        anilistCategory.startsWith("Theme-Game") -> CategoryGroup.Sports
        anilistCategory.startsWith("Theme-Arts") -> CategoryGroup.Arts
        else -> CategoryGroup.Society
    }

    fun all(tags: List<AniTag>): List<AniCategory> =
        GENRES.map { AniCategory(it, CategoryGroup.Genres, true) } + tags.map { AniCategory(it.name, groupOf(it.group), false) }

    private val french: Map<String, String> by lazy {
        AnimeCategories::class.java.getResourceAsStream("/anime_categories_fr.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }.orEmpty()
    }

    /** The French name when the system is French. A name without translation stays in English. */
    fun label(name: String, frenchSystem: Boolean = Strings.isFrench()): String = if (frenchSystem) french[name] ?: name else name

    /** "isek" finds Isekai, "epee" finds what is called épée: accents do not count. */
    fun matches(category: AniCategory, query: String): Boolean {
        val q = plain(query)
        return q.isEmpty() || plain(category.name).contains(q) || plain(label(category.name)).contains(q)
    }

    fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
}

/** A row of the page: its name, and what it asks for. */
data class AniRow(val key: String, val name: (Boolean) -> String, val query: AniQuery)

/** Anime and manga from AniList, matched with the app's catalog so that they open and play like any other title. */
class AnimeCatalog(val client: AniListClient, val fans: FanSources, private val catalog: CatalogApi, private val db: com.sokoul.desktop.data.DesktopDb? = null,
    private val ghibli: GhibliClient? = null) {
    private val matching = AnimeMatchCache()
    private val works = ConcurrentHashMap<Int, AniTitle>()
    private val links = ConcurrentHashMap<Int, AnimeLink>()
    private val mangaIds = ConcurrentHashMap<Int, String>()

    /** A row of the page, as the same items as every other row: same cards, same logos, same page when opened. */
    suspend fun items(q: AniQuery): List<TmdbItem> = resolveTitles(client.titles(q))

    private suspend fun resolveTitles(titles: List<AniTitle>): List<TmdbItem> = kotlinx.coroutines.coroutineScope {
        titles.map { t ->
            async { match(t)?.also { works[it.id] = t } }
        }.mapNotNull { it.await() }.distinctBy { it.id }
    }

    suspend fun todayItems(): List<TmdbItem> {
        val zone = java.time.ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val found = client.schedule(day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), 1)
        return resolveTitles(found.sortedByDescending { it.title.popularity ?: 0 }.take(TODAY_ROW).map { it.title })
    }

    suspend fun ghibliItems(): List<TmdbItem> = kotlinx.coroutines.coroutineScope {
        ghibli?.films().orEmpty().map { film ->
            async {
                matching.resolve("ghibli:${film.id}") {
                    try { film.match(catalog.search(film.title)) }
                    catch (c: kotlinx.coroutines.CancellationException) { throw c }
                    catch (_: Exception) { null }
                }
            }
        }.mapNotNull { it.await() }.distinctBy { it.id }
    }

    /** Its title page, for a work or for the anime a book became. */
    suspend fun titleOf(t: AniTitle): TmdbItem? {
        if (t.kind == MediaKind.ANIME) return match(t)
        val adaptations = runCatching { client.detail(t.id) }.getOrNull()?.relations.orEmpty().filter { it.title.kind == MediaKind.ANIME && it.type == "ADAPTATION" }.map { it.title }
        // The series, not the first adaptation listed: One Piece's book listed a TV special first, so its card opened a
        // special instead of the series and its episodes.
        val anime = AnimeMatch.mainAdaptation(adaptations)
        return anime?.let { a -> match(a)?.also { works[it.id] = a } }
    }

    /** The link of a title already opened: what the player knows without asking anyone. */
    fun linkKnown(tmdbId: Int): AnimeLink? = links[tmdbId]

    /** What links a title of the catalog to the anime databases. Null for anything else than animation from Asia. */
    suspend fun link(item: TmdbItem, originalLanguage: String?): AnimeLink? {
        links[item.id]?.let { return it }
        val known = works[item.id]
        if (known == null && originalLanguage !in ASIAN_ANIMATION) return null
        // No genres known (a title opened from search, My list or Continue watching): the language alone decides, and
        // AniList's answer below. Before, such a title lost its whole anime part.
        if (known == null && item.genreIds.isNotEmpty() && ANIMATION_GENRE !in item.genreIds) return null
        // By its ids first: the name alone opened the neighbour ("Demon Slayer" found "Onigiri").
        val byId = if (known == null) catalog.animeIdentity?.let { a -> runCatching { a.identity(item.kind(), item.id) }.getOrNull() }
            ?.main?.anilist?.let { id -> runCatching { client.byId(id) }.getOrNull() } else null
        val work = known ?: byId ?: listOfNotNull(item.originalTitle, item.title).firstNotNullOfOrNull { runCatching { client.find(it, MediaKind.ANIME) }.getOrNull() } ?: return null
        val episodes: suspend () -> Int = {
            if (item.kind() != "tv") 0 else (1..(catalog.details("tv", item.id)?.seasons ?: 1).coerceAtLeast(1)).sumOf { s -> runCatching { catalog.season(item.id, s).size }.getOrDefault(0) }
        }
        return linkOf(work, episodes).also { links[item.id] = it }
    }

    suspend fun linkOf(t: AniTitle, episodesInCatalog: (suspend () -> Int)? = null): AnimeLink {
        val detail = runCatching { client.detail(t.id) }.getOrNull()
        val relations = detail?.relations.orEmpty()
        val book = if (t.kind == MediaKind.MANGA) t else AnimeMatch.bookOf(relations)
            ?: runCatching { client.find(t.romaji ?: t.title, MediaKind.MANGA) }.getOrNull()?.takeIf { sameName(it, t) }
        val bookLinks = if (book == null) emptyList() else runCatching { client.detail(book.id).links }.getOrDefault(emptyList()).filter { it.site.isNotBlank() }.distinctBy { it.url }
        val chain = if (t.kind == MediaKind.ANIME) seasonsOf(t, episodesInCatalog) else emptyList()
        val themes = chain.flatMapIndexed { i, s -> runCatching { fans.themes(s.id) }.getOrDefault(emptyList()).map { it.copy(season = i) } }.distinctBy { it.video }
        val adaptations = relations.filter { it.title.kind == MediaKind.ANIME }.map { it.title }.distinctBy { it.id }
        return AnimeLink(t, book, bookLinks, themes, chain, adaptations,
            detail?.links.orEmpty().filter { it.type == "STREAMING" }.distinctBy { it.url })
    }

    /** The anime and its sequels, in order: each is one season here. */
    private suspend fun seasonsOf(first: AniTitle, episodesInCatalog: (suspend () -> Int)?): List<AniTitle> {
        val chain = mutableListOf(first)
        var at = first
        var limit: Int? = null
        repeat(MAX_SEASONS) {
            val next = runCatching { client.detail(at.id) }.getOrNull()?.relations.orEmpty()
                .firstOrNull { it.type == "SEQUEL" && it.title.kind == MediaKind.ANIME && it.title.format in setOf("TV", "TV_SHORT", "ONA") }?.title ?: return chain
            if (chain.any { it.id == next.id }) return chain
            // Here a sequel can be another series altogether: Shippuden is not in Naruto.
            if (episodesInCatalog != null) {
                val total = limit ?: episodesInCatalog().also { limit = it }
                if (chain.sumOf { it.episodes ?: 0 } >= total) return chain
            }
            chain += next
            at = next
        }
        return chain
    }

    private fun sameName(a: AniTitle, b: AniTitle) = listOfNotNull(a.english, a.romaji).map(AnimeCategories::plain).any { n -> listOfNotNull(b.english, b.romaji).map(AnimeCategories::plain).any { it.startsWith(n) || n.startsWith(it) } }

    /** Where a series' wiki lives: the name of the work, as fans write it. */
    fun wikiOf(t: AniTitle): String? = WikiNames.of(t)

    /** The chapters an episode adapts, by its number since the first season. */
    suspend fun chaptersOf(link: AnimeLink, absoluteEpisode: Int): List<Int> {
        val wiki = wikiOf(link.anime) ?: return emptyList()
        return runCatching { fans.chaptersOf(wiki, absoluteEpisode) }.getOrDefault(emptyList())
    }

    suspend fun episodeKinds(link: AnimeLink): Map<Int, EpisodeKind> =
        runCatching { fans.episodeKinds(listOfNotNull(link.anime.english, link.anime.romaji)) }.getOrDefault(emptyMap())

    /** Its number since the first season, by the one numbering of the app. Null when a season before is of unknown size. */
    suspend fun absoluteEpisode(tmdbId: Int, season: Int, episode: Int): Int? =
        EpisodeMapper.absolute(season, episode, (1..season).map { s -> runCatching { catalog.season(tmdbId, s).size }.getOrDefault(0) })

    /** How many episodes come before this season, or null when one of them is of unknown size. */
    suspend fun seasonOffset(tmdbId: Int, season: Int): Int? = absoluteEpisode(tmdbId, season, 1)?.minus(1)

    /** The episode last watched, and the chapter the story goes on at. */
    suspend fun chapterAfterWatched(item: TmdbItem, link: AnimeLink): Pair<Int, Int>? {
        val resume = catalog.lastWatched("tv", item.id) ?: return null
        val absolute = absoluteEpisode(item.id, resume.season ?: 1, resume.episode ?: return null) ?: return null
        val last = chaptersOf(link, absolute).maxOrNull() ?: return null
        return absolute to last + 1
    }

    suspend fun chaptersOf(book: AniTitle): List<MangaChapter> {
        val id = mangaIds[book.id] ?: fans.mangadexId(book.id, listOfNotNull(book.romaji, book.english))?.also { mangaIds[book.id] = it } ?: return emptyList()
        val all = fans.chapters(id, languages())
        // A book whose publisher holds it (One Piece) keeps one or two chapters here, and MangaDex still lists some whose
        // pages it took down. For such a short list, ask for the pages: a chapter that has none is not offered.
        if (all.size > CHECKED_WHEN_AT_MOST) return all
        return all.filter { c -> runCatching { fans.pages(c.id).isNotEmpty() }.getOrDefault(true) }
    }

    private fun languages() = if (Strings.isFrench()) listOf("fr", "en") else listOf("en", "fr")

    fun readerRequest(book: AniTitle, chapters: List<MangaChapter>, chapter: Int, page: Int) = ReaderRequest(
        book.id.toString(), book.title, book.cover,
        chapters.map { c ->
            ReadChapter(
                c.id,
                ReaderLogic.chapterLabel(c.number, c.title),
                c.number,
                listOfNotNull(c.group, c.language.uppercase()).joinToString(" · ").ifEmpty { null },
            ) { fans.pages(c.id).map { ReadPage.Url(it) } }
        },
        chapter, page,
        if (book.country == "KR" || book.country == "CN") ReaderMode.Strip else ReaderMode.Single,
        rightToLeft = book.country != "KR" && book.country != "CN",
    )

    /** One's own comic: a CBZ or ZIP read in place. */
    suspend fun readerFromFile(file: java.io.File): ReaderRequest? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val names = runCatching { java.util.zip.ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.toList() } }.getOrNull() ?: return@withContext null
        val images = ReaderLogic.imagesInOrder(names)
        if (images.isEmpty()) return@withContext null
        val chapter = ReadChapter(file.path, file.nameWithoutExtension, null, null) {
            images.map { name -> ReadPage.File(name) { java.util.zip.ZipFile(file).use { z -> z.getInputStream(z.getEntry(name)).readBytes() } } }
        }
        ReaderRequest("file:" + file.name, file.nameWithoutExtension, null, listOf(chapter), 0, readingOf("file:" + file.name)?.page ?: 0, ReaderMode.Single, rightToLeft = true)
    }

    // ---- How one likes to read
    fun readerPrefs(): ReaderPrefs? = db?.setting("pc.manga.reader")?.split(',')?.takeIf { it.size == 3 }?.let { p ->
        ReaderPrefs(ReaderMode.entries.firstOrNull { it.name == p[0] }, ReaderFit.entries.firstOrNull { it.name == p[1] }, p[2].toIntOrNull() ?: 0)
    }

    fun saveReaderPrefs(mode: ReaderMode, fit: ReaderFit, dim: Int) {
        // The long strip belongs to the book that needs it, not to the next one.
        if (mode != ReaderMode.Strip) db?.putSetting("pc.manga.reader", "${mode.name},${fit.name},$dim")
    }

    // ---- Where one stopped
    fun readingOf(bookId: Int) = readingOf(bookId.toString())

    fun readingOf(key: String): ReadingPlace? = db?.setting("pc.manga.$key")?.split('\t')?.takeIf { it.size >= 2 }?.let { ReadingPlace(it[0], it[1].toIntOrNull() ?: 0) }

    fun saveReading(request: ReaderRequest, chapter: ReadChapter, page: Int, total: Int) {
        val d = db ?: return
        d.putSetting("pc.manga.${request.key}", "${chapter.number.orEmpty()}\t$page")
        val line = listOf(request.key, request.title, request.cover.orEmpty(), chapter.label, ((page + 1) * 100 / total.coerceAtLeast(1)).toString()).joinToString("\t")
        val others = d.setting(READING_LIST)?.lines().orEmpty().filter { it.isNotBlank() && !it.startsWith(request.key + "\t") }
        d.putSetting(READING_LIST, (listOf(line) + others).take(MAX_READING).joinToString("\n"))
    }

    /** Forgets what was being read. Only tools and tests use it, to leave no trace of their own reading. */
    fun forgetReading(keys: Set<String>? = null) {
        val d = db ?: return
        val kept = if (keys == null) emptyList() else d.setting(READING_LIST)?.lines().orEmpty().filter { l -> l.substringBefore('\t') !in keys }
        d.putSetting(READING_LIST, kept.joinToString("\n"))
    }

    fun readingList(): List<ReadingEntry> = db?.setting(READING_LIST)?.lines().orEmpty().mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 5) null else ReadingEntry(p[0], p[1], p[2].ifEmpty { null }, p[3], p[4].toIntOrNull() ?: 0)
    }

    fun rows(kind: MediaKind, today: LocalDate = LocalDate.now()): List<AniRow> {
        val (season, year) = AnimeSeasons.of(today)
        val (nextSeason, nextYear) = AnimeSeasons.next(today)
        fun row(key: String, en: String, fr: String, q: AniQuery) = AniRow(key, { french -> if (french) fr else en }, q)
        fun theme(tag: String, en: String, fr: String) = row("tag-$tag", en, fr, AniQuery(kind, sort = "POPULARITY_DESC", tag = tag))
        val common = listOf(
            theme("Isekai", "Another world", "Un autre monde"),
            theme("Iyashikei", "Soft and soothing", "Doux et apaisant"),
            theme("Found Family", "Found family", "Une famille choisie"),
            theme("Anti-Hero", "Anti-heroes", "Anti-héros"),
            theme("Death Game", "Death games", "Jeux mortels"),
            theme("Time Manipulation", "Playing with time", "Jouer avec le temps"),
            theme("Reincarnation", "A second life", "Une seconde vie"),
            theme("Tsundere", "Tsundere alert", "Tsundere en vue"),
            theme("Food", "Food on screen", "Gastronomie"),
            theme("Military", "At war", "En guerre"),
            theme("Super Power", "Superpowers", "Super-pouvoirs"),
            theme("Primarily Female Cast", "Led by women", "Portés par des héroïnes"),
        )
        return if (kind == MediaKind.ANIME) {
            listOf(
                row("trending", "Trending now", "Tendances", AniQuery(kind)),
                row("season", "This season", "Cette saison", AniQuery(kind, sort = "POPULARITY_DESC", season = season, year = year)),
                row("next", "Next season", "La saison prochaine", AniQuery(kind, sort = "POPULARITY_DESC", season = nextSeason, year = nextYear)),
                row("top", "Highest rated", "Les mieux notés", AniQuery(kind, sort = "SCORE_DESC")),
                row("popular", "Most popular of all time", "Les plus populaires", AniQuery(kind, sort = "POPULARITY_DESC")),
                row("movies", "Anime films", "Films d'animation", AniQuery(kind, sort = "POPULARITY_DESC", format = "MOVIE")),
                row("donghua", "Chinese animation", "Animation chinoise", AniQuery(kind, sort = "POPULARITY_DESC", country = "CN")),
            ) + common
        } else {
            listOf(
                row("trending", "Trending now", "Tendances", AniQuery(kind)),
                row("releasing", "Still being published", "En cours de parution", AniQuery(kind, sort = "POPULARITY_DESC", status = "RELEASING")),
                row("top", "Highest rated", "Les mieux notés", AniQuery(kind, sort = "SCORE_DESC")),
                row("popular", "Most popular of all time", "Les plus populaires", AniQuery(kind, sort = "POPULARITY_DESC")),
                row("finished", "Finished, to read in one go", "Terminés, à lire d'une traite", AniQuery(kind, sort = "POPULARITY_DESC", status = "FINISHED")),
                row("manhwa", "Manhwa from Korea", "Manhwa coréens", AniQuery(kind, sort = "POPULARITY_DESC", country = "KR")),
                row("manhua", "Manhua from China", "Manhua chinois", AniQuery(kind, sort = "POPULARITY_DESC", country = "CN")),
                row("novels", "Light novels", "Light novels", AniQuery(kind, sort = "POPULARITY_DESC", format = "NOVEL")),
            ) + common
        }
    }

    /** The same work in the app's catalog: this is what opens its page and finds its versions. */
    suspend fun match(title: AniTitle): TmdbItem? = matching.resolve(title.id) {
        val kind = if (title.format == "MOVIE") "movie" else "tv"
        for (name in listOfNotNull(title.english, title.romaji, title.native).distinct()) {
            val found = try { catalog.search(name) } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Exception) { emptyList() }
            AnimeMatch.pick(found, kind, title)?.let {
                return@resolve it
            }
        }
        null
    }
}

private const val CHECKED_WHEN_AT_MOST = 3
private const val TODAY_ROW = 30
private const val MAX_SEASONS = 8
private const val MAX_READING = 30
private const val READING_LIST = "pc.manga_reading"
private const val ANIMATION_GENRE = 16
private val ASIAN_ANIMATION = setOf("ja", "zh", "ko")

/** A title of the catalog and what fans know about it. */
data class AnimeLink(
    val anime: AniTitle,
    val manga: AniTitle?,
    val mangaLinks: List<AniLink>,
    val themes: List<AnimeTheme>,
    val seasons: List<AniTitle>,
    val adaptations: List<AniTitle>,
    val streamingLinks: List<AniLink> = emptyList(),
)

/** The opening an episode plays, by its number since the first season. */
fun AnimeLink.openingOn(absolute: Int): AnimeTheme? {
    // The one numbering of the app; AniList's seasons are 0-based here. Past the seasons of known length: no opening.
    val (season, episode) = EpisodeMapper.place(absolute, seasons.map { it.episodes })?.let { (s, e) -> s - 1 to e }
        ?: seasons.lastOrNull()?.takeIf { it.episodes == null }?.let { seasons.lastIndex to absolute - seasons.dropLast(1).sumOf { s -> s.episodes ?: 0 } }
        ?: return null
    val openings = themes.filter { it.isOpening && it.season == season }
    return openings.firstOrNull { it.covers(episode) } ?: openings.singleOrNull()
}

data class ReadingPlace(val chapter: String, val page: Int)

data class ReadingEntry(val key: String, val title: String, val cover: String?, val chapter: String, val percent: Int)

sealed interface ReadPage {
    data class Url(val url: String) : ReadPage
    data class File(val name: String, val read: () -> ByteArray) : ReadPage
}

data class ReadChapter(val key: String, val label: String, val number: String?, val credit: String?, val pages: suspend () -> List<ReadPage>)

enum class ReaderMode { Single, Double, Strip }

/** A page as tall as the screen, or as wide as it and scrolled. */
enum class ReaderFit { Height, Width }

data class ReaderPrefs(val mode: ReaderMode?, val fit: ReaderFit?, val dim: Int)

data class ReaderRequest(
    val key: String,
    val title: String,
    val cover: String?,
    val chapters: List<ReadChapter>,
    val startChapter: Int,
    val startPage: Int,
    val mode: ReaderMode,
    val rightToLeft: Boolean,
)

/** The rules of the reader. Pure and tested. */
object ReaderLogic {
    /** A page more than twice as tall as it is wide is a piece of a webtoon strip, never a printed page. */
    fun isStripPage(width: Int, height: Int): Boolean = width > 0 && height > width * 2

    private val IMAGE = Regex("""(?i)\.(jpe?g|png|webp|gif)$""")
    private val NUMBER = Regex("""\d+""")

    /** Natural order: page 2 before page 10. */
    fun imagesInOrder(names: List<String>): List<String> = names.filter { IMAGE.containsMatchIn(it) && !it.startsWith("__MACOSX") }
        .sortedWith(compareBy<String> { it.substringBeforeLast('/', "") }.thenComparator { a, b -> natural(a, b) })

    private fun natural(a: String, b: String): Int {
        val x = NUMBER.findAll(a).map { it.value.toLong() }.toList()
        val y = NUMBER.findAll(b).map { it.value.toLong() }.toList()
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return if (x.size != y.size) x.size.compareTo(y.size) else a.compareTo(b)
    }

    fun chapterLabel(number: String?, title: String?) = listOfNotNull(number?.let { com.sokoul.pc.data.L("Chapter $it", "Chapitre $it").text() }, title).joinToString(" · ").ifEmpty { com.sokoul.pc.data.L("Chapter", "Chapitre").text() }

    fun nextMode(m: ReaderMode) = when (m) {
        ReaderMode.Single -> ReaderMode.Double
        ReaderMode.Double -> ReaderMode.Strip
        ReaderMode.Strip -> ReaderMode.Single
    }

    /** A double page opens with the cover alone, as a book does. */
    fun step(m: ReaderMode, page: Int) = if (m == ReaderMode.Double && page > 0) 2 else 1

    /** The pages on screen, left to right. */
    fun shown(m: ReaderMode, page: Int, total: Int, rightToLeft: Boolean): List<Int> {
        val p = page.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (m != ReaderMode.Double || p == 0 || p + 1 >= total) return listOf(p)
        return if (rightToLeft) listOf(p + 1, p) else listOf(p, p + 1)
    }

    fun modeShort(m: ReaderMode) = when (m) {
        ReaderMode.Single -> com.sokoul.pc.data.L("One page", "Une page")
        ReaderMode.Double -> com.sokoul.pc.data.L("Double page", "Double page")
        ReaderMode.Strip -> com.sokoul.pc.data.L("Long strip", "Bande continue")
    }

    fun fitName(f: ReaderFit) = when (f) {
        ReaderFit.Height -> com.sokoul.pc.data.L("Fit height", "Page entière")
        ReaderFit.Width -> com.sokoul.pc.data.L("Fit width", "Pleine largeur")
    }

    fun modeName(m: ReaderMode, rightToLeft: Boolean) = when (m) {
        ReaderMode.Strip -> com.sokoul.pc.data.L("Long strip", "Bande continue")
        ReaderMode.Double -> if (rightToLeft) com.sokoul.pc.data.L("Double page, right to left", "Double page, sens japonais") else com.sokoul.pc.data.L("Double page", "Double page")
        ReaderMode.Single -> if (rightToLeft) com.sokoul.pc.data.L("One page, right to left", "Une page, sens japonais") else com.sokoul.pc.data.L("One page", "Une page")
    }
}

/** Fans' wikis for the series that record the chapter of each episode, checked by hand. */
object WikiNames {
    // Each one checked on 2026-11-08: its "Episode N" page has a chapters field in numbers. Attack on Titan's wiki names
    // its chapters by title ("To You, 2,000 Years From Now"), read as chapter 2000: left out. One Piece's and Naruto's
    // wikis have no such field.
    private val KNOWN = listOf(
        "jujutsu kaisen" to "jujutsu-kaisen", "demon slayer" to "kimetsu-no-yaiba", "kimetsu no yaiba" to "kimetsu-no-yaiba",
        "chainsaw man" to "chainsaw-man",
        "my hero academia" to "myheroacademia", "boku no hero" to "myheroacademia", "bleach" to "bleach",
        "black clover" to "blackclover", "spy x family" to "spy-x-family", "vinland saga" to "vinlandsaga", "dr stone" to "dr-stone",
        "one punch man" to "onepunchman", "blue lock" to "bluelock", "dandadan" to "dandadan",
    )

    fun of(t: AniTitle): String? {
        val names = listOfNotNull(t.english, t.romaji).map(AnimeCategories::plain)
        return KNOWN.firstOrNull { (k, _) -> names.any { it.startsWith(k) } }?.second
    }
}

/** Which result of a search is this work. Pure and tested. */
object AnimeMatch {
    /** Its exact name first, then the best known that starts with it. A search also answers with synonyms nobody means. */
    fun best(results: List<AniTitle>, name: String): AniTitle? {
        val wanted = AnimeCategories.plain(name)
        fun names(t: AniTitle) = listOfNotNull(t.english, t.romaji, t.native).map(AnimeCategories::plain)
        // Never "the first result": a name that is not this one opened another work ("Berserk", "One Piece").
        return results.filter { t -> names(t).any { it == wanted } }.maxByOrNull { it.popularity ?: 0 }
            ?: results.filter { t -> names(t).any { it.startsWith(wanted) } }.maxByOrNull { it.popularity ?: 0 }
    }

    /** The anime a book is best known by: a TV series first, then the most popular; specials and films last. */
    fun mainAdaptation(adaptations: List<AniTitle>): AniTitle? =
        adaptations.maxWithOrNull(compareBy<AniTitle> { it.format == "TV" }.thenBy { it.format == "TV_SHORT" || it.format == "ONA" }.thenBy { it.popularity ?: 0 })

    /** The book a work comes from: the original manga rather than a spin-off or a novel. */
    fun bookOf(relations: List<AniRelation>): AniTitle? {
        val books = relations.filter { it.title.kind == MediaKind.MANGA }
        val rank = compareBy<AniRelation> { it.type == "SOURCE" }.thenBy { it.title.format == "MANGA" }.thenBy { it.title.popularity ?: 0 }
        return books.maxWithOrNull(rank)?.title
    }


    fun pick(results: List<TmdbItem>, kind: String, title: AniTitle): TmdbItem? {
        val animated = results.filter { it.mediaType == kind && ANIMATION_GENRE in it.genreIds }
        if (animated.isEmpty()) return null
        val names = listOfNotNull(title.english, title.romaji, title.native).map(AnimeCategories::plain)
        // A later season has its own entry here and belongs to one series there: the start of the name is enough.
        return animated.firstOrNull { item -> plainNames(item).any { it in names } }
            ?: animated.firstOrNull { item -> plainNames(item).any { n -> names.any { it.startsWith(n) && n.length >= MIN_PREFIX } } }
    }

    private fun plainNames(item: TmdbItem) = listOfNotNull(item.title, item.originalTitle).map(AnimeCategories::plain)

    private const val MIN_PREFIX = 4
}
