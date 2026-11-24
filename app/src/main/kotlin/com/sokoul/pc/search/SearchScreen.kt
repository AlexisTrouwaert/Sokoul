package com.sokoul.pc.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.GenreTile
import com.sokoul.pc.data.kind
import com.sokoul.pc.home.typeLabel
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.theme.CardMetrics
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.FocusCard
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.SingleLine
import com.sokoul.pc.ui.tvFocusable
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.live.TvCatalog
import com.sokoul.pc.data.anime.AniQuery
import com.sokoul.pc.data.anime.AniTitle
import com.sokoul.pc.data.anime.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val Letters = "abcdefghijklmnopqrstuvwxyz".map { it.toString() }
private val Digits = "1234567890".map { it.toString() }

enum class SearchTab(val label: Text) {
    All(Text.SearchAll), Popular(Text.TabPopular), Movies(Text.TabMovies), Series(Text.TabSeries),
    Live(Text.SearchLive), Anime(Text.SearchAnime), Manga(Text.SearchManga),
}

fun filterResults(results: List<TmdbItem>, tab: SearchTab): List<TmdbItem> = when (tab) {
    SearchTab.Popular -> results
    SearchTab.Movies -> results.filter { it.kind() == "movie" }
    SearchTab.Series -> results.filter { it.kind() == "tv" }
    else -> emptyList()
}

