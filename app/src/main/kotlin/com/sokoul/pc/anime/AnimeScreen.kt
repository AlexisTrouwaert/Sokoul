package com.sokoul.pc.anime

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.data.anime.AniAiring
import com.sokoul.pc.data.anime.AniCategory
import com.sokoul.pc.data.anime.AniQuery
import com.sokoul.pc.data.anime.AniTitle
import com.sokoul.pc.data.anime.AnimeCatalog
import com.sokoul.pc.data.anime.AnimeCategories
import com.sokoul.pc.data.anime.AnimeLink
import com.sokoul.pc.data.anime.CategoryGroup
import com.sokoul.pc.data.anime.MediaKind
import com.sokoul.pc.data.anime.ReaderRequest
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.theme.CardMetrics
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.BackButton
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.CardRow
import com.sokoul.pc.ui.FocusCard
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.tvFocusable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val CARD_W = 210.dp
private val CARD_H = 300.dp
private const val SELECT_DELAY_MS = 300L
private const val CALENDAR_DAYS = 7L
private const val MIN_POPULARITY_ON_CALENDAR = 800
private const val FOCUS_SETTLE_MS = 32L

/** What opens over the page: everything to sort by, or the week's episodes. */
enum class AnimeHubView { Categories, Calendar }

/** What the page shows, for the tools. */
data class AnimeReport(val view: String, val categories: Int, val shownInGrid: Int, val pinned: List<String>, val detail: String?)

/** A cover. On the anime page, the same card as everywhere. */
@Composable
fun AniCard(t: AniTitle, modifier: Modifier = Modifier, tag: String? = null, onClick: (CardOrigin) -> Unit) {
    FocusCard(CARD_W, CARD_H, onClick, modifier, tag = tag) { focused ->
        Art(t.cover, Modifier.fillMaxSize())
        if (focused) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE6000000))))
            Label(t.title, Type.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.align(Alignment.BottomStart).padding(12.dp), maxLines = 2)
        }
    }
}

/** A work: to its title page when it has one, on its book when asked for, else on a page of its own. */
fun openWork(anime: AnimeCatalog, t: AniTitle, scope: kotlinx.coroutines.CoroutineScope, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit, onDetail: (AniTitle) -> Unit, origin: CardOrigin? = null) {
    // One page per work: a book with an anime opens the anime's page, where the book has its own part (chapters, and
    // the chapters of each episode). A book without one opens its own page.
    scope.launch {
        val item = anime.titleOf(t)
        if (item != null) {
            onOpenTitle(listOf(item), 0, origin)
        } else {
            onDetail(t)
        }
    }
}

