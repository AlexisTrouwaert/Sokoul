package com.sokoul.pc.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
    var query by rememberSaveable { mutableStateOf("") }
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

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        results = null
        try {
            delay(DEBOUNCE_MS)
            searcher.searchEach(q) { results = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (query.trim() == q) loading = false
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
        Row(Modifier.fillMaxWidth().padding(start = 86.dp, end = 80.dp, top = 148.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Palette.Secondary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.width(26.dp))
            BasicText(
                query.ifEmpty { tr(Text.SearchHint) },
                Modifier.weight(1f).testTag("search-query"),
                style = Type.RowTitle.copy(fontSize = 52.sp, color = Palette.Secondary),
                maxLines = 1,
            )
            if (loading) CircularProgressIndicator(Modifier.size(40.dp), color = Palette.Secondary, strokeWidth = 4.dp)
        }
        Column(Modifier.offset(x = 80.dp, y = 265.dp).width(432.dp)) {
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
        Column(Modifier.padding(start = 592.dp, top = 265.dp).width(1328.dp).fillMaxHeight()) {
            val found = results
            if (query.isBlank()) {
                Box(Modifier.width(1248.dp).height(1.dp).background(Palette.Secondary.copy(alpha = 0.5f)))
                Spacer(Modifier.height(32.dp))
                if (recent.isNotEmpty()) {
                    Label(com.sokoul.pc.data.L("Recent searches", "Recherches récentes").text(), Type.RowTitle, Palette.Secondary)
                    Spacer(Modifier.height(14.dp))
                    QueryChips(recent, Icons.Filled.History, "recent") { query = it }
                    Spacer(Modifier.height(32.dp))
                }
                Label(tr(Text.Explore), Type.RowTitle, Palette.Secondary)
                Spacer(Modifier.height(14.dp))
                GenreGrid(deps, genres, openTitle)
            } else if (found == null) {
                Label(tr(Text.SearchLoading), Type.Meta, Palette.Secondary)
            } else {
                Suggestions(found.titles.items) { query = it }
                Spacer(Modifier.height(20.dp))
                Tabs(tab) { tabName = it.name }
                Spacer(Modifier.height(20.dp))
                SearchContent(deps, found, tab, openTitle, openLive, openWork)
            }
        }
    }
}

private const val DEBOUNCE_MS = 300L

/** The remote's microphone, through the system's speech recogniser: typing on a grid is slow from a sofa. */
@Composable
private fun VoiceButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.RowFill)
    Row(
        Modifier.size(432.dp, 68.dp).clip(RoundedCornerShape(50)).background(fill).testTag("search-voice")
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
        Modifier.size(432.dp, 68.dp).clip(RoundedCornerShape(50)).background(Color(0x14000000)).padding(8.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        keys.chunked(6).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                row.forEachIndexed { c, k ->
                    KeyCap(k, Modifier.size(74.dp, 76.dp), if (r == 0 && c == 0) firstKey else remember { FocusRequester() }) { onKey(k) }
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            WordKey(tr(Text.KeySpace), Modifier.width(150.dp), onSpace)
            Box(Modifier.width(132.dp), contentAlignment = Alignment.Center) { BackspaceKey(onBackspace) }
            WordKey(tr(Text.KeyClear), Modifier.width(150.dp), onClear)
        }
    }
}

@Composable
private fun KeyCap(text: String, modifier: Modifier, requester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Color.White else Color.Transparent)
    val scale by animateFloatAsState(if (focused) 0.96f else 0.8f)
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .testTag("key-$text")
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            style = Type.Body.copy(fontSize = 44.sp, fontWeight = FontWeight.Normal, color = if (focused) Color.Black else Palette.Secondary),
        )
    }
}

@Composable
private fun WordKey(text: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Color.White else Color.Transparent)
    Box(
        modifier.height(66.dp).clip(RoundedCornerShape(14.dp)).background(fill).tvFocusable(onFocusChanged = { focused = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Type.Body.copy(fontSize = 26.sp, color = if (focused) Color.Black else Palette.Secondary))
    }
}

@Composable
private fun BackspaceKey(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Color.White else Color.Transparent)
    Box(
        Modifier.size(84.dp, 66.dp).clip(RoundedCornerShape(14.dp)).background(fill).tvFocusable(onFocusChanged = { focused = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.Backspace, null, tint = if (focused) Color.Black else Palette.Secondary, modifier = Modifier.size(42.dp))
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
        GridCells.Fixed(4),
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
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