/** Grid keyboard usable with the mouse, plus direct typing on the physical keyboard. */
@Composable
fun SearchScreen(
    deps: AppDeps,
    firstKey: FocusRequester,
    onOpen: (List<TmdbItem>, Int, CardOrigin?) -> Unit,
    onOpenLive: (TvCatalog.Hit) -> Unit,
    onOpenWork: (AniTitle, CardOrigin?) -> Unit,
) {
    // Kept while a title opened from the results is on top; a new visit of the page starts empty (a query left from the
    // last visit was typed onto: "teen titans goteen titans go", 2026-11-22).
    var query by remember { mutableStateOf("") }
    var recent by remember(deps) { mutableStateOf(RecentSearches.read(deps.db)) }
    // A search counts as done when one of its results is opened: what was typed on the way is not kept.
    fun keep(q: String) { recent = RecentSearches.add(deps.db, q) }
    val openTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit = { list, i, o -> keep(query); onOpen(list, i, o) }
    val openLive: (TvCatalog.Hit) -> Unit = { keep(query); onOpenLive(it) }
    val openWork: (AniTitle, CardOrigin?) -> Unit = { w, o -> keep(query); onOpenWork(w, o) }
    var results by remember { mutableStateOf<SearchResults<TmdbItem, TvCatalog.Hit, AniTitle, AniTitle>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tabName by rememberSaveable { mutableStateOf(SearchTab.All.name) }
    val tab = SearchTab.entries.firstOrNull { it.name == tabName } ?: SearchTab.All
    var digits by remember { mutableStateOf(false) }
    val genres by produceState(emptyList<GenreTile>()) { value = deps.catalog.genreTiles() }
    val searcher = remember(deps) {
        SearchAggregator<TmdbItem, TvCatalog.Hit, AniTitle, AniTitle>(
            liveAvailable = { deps.tv.playlist() != null },
            animeAvailable = { deps.anime != null },
            mangaAvailable = { deps.anime != null },
            searchTitles = { deps.catalog.searchMore(it) },
            searchLive = { deps.tv.search(it) },
            searchAnime = { deps.anime?.client?.titles(AniQuery(MediaKind.ANIME, search = it, sort = "SEARCH_MATCH", perPage = 12)).orEmpty() },
            searchManga = { deps.anime?.client?.titles(AniQuery(MediaKind.MANGA, search = it, sort = "SEARCH_MATCH", perPage = 12)).orEmpty() },
        )
    }

    // Finished searches of this visit, by query: Backspace, a recent search or a suggestion shows at once, with no network.
    val answered = remember(deps) { SearchMemory<SearchResults<TmdbItem, TvCatalog.Hit, AniTitle, AniTitle>>(SEARCH_MEMORY) }
    val term = query.trim()
    val seriesTab = tab == SearchTab.Series
    val scope = when (tab) {
        SearchTab.Movies, SearchTab.Popular, SearchTab.Series -> SearchScope.Titles
        SearchTab.Live -> SearchScope.Live
        SearchTab.Anime -> SearchScope.Anime
        SearchTab.Manga -> SearchScope.Manga
        SearchTab.All -> SearchScope.All
    }
    var resultScope by remember { mutableStateOf(SearchScope.All) }
    LaunchedEffect(term, scope, seriesTab) {
        val q = term
        if (q.length < 2) {
            results = null
            loading = false
            return@LaunchedEffect
        }
        if (seriesTab) {
            loading = false
            return@LaunchedEffect
        }
        val cacheKey = q.lowercase()
        val scopedKey = "${scope.name}:$cacheKey"
        (answered[scopedKey] ?: answered["All:$cacheKey"])?.let {
            results = it
            resultScope = scope
            loading = false
            return@LaunchedEffect
        }
        // The previous results stay on screen while the next ones come: the page no longer flashes "Searching…" and
        // jumps at every letter typed.
        loading = true
        try {
            delay(DEBOUNCE_MS)
            val final = searcher.searchEach(q, searchTitlesProgressively = deps.catalog::searchMore, scope = scope) { partial ->
                resultScope = scope
                results = partial
            }
            results = final
            if (listOf(final.titles, final.live, final.anime, final.manga).none { it.failed }) answered[scopedKey] = final
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (query.trim() == q) loading = false
        }
    }
    val seriesCache = remember(deps) { SearchMemory<SearchSourceResults<TmdbItem>>(SEARCH_MEMORY) }
    val series by androidx.compose.runtime.key(term, tab) {
        produceState<SearchSourceResults<TmdbItem>?>(seriesCache[term.lowercase()]) {
            if (!seriesTab || term.length < 2 || value != null) return@produceState
            delay(DEBOUNCE_MS)
            try {
                val matches = deps.catalog.searchSeries(term) { first ->
                    if (first.isNotEmpty()) value = SearchSourceResults(true, items = first)
                }
                value = SearchSourceResults(true, items = matches).also { seriesCache[term.lowercase()] = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = SearchSourceResults(true, failed = true, items = value?.items.orEmpty())
            }
        }
    }
    val seriesLoading = seriesTab && term.length >= 2 && series == null

    val resultsBody: @Composable () -> Unit = {
            val found: SearchResults<TmdbItem, TvCatalog.Hit, AniTitle, AniTitle>? =
                if (seriesTab) series?.let {
                    SearchResults(it, SearchSourceResults(false), SearchSourceResults(false), SearchSourceResults(false))
                } else results?.takeIf { resultScope == scope }
            if (query.isBlank()) {
                if (recent.isNotEmpty()) {
                    Label(com.sokoul.pc.data.L("Recent searches", "Recherches récentes").text(), Type.RowTitle, Palette.Secondary)
                    Spacer(Modifier.height(14.dp))
                    QueryChips(recent, Icons.Filled.History, "recent") { query = it }
                    Spacer(Modifier.height(32.dp))
                }
                Label(tr(Text.Explore), Type.RowTitle, Palette.Secondary)
                Spacer(Modifier.height(14.dp))
                GenreGrid(deps, genres, openTitle)
            } else {
                if (found != null) {
                    Suggestions(found.titles.items) { query = it }
                    Spacer(Modifier.height(20.dp))
                }
                Tabs(tab) { tabName = it.name }
                Spacer(Modifier.height(20.dp))
                when {
                    found != null && (!loading || !found.isEmpty) -> SearchContent(found, tab, openTitle, openLive, openWork)
                    term.length < 2 -> Unit
                    else -> Label(tr(Text.SearchLoading), Type.Meta, Palette.Secondary)
                }
            }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .testTag("search")
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed) return@onPreviewKeyEvent false
                val char = e.utf16CodePoint.toChar()
                when {
                    e.key == Key.Backspace -> {
                        query = query.dropLast(1)
                        true
                    }
                    e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Tab -> false
                    char.isLetterOrDigit() || char == ' ' -> {
                        query += char
                        true
                    }
                    else -> false
                }
            },
    ) {
        if (com.sokoul.pc.ui.Form.tabletPortrait) {
            UprightSearch(query, loading || seriesLoading, digits, { digits = it }, firstKey, { query = it }, resultsBody)
            return@Box
        }
        SearchHeader(query, loading || seriesLoading, Modifier.padding(start = 80.dp, end = 80.dp, top = 136.dp))
        // The keyboard on a glass plate, as on Disney+: round keys, the focused one a white disc.
        Column(
            Modifier.offset(x = 80.dp, y = SEARCH_BODY_TOP).width(KEYBOARD_PLATE)
                .clip(RoundedCornerShape(32.dp)).background(Color(0xB81C1C1E))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(32.dp))
                .padding(KEYBOARD_PAD)
                .testTag("search-keyboard"),
        ) {
            if (com.sokoul.pc.Platform.hasVoice) {
                VoiceButton { com.sokoul.pc.Platform.listen { said -> query = said } }
                Spacer(Modifier.height(14.dp))
            }
            ModeSwitch(digits) { digits = it }
            Spacer(Modifier.height(14.dp))
            Keyboard(
                keys = if (digits) Digits else Letters,
                firstKey = firstKey,
                onKey = { query += it },
                onSpace = { query += " " },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
            )
        }
        // Padding, not offset: an offset column kept the screen's height, its bottom off screen, and the focus went to
        // cards there without the list scrolling to them (seen on the TV, 2026-11-18).
        Column(Modifier.padding(start = RESULTS_START, top = SEARCH_BODY_TOP).width(1920.dp - RESULTS_START).fillMaxHeight()) {
            resultsBody()
        }
    }
}