/** Takes the keys when the page opens, then hands them to its first control as soon as it has one. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun takeFocusOnOpen(ready: Boolean = true): FocusRequester {
    val root = remember { FocusRequester() }
    val manager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(ready) {
        delay(FOCUS_SETTLE_MS)
        runCatching { root.requestFocus() }
        if (ready) {
            delay(FOCUS_SETTLE_MS)
            runCatching { manager.moveFocus(androidx.compose.ui.focus.FocusDirection.Enter) }
        }
    }
    return root
}

/** Everything to sort by, over the page: every category, combined, with what fans filter on. */
@Composable
fun AnimeCategoriesPage(deps: AppDeps, startKind: MediaKind, preset: String?, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit, onRead: (ReaderRequest) -> Unit, onClose: () -> Unit) {
    val anime = deps.anime ?: return
    val scope = rememberCoroutineScope()
    val categories by produceState(emptyList<AniCategory>()) {
        value = AnimeCategories.all(runCatching { anime.client.tags() }.getOrDefault(emptyList()))
    }
    var kind by remember { mutableStateOf(startKind) }
    var query by remember { mutableStateOf("") }
    val pinned = remember { mutableStateListOf<AniCategory>() }
    var looking by remember { mutableStateOf<AniCategory?>(null) }
    var sort by remember { mutableStateOf("POPULARITY_DESC") }
    var format by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var country by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<AniTitle?>(null) }
    LaunchedEffect(categories) {
        if (preset != null && pinned.isEmpty()) categories.firstOrNull { it.name == preset }?.let { pinned += it }
    }
    val groups = remember(categories, query) { categories.filter { AnimeCategories.matches(it, query) }.groupBy { it.group } }
    var wanted by remember { mutableStateOf<AniCategory?>(null) }
    LaunchedEffect(wanted) {
        val c = wanted ?: return@LaunchedEffect
        delay(SELECT_DELAY_MS)
        looking = c
    }
    val active = (pinned.toList() + listOfNotNull(looking)).distinct()
    var count by remember { mutableStateOf(0) }
    SideEffect { deps.animeReport = AnimeReport(AnimeHubView.Categories.name, categories.size, count, pinned.map { it.name }, detail?.title) }
    val root = takeFocusOnOpen(categories.isNotEmpty())
    var moved by remember { mutableStateOf(false) }
    val toggle = { c: AniCategory -> if (c in pinned) pinned.remove(c) else pinned.add(c) }

    Box(Modifier.fillMaxSize().background(Palette.Background).testTag("anime-categories")) {
        Row(
            Modifier.fillMaxSize().padding(top = 112.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val char = e.utf16CodePoint.toChar()
                    if (e.key == Key.DirectionUp || e.key == Key.DirectionDown) moved = true
                    when {
                        detail != null -> false
                        e.key == Key.Escape && query.isNotEmpty() -> {
                            query = ""
                            true
                        }
                        e.key == Key.Escape -> {
                            onClose()
                            true
                        }
                        e.key == Key.Backspace && query.isNotEmpty() -> {
                            query = query.dropLast(1)
                            true
                        }
                        char.isLetterOrDigit() -> {
                            query += char
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(root).focusable(),
        ) {
            Column(Modifier.width(500.dp).fillMaxHeight().padding(start = Metrics.PageMargin, end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(query.ifEmpty { L("Search ${categories.size} categories", "Chercher parmi ${categories.size} catégories").text() }, Type.Body.copy(fontWeight = FontWeight.SemiBold), if (query.isEmpty()) Palette.Secondary else Palette.OnBackground, Modifier.padding(start = 20.dp).testTag("anime-category-count"), maxLines = 1)
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    groups.forEach { (group, list) ->
                        item(key = "g-$group") { Label(groupName(group).text(), Type.Body.copy(fontWeight = FontWeight.Bold), Palette.Secondary, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp), maxLines = 1) }
                        list.forEach { c ->
                            item(key = c.name) {
                                CategoryRow(c, c in pinned, c == looking, onFocus = { if (moved) wanted = c }) { toggle(c) }
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(active.joinToString(" + ") { AnimeCategories.label(it.name) }.ifEmpty { L("Everything", "Tout").text() }, Type.RowTitle, modifier = Modifier.padding(start = 8.dp).testTag("anime-category-name"), maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Choices(listOf(MediaKind.ANIME to L("Anime", "Animés"), MediaKind.MANGA to L("Manga", "Mangas")), kind, "kind") { kind = it }
                    Choices(listOf("POPULARITY_DESC" to L("Popular", "Populaires"), "TRENDING_DESC" to L("Trending", "Tendances"), "SCORE_DESC" to L("Best rated", "Mieux notés"), "START_DATE_DESC" to L("Newest", "Récents")), sort, "sort") { sort = it }
                    Choices(listOf(null to L("Everywhere", "Partout"), "JP" to L("Japan", "Japon"), "KR" to L("Korea", "Corée"), "CN" to L("China", "Chine")), country, "country") { country = it }
                }
                val formats = if (kind == MediaKind.ANIME) {
                    listOf(null to L("All formats", "Tous formats"), "TV" to L("Series", "Séries"), "MOVIE" to L("Films", "Films"), "OVA" to L("OVA", "OAV"), "ONA" to L("Web", "Web"))
                } else {
                    listOf(null to L("All formats", "Tous formats"), "MANGA" to L("Manga", "Manga"), "NOVEL" to L("Light novels", "Light novels"), "ONE_SHOT" to L("One shots", "One shots"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Choices(formats, format, "format") { format = it }
                    Choices(listOf(null to L("Any status", "Tous états"), "RELEASING" to L("Ongoing", "En cours"), "FINISHED" to L("Finished", "Terminés")), status, "status") { status = it }
                }
                val q = AniQuery(
                    kind, sort = sort, format = format, status = status, country = country,
                    genres = active.filter { it.isGenre }.map { it.name }, tags = active.filter { !it.isGenre }.map { it.name }, perPage = 30,
                )
                ResultGrid(anime, q, onCount = { count = it }) { t, origin ->
                    openWork(anime, t, scope, onOpenTitle, { detail = it }, origin)
                }
            }
        }
        BackButton(onClick = onClose, modifier = Modifier.padding(start = 40.dp, top = 44.dp).testTag("anime-categories-back"))
        detail?.let { t -> WorkPage(deps, t, onOpenTitle, onRead) { detail = null } }
    }
}

private fun groupName(g: CategoryGroup) = when (g) {
    CategoryGroup.Genres -> L("Genres", "Genres")
    CategoryGroup.Audience -> L("Audience", "Public")
    CategoryGroup.Characters -> L("Characters", "Personnages")
    CategoryGroup.Worlds -> L("Worlds and places", "Univers et lieux")
    CategoryGroup.Action -> L("Action", "Action")
    CategoryGroup.Fantasy -> L("Fantasy", "Fantastique")
    CategoryGroup.SciFi -> L("Science fiction", "Science-fiction")
    CategoryGroup.Drama -> L("Drama", "Drame")
    CategoryGroup.Comedy -> L("Comedy", "Comédie")
    CategoryGroup.Romance -> L("Romance", "Romance")
    CategoryGroup.SliceOfLife -> L("Slice of life", "Tranche de vie")
    CategoryGroup.Sports -> L("Sports and games", "Sports et jeux")
    CategoryGroup.Arts -> L("Arts and music", "Arts et musique")
    CategoryGroup.Society -> L("Society and more", "Société et autres")
}

@Composable
internal fun <T> Choices(options: List<Pair<T, L>>, current: T, tag: String, onPick: (T) -> Unit) {
    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEachIndexed { i, (value, label) ->
            com.sokoul.pc.ui.PanelChip(label.text(), Modifier.testTag("$tag-$i"), selected = value == current) { onPick(value) }
        }
    }
}

@Composable
private fun CategoryRow(c: AniCategory, pinned: Boolean, looking: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    var has by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (has) Palette.FocusFill else if (pinned) Palette.RowFill else if (looking) Palette.ActiveFill else Color.Transparent)
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(fill)
            .tvFocusable(
                onFocusChanged = {
                    has = it
                    if (it) onFocus()
                },
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Label(AnimeCategories.label(c.name), Type.Meta, if (has) Palette.OnFocus else Palette.OnBackground, maxLines = 1)
        if (pinned) Label("✓", Type.Meta, if (has) Palette.OnFocus else Palette.OnBackground, maxLines = 1)
    }
}

@Composable
private fun ResultGrid(anime: AnimeCatalog, q: AniQuery, onCount: (Int) -> Unit, onOpen: (AniTitle, CardOrigin) -> Unit) {
    val titles = remember(q) { mutableStateListOf<AniTitle>() }
    var page by remember(q) { mutableStateOf(1) }
    val state = rememberLazyGridState()
    LaunchedEffect(q, page) {
        val found = runCatching { anime.client.titles(q.copy(page = page)) }.getOrDefault(emptyList())
        titles += found.filter { f -> titles.none { it.id == f.id } }
        onCount(titles.size)
    }
    LaunchedEffect(q) {
        state.scrollToItem(0)
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (titles.isNotEmpty() && last >= titles.size - GRID_AHEAD && titles.size >= page * GRID_PAGE_MIN) page++
        }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(CARD_W + CardMetrics.Gap),
        Modifier.fillMaxSize().testTag("anime-grid"),
        state = state,
        contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 16.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(CardMetrics.Gap),
    ) {
        itemsIndexed(titles, key = { _, t -> t.id }) { i, t -> AniCard(t, tag = "anime-grid-$i") { origin -> onOpen(t, origin) } }
    }
}

private const val GRID_AHEAD = 10
private const val GRID_PAGE_MIN = 20

/** The next seven days at local time. Your own titles can stand alone. */
@Composable
fun AnimeCalendarPage(deps: AppDeps, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit, onRead: (ReaderRequest) -> Unit, onClose: () -> Unit) {
    val anime = deps.anime ?: return
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val airings by produceState<List<AniAiring>?>(null) {
        // Day by day, each with its own pages: asked for the whole week at once, the pages stopped at 300 airings and a
        // page AniList refused (its per-minute limit) took whole days away with it (2026-11-10).
        val all = mutableListOf<AniAiring>()
        for (d in 0 until CALENDAR_DAYS) {
            val from = today.plusDays(d).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = today.plusDays(d + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            for (page in 1..CALENDAR_PAGES_PER_DAY) {
                var found = runCatching { anime.client.schedule(from, to, page) }.getOrDefault(emptyList())
                // An empty first page is more often a refusal than a day without anime: asked again after a pause.
                var tries = 0
                while (found.isEmpty() && page == 1 && tries < CALENDAR_RETRIES) {
                    tries++
                    delay(CALENDAR_RETRY_MS)
                    found = runCatching { anime.client.schedule(from, to, page) }.getOrDefault(emptyList())
                }
                all += found
                value = all.filter { (it.title.popularity ?: 0) >= MIN_POPULARITY_ON_CALENDAR }
                if (found.size < CALENDAR_PAGE_SIZE) break
            }
        }
        value = all.filter { (it.title.popularity ?: 0) >= MIN_POPULARITY_ON_CALENDAR }
    }
    var onlyMine by remember { mutableStateOf(false) }
    var onlyToday by remember { mutableStateOf(false) }
    val mine = deps.myList.items.map { AnimeCategories.plain(it.title) }.toSet()
    val shown = airings.orEmpty().filter { a -> !onlyMine || listOfNotNull(a.title.english, a.title.romaji).any { AnimeCategories.plain(it) in mine } }
    val byDay = remember(shown) { shown.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() } }
    val days = (0 until if (onlyToday) 1L else CALENDAR_DAYS).map { today.plusDays(it) }.filter { byDay[it].orEmpty().isNotEmpty() }
    var detail by remember { mutableStateOf<AniTitle?>(null) }
    var focused by remember { mutableStateOf<AniAiring?>(null) }
    SideEffect { deps.animeReport = AnimeReport(AnimeHubView.Calendar.name, 0, shown.size, emptyList(), detail?.title) }
    val root = takeFocusOnOpen(airings != null)
    Box(Modifier.fillMaxSize().background(Palette.Background).testTag("anime-calendar")) {
        val a = focused
        a?.title?.banner?.let { Art(it, Modifier.fillMaxWidth().height(CALENDAR_HERO), placeholder = Palette.Background) }
        Box(Modifier.fillMaxWidth().height(CALENDAR_HERO).background(Brush.verticalGradient(0f to Color(0x99000000), 1f to Palette.Background)))
        Column(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && detail == null) {
                    onClose()
                    true
                } else {
                    false
                }
            }.focusRequester(root).focusable(),
        ) {
            Column(Modifier.fillMaxWidth().height(CALENDAR_HERO).padding(start = Metrics.PageMargin, end = Metrics.PageMargin, top = 110.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(a?.title?.title ?: L("This week", "Cette semaine").text(), Type.RowTitle.copy(fontSize = Type.RowTitle.fontSize * 1.4f), maxLines = 1, modifier = Modifier.testTag("calendar-title"))
                a?.let { Label("${clock(it.at)} · ${L("Episode ${it.episode}", "Épisode ${it.episode}").text()}", Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 1) }
                a?.title?.description?.let { Label(it, Type.Body, Palette.BarText, maxLines = 2, modifier = Modifier.width(1100.dp)) }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Choices(listOf(false to L("This week", "Cette semaine"), true to L("Today", "Aujourd'hui")), onlyToday, "calendar-when") { onlyToday = it }
                    Choices(listOf(false to L("All", "Tout"), true to L("My List", "Ma liste")), onlyMine, "calendar-filter") { onlyMine = it }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 60.dp)) {
                items(days, key = { it.toString() }) { day ->
                    val name = if (day == today) L("Today", "Aujourd'hui").text() else day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() }
                    CardRow("$name ${day.dayOfMonth}", byDay[day].orEmpty(), key = { "${it.title.id}-${it.episode}" }, testTag = "calendar-$day") { _, x ->
                        CalendarCard(x, onFocus = { focused = x }) { openWork(anime, x.title, scope, onOpenTitle, { detail = it }) }
                    }
                }
            }
        }
        BackButton(onClick = onClose, modifier = Modifier.padding(start = 40.dp, top = 44.dp).testTag("anime-calendar-back"))
        detail?.let { t -> WorkPage(deps, t, onOpenTitle, onRead) { detail = null } }
    }
}

private const val CALENDAR_PAGES_PER_DAY = 3
/** AniList's page size in [com.sokoul.pc.data.anime.AniList.SCHEDULE]: a shorter page is the last one of the day. */
private const val CALENDAR_PAGE_SIZE = 50
private const val CALENDAR_RETRIES = 2
private const val CALENDAR_RETRY_MS = 8_000L
private val CALENDAR_HERO = 400.dp

@Composable
private fun CalendarCard(a: AniAiring, onFocus: () -> Unit, onClick: () -> Unit) {
    val past = a.at < System.currentTimeMillis()
    FocusCard(CardMetrics.EpisodeWidth, CALENDAR_CARD_H, { onClick() }, onFocus = { if (it) onFocus() }) { focused ->
        Art(a.title.banner ?: a.title.cover, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color(0x22000000), 1f to Color(0xF2000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Label("${clock(a.at)} · ${L("Ep. ${a.episode}", "Ép. ${a.episode}").text()}", Type.Body.copy(fontWeight = FontWeight.Bold), if (past && !focused) Palette.Secondary else Palette.OnBackground, maxLines = 1)
            Label(a.title.title, Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

private val CALENDAR_CARD_H = 200.dp

/** A work the catalog does not have, or a book with no anime: its own page, in the same language as the others. */
@Composable
fun WorkPage(deps: AppDeps, t: AniTitle, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit, onRead: (ReaderRequest) -> Unit, onClose: () -> Unit) {
    val anime = deps.anime ?: return
    val scope = rememberCoroutineScope()
    val link by produceState<AnimeLink?>(null, t.id) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { anime.linkOf(t) }.getOrNull() } }
    val root = takeFocusOnOpen(link != null)
    Box(
        Modifier.fillMaxSize().background(Palette.Background).testTag("anime-detail").onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        }.focusRequester(root).focusable(),
    ) {
        t.banner?.let { Art(it, Modifier.fillMaxWidth().height(620.dp), placeholder = Palette.Background) }
        Box(Modifier.fillMaxWidth().height(620.dp).background(Brush.verticalGradient(0f to Color(0x99000000), 1f to Palette.Background)))
        Column(Modifier.fillMaxSize().padding(top = 130.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(30.dp)) {
            val l = link
            if (l?.manga != null) {
                MangaSection(deps, null, l, onRead)
                if (l.adaptations.isNotEmpty()) {
                    CardRow(L("Adaptations", "Adaptations").text(), l.adaptations, key = { it.id }) { _, r ->
                        AniCard(r) { origin -> openWork(anime, r, scope, onOpenTitle, {}, origin) }
                    }
                }
            } else {
                Row(Modifier.padding(horizontal = Metrics.PageMargin), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    Art(t.cover, Modifier.size(300.dp, 430.dp).clip(RoundedCornerShape(CardMetrics.Corner)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(t.title, Type.RowTitle.copy(fontSize = Type.RowTitle.fontSize * 1.4f), maxLines = 2, modifier = Modifier.testTag("anime-detail-title"))
                        Label((t.genres + t.tags.take(6)).joinToString(" · ") { AnimeCategories.label(it) }, Type.Body, Palette.BarText, maxLines = 2)
                        t.description?.let { Label(it, Type.Body, maxLines = 7) }
                    }
                }
            }
        }
        BackButton(onClick = onClose, modifier = Modifier.padding(start = 40.dp, top = 44.dp).testTag("anime-detail-back"))
    }
}