/** Where the keyboard plate and the results start, under the header (1920 frame). */
private val SEARCH_BODY_TOP = 262.dp
private val KEY_SIZE = 64.dp
private val KEY_GAP = 8.dp
private const val KEYS_PER_ROW = 6
private val KEYBOARD_PAD = 24.dp
/** Six keys and five gaps, inside the plate's padding. */
private val KEYBOARD_PLATE = KEY_SIZE * KEYS_PER_ROW + KEY_GAP * (KEYS_PER_ROW - 1) + KEYBOARD_PAD * 2
/** The results begin one gutter (56) after the plate. */
private val RESULTS_START = 80.dp + KEYBOARD_PLATE + 56.dp

/** Long enough to skip the letters typed in a burst, short enough that the page answers as the person stops. */
private const val DEBOUNCE_MS = 220L

/** Finished searches kept for this visit of the page. */
private const val SEARCH_MEMORY = 40

/** The last [capacity] finished searches, the least recently used dropped first. */
internal class SearchMemory<V>(private val capacity: Int) {
    private val map = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > capacity
    }
    operator fun get(key: String): V? = map[key]
    operator fun set(key: String, value: V) { map[key] = value }
}

/** The remote's microphone, through the system's speech recogniser: typing on a grid is slow from a sofa. */
@Composable
private fun VoiceButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.RowFill)
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(50)).background(fill).testTag("search-voice")
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (focused) Palette.OnFocus else Palette.OnBackground
        Icon(Icons.Filled.Mic, null, tint = tint, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        BasicText(com.sokoul.pc.data.L("Speak", "Dicter").text(), style = Type.Button.copy(color = tint))
    }
}

/** The last searches that led somewhere, newest first, kept on this device. */
object RecentSearches {
    private const val KEY = "search.recent"
    private const val MAX = 8
    private const val NEWLINE = "\n"

    fun read(db: com.sokoul.desktop.data.DesktopDb): List<String> =
        db.setting(KEY).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX)

    fun add(db: com.sokoul.desktop.data.DesktopDb, query: String): List<String> {
        val q = query.trim().lowercase()
        val now = read(db)
        if (q.length < 2) return now
        val next = (listOf(q) + now.filter { it != q }).take(MAX)
        db.putSetting(KEY, next.joinToString(NEWLINE))
        return next
    }
}

@Composable
private fun ModeSwitch(digits: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeButton("abc", !digits, Modifier.weight(1f)) { onChange(false) }
        ModeButton("123", digits, Modifier.weight(1f)) { onChange(true) }
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when {
            focused -> Palette.FocusFill
            selected -> Palette.RowFill
            else -> Color.Transparent
        },
    )
    Box(
        modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(fill).tvFocusable(onFocusChanged = { focused = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Type.Body.copy(fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Palette.OnFocus else if (selected) Palette.OnBackground else Palette.Secondary))
    }
}

@Composable
private fun Keyboard(
    keys: List<String>,
    firstKey: FocusRequester,
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        keys.chunked(KEYS_PER_ROW).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEachIndexed { c, k ->
                    KeyCap(k, Modifier.size(KEY_SIZE), if (r == 0 && c == 0) firstKey else remember { FocusRequester() }) { onKey(k) }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(KEY_GAP), verticalAlignment = Alignment.CenterVertically) {
            WordKey(tr(Text.KeySpace), Modifier.width(164.dp), onSpace)
            BackspaceKey(onBackspace)
            WordKey(tr(Text.KeyClear), Modifier.width(164.dp), onClear)
        }
    }
}

/** A letter on a round key: white at rest, a white disc with dark ink when focused, a little larger (Disney+). */
@Composable
private fun KeyCap(text: String, modifier: Modifier, requester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color.Transparent, androidx.compose.animation.core.tween(120))
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 400f))
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(fill)
            .testTag("key-$text")
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = Type.Body.copy(fontSize = 32.sp, fontWeight = FontWeight.Medium, color = if (focused) Palette.OnFocus else Color(0xD9FFFFFF)),
        )
    }
}

/** Space and Clear: pills, faint at rest, white when focused. */
@Composable
private fun WordKey(text: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color(0x14FFFFFF), androidx.compose.animation.core.tween(120))
    Box(
        modifier.height(KEY_SIZE).clip(RoundedCornerShape(50)).background(fill).tvFocusable(onFocusChanged = { focused = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Type.Body.copy(fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Palette.OnFocus else Palette.Secondary))
    }
}

@Composable
private fun BackspaceKey(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color(0x14FFFFFF), androidx.compose.animation.core.tween(120))
    Box(
        Modifier.size(KEY_SIZE).clip(androidx.compose.foundation.shape.CircleShape).background(fill).tvFocusable(onFocusChanged = { focused = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.Backspace, null, tint = if (focused) Palette.OnFocus else Palette.Secondary, modifier = Modifier.size(30.dp))
    }
}

/**
 * Search on a tablet held upright: the same Sokoul keyboard as on the TV (Disney+'s, in Sokoul's glass and white), across
 * the width under what is typed, keys large enough for a finger, then the results. No system keyboard: the page types
 * with its own. Drawn at the tablet's upright scale, as Home and Settings are.
 */
@Composable
private fun UprightSearch(
    query: String, loading: Boolean, digits: Boolean, onDigits: (Boolean) -> Unit, firstKey: FocusRequester,
    onQuery: (String) -> Unit, results: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val outer = androidx.compose.ui.platform.LocalDensity.current
        val inner = com.sokoul.pc.ui.uprightDensity(constraints.maxWidth)
        // Below the top bar, which stays drawn at the app's scale.
        val top = (with(outer) { 120.dp.toPx() } / inner.density).dp
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides inner) {
            Column(Modifier.fillMaxSize().padding(start = UPRIGHT_MARGIN, end = UPRIGHT_MARGIN, top = top)) {
                SearchHeader(query, loading)
                Spacer(Modifier.height(22.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Color(0xB81C1C1E))
                        .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(32.dp)).padding(20.dp).testTag("search-keyboard"),
                    verticalArrangement = Arrangement.spacedBy(UPRIGHT_KEY_GAP),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (com.sokoul.pc.Platform.hasVoice) {
                            Box(Modifier.weight(1f)) { VoiceButton { com.sokoul.pc.Platform.listen { said -> onQuery(said) } } }
                        }
                        Box(Modifier.width(300.dp)) { ModeSwitch(digits, onDigits) }
                    }
                    val keys = if (digits) Digits else Letters
                    keys.chunked(UPRIGHT_KEYS_PER_ROW).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(UPRIGHT_KEY_GAP)) {
                            row.forEachIndexed { c, k ->
                                KeyCap(k, Modifier.weight(1f).height(UPRIGHT_KEY_HEIGHT), if (r == 0 && c == 0) firstKey else remember { FocusRequester() }) { onQuery(query + k) }
                            }
                            // The last row keeps the others' key width.
                            repeat(UPRIGHT_KEYS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(UPRIGHT_KEY_GAP), verticalAlignment = Alignment.CenterVertically) {
                        WordKey(tr(Text.KeySpace), Modifier.weight(2f)) { onQuery("$query ") }
                        BackspaceKey { onQuery(query.dropLast(1)) }
                        WordKey(tr(Text.KeyClear), Modifier.weight(1f)) { onQuery("") }
                    }
                }
                Spacer(Modifier.height(28.dp))
                results()
            }
        }
    }
}

private val UPRIGHT_MARGIN = 44.dp
private const val UPRIGHT_KEYS_PER_ROW = 9
private val UPRIGHT_KEY_HEIGHT = 76.dp
private val UPRIGHT_KEY_GAP = 10.dp

/**
 * What is typed, large, with a blinking caret; the hint when nothing is. Under it one hairline that glows slowly while
 * results are coming (DESIGN.md: no spinner over content).
 */
@Composable
private fun SearchHeader(query: String, loading: Boolean, modifier: Modifier = Modifier) {
    // A frame-clock animation never lets a UI test reach idle, and behind another window it only costs: the caret and
    // the glow move only in the real app, in front (the grid search test waited for ever, 2026-11-24).
    val inFront by com.sokoul.pc.ui.AppFocus.active.collectAsState()
    val animate = com.sokoul.pc.ui.FocusSource.trackRealMouse && inFront
    val caret: Float
    val glow: Float
    if (animate) {
        val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "search-header")
        val caretState = pulse.animateFloat(
            1f, 0f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(530, easing = androidx.compose.animation.core.LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "caret",
        )
        val glowState = pulse.animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "glow",
        )
        caret = caretState.value
        glow = glowState.value
    } else {
        caret = 1f
        glow = 0.5f
    }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Palette.Secondary, modifier = Modifier.size(52.dp))
            Spacer(Modifier.width(22.dp))
            if (query.isEmpty()) {
                BasicText(
                    tr(Text.SearchHint), Modifier.testTag("search-query"),
                    style = Type.RowTitle.copy(fontFamily = com.sokoul.pc.theme.InterDisplay, fontSize = 56.sp, fontWeight = FontWeight.SemiBold, color = Palette.Secondary),
                    maxLines = 1,
                )
            } else {
                BasicText(
                    query, Modifier.weight(1f, fill = false).testTag("search-query"),
                    style = Type.RowTitle.copy(fontFamily = com.sokoul.pc.theme.InterDisplay, fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = Palette.OnBackground),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(4.dp, 54.dp).graphicsLayer { alpha = caret }.background(Palette.OnBackground))
        }
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = if (loading) 0.25f + 0.55f * glow else 0.18f }
                .background(Palette.OnBackground),
        )
    }
}

@Composable
private fun GenreGrid(deps: AppDeps, genres: List<GenreTile>, onOpen: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    var loadingGenre by remember { mutableStateOf<GenreTile?>(null) }
    LaunchedEffect(loadingGenre) {
        val g = loadingGenre ?: return@LaunchedEffect
        val list = deps.catalog.byGenre(g.kind, g.id)
        loadingGenre = null
        if (list.isNotEmpty()) onOpen(list, 0, null)
    }
    LazyVerticalGrid(
        GridCells.Fixed(if (com.sokoul.pc.ui.Form.tabletPortrait) 3 else 4),
        contentPadding = PaddingValues(end = 80.dp, top = 14.dp, bottom = 300.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalArrangement = Arrangement.spacedBy(70.dp),
    ) {
        items(genres, key = { it.id }) { g ->
            FocusCard(CardMetrics.PosterWidth, CardMetrics.PosterHeight, { loadingGenre = g }) {
                Art(g.image, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xCC000000))))
                Label(g.name, Type.Button, modifier = Modifier.align(Alignment.BottomStart).padding(24.dp), maxLines = 2)
            }
        }
    }
}

/** TMDB has no suggestion service: the top matching titles act as suggestions, as real data rather than invented text. */
@Composable
private fun Suggestions(results: List<TmdbItem>, onPick: (String) -> Unit) =
    QueryChips(results.take(4).map { it.title.lowercase() }, Icons.Filled.Search, "suggestion", onPick)

/** A row of searches to run again in one press: suggestions, or the recent searches. */
@Composable
private fun QueryChips(queries: List<String>, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        queries.take(4).forEachIndexed { i, text ->
            var focused by remember { mutableStateOf(false) }
            val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.RowFill)
            Row(
                Modifier.height(66.dp).clip(RoundedCornerShape(50)).background(fill)
                    .testTag("$tag-$i")
                    .tvFocusable(onFocusChanged = { focused = it }, onClick = { onPick(text) }).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (focused) Palette.OnFocus else Palette.OnBackground, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                SingleLine(text, Type.Button, if (focused) Palette.OnFocus else Palette.Secondary, Modifier.width(220.dp))
            }
        }
    }
}

@Composable
private fun Tabs(tab: SearchTab, onChange: (SearchTab) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(50)).background(Color(0x14000000)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchTab.entries.forEach { t ->
            var focused by remember { mutableStateOf(false) }
            val selected = t == tab
            val fill by animateColorAsState(
                when {
                    focused -> Palette.FocusFill
                    selected -> Palette.RowFill
                    else -> Color.Transparent
                },
            )
            Box(
                Modifier.height(66.dp).clip(RoundedCornerShape(50)).background(fill)
                    .tvFocusable(onFocusChanged = {
                        focused = it
                        if (it) onChange(t)
                    }, onClick = { onChange(t) }).padding(horizontal = 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(tr(t.label), style = Type.Button.copy(color = if (focused) Palette.OnFocus else Palette.OnBackground))
            }
        }
    }
}

@Composable
internal fun ResultCard(deps: AppDeps, item: TmdbItem, onClick: (CardOrigin) -> Unit) {
    val genre by produceState<String?>(null, item.id) { value = item.genreIds.firstOrNull()?.let { deps.catalog.genreName(item.kind(), it) } }
    FocusCard(560.dp, 160.dp, onClick, corner = 32.dp) { focused ->
        val fill by animateColorAsState(if (focused) Color(0xFF636366) else Color(0xFF3F3F41))
        Row(Modifier.fillMaxSize().background(fill).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(item.poster, Modifier.size(80.dp, 120.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.fillMaxWidth()) {
                Label(item.title, Type.Meta.copy(fontSize = 28.sp, fontWeight = FontWeight.Normal), maxLines = 2)
                Spacer(Modifier.height(4.dp))
                SingleLine(listOfNotNull(typeLabel(item.kind()), genre, item.year?.toString()).joinToString(" · "), Type.Body, Palette.Secondary)
            }
        }
    }
}
