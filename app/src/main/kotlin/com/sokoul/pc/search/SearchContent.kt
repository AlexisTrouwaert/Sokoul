package com.sokoul.pc.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.kind
import com.sokoul.pc.data.anime.AniTitle
import com.sokoul.pc.data.anime.MediaKind
import com.sokoul.pc.home.typeLabel
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.live.TvCatalog
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.FocusCard
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.SingleLine

@Composable
internal fun SearchContent(
    results: SearchResults<TmdbItem, TvCatalog.Hit, AniTitle, AniTitle>,
    tab: SearchTab,
    onOpenTitles: (List<TmdbItem>, Int, CardOrigin?) -> Unit,
    onOpenLive: (TvCatalog.Hit) -> Unit,
    onOpenWork: (AniTitle, CardOrigin?) -> Unit,
) {
    val gridState = rememberLazyGridState()
    when (tab) {
        SearchTab.All -> {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = POSTER_ROOM, top = POSTER_ROOM, bottom = 200.dp).testTag("search-results"),
                verticalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                if (results.titles.items.isNotEmpty()) SearchSection(tr(Text.SearchTitles)) {
                    val shown = results.titles.items.take(ALL_TITLES)
                    PosterRows(shown) { item -> SearchPoster(item) { origin -> onOpenTitles(shown, shown.indexOf(item), origin) } }
                }
                if (results.live.items.isNotEmpty()) SearchSection(tr(Text.SearchLive)) {
                    LiveRail(results.live.items, onOpenLive)
                }
                if (results.anime.items.isNotEmpty()) SearchSection(tr(Text.SearchAnime)) {
                    PosterRows(results.anime.items.take(ALL_WORKS)) { work -> WorkPoster(work) { origin -> onOpenWork(work, origin) } }
                }
                if (results.manga.items.isNotEmpty()) SearchSection(tr(Text.SearchManga)) {
                    PosterRows(results.manga.items.take(ALL_WORKS)) { work -> WorkPoster(work) { origin -> onOpenWork(work, origin) } }
                }
                SearchSourceStatus(results)
                if (results.isEmpty && listOf(results.titles, results.live, results.anime, results.manga).none { it.failed }) {
                    Label(tr(Text.NoResults), Type.Meta, Palette.Secondary)
                }
            }
        }
        SearchTab.Popular, SearchTab.Movies, SearchTab.Series -> {
            val shown = filterResults(results.titles.items, tab)
            when {
                results.titles.failed && shown.isEmpty() -> SourceUnavailable(Text.SearchTitles)
                shown.isEmpty() -> Label(tr(Text.NoResults), Type.Meta, Palette.Secondary)
                else -> LazyVerticalGrid(
                    GridCells.Fixed(POSTER_COLUMNS),
                    state = gridState,
                    contentPadding = PaddingValues(start = POSTER_ROOM, top = POSTER_ROOM, end = 80.dp, bottom = 300.dp),
                    horizontalArrangement = Arrangement.spacedBy(POSTER_GAP),
                    verticalArrangement = Arrangement.spacedBy(40.dp),
                    modifier = Modifier.testTag("search-results"),
                ) {
                    items(shown.size, key = { shown[it].kind() + shown[it].id }) { i ->
                        SearchPoster(shown[i]) { onOpenTitles(shown, i, it) }
                    }
                }
            }
        }
        SearchTab.Live -> when {
            !results.live.available -> Label(tr(Text.NoSourceConfigured), Type.Meta, Palette.Secondary)
            results.live.failed -> SourceUnavailable(Text.SearchLive)
            results.live.items.isEmpty() -> Label(tr(Text.NoResults), Type.Meta, Palette.Secondary)
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize().testTag("search-results"),
            ) {
                items(results.live.items, key = { "${it.channel.id}:${it.program?.start ?: 0}" }) { hit ->
                    LiveResultCard(hit) { onOpenLive(hit) }
                }
            }
        }
        SearchTab.Anime -> WorkResults(results.anime, Text.SearchAnime, onOpenWork)
        SearchTab.Manga -> WorkResults(results.manga, Text.SearchManga, onOpenWork)
    }
}

/** Room left of a rail for its focused card to grow into; the rail is pulled back by as much, so the cards keep the section's edge. */
private val RAIL_ROOM = 28.dp
private val RailEdge = Modifier.offset(x = -RAIL_ROOM)

@Composable
private fun SearchSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(title, Type.RowTitle, maxLines = 1)
        content()
    }
}

/** Posters as on Disney+: 2:3, five to a row, the title and its type under each (DESIGN.md: title under, in grids). */
private val POSTER_WIDTH = 216.dp
private val POSTER_HEIGHT = 324.dp
private val POSTER_GAP = 32.dp
/** Five across on a TV or a tablet held sideways, four on a tablet held upright. */
private val POSTER_COLUMNS: Int get() = if (com.sokoul.pc.ui.Form.tabletPortrait) 4 else 5
/** Room for a focused poster to grow into (scale 1.10) without being cut by the list's edge. */
private val POSTER_ROOM = 14.dp
/** In "All", a few rows of each kind; the tabs show them all. */
private const val ALL_TITLES = 15
private const val ALL_WORKS = 10

@Composable
private fun <T> PosterRows(items: List<T>, cell: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(36.dp)) {
        items.chunked(POSTER_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(POSTER_GAP)) { row.forEach { cell(it) } }
        }
    }
}

/** A film or series: its poster, then its title (white when focused) and "Film · 2014". */
@Composable
internal fun SearchPoster(item: TmdbItem, onClick: (CardOrigin) -> Unit) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.width(POSTER_WIDTH)) {
        FocusCard(POSTER_WIDTH, POSTER_HEIGHT, onClick, onFocus = { focused = it }, tag = "search-poster-${item.id}") {
            val artwork = item.poster ?: item.backdrop
            if (artwork != null) {
                Art(artwork, Modifier.fillMaxSize())
            } else {
                Label(item.title, Type.Meta, modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp), maxLines = 3)
            }
        }
        PosterCaption(item.title, listOfNotNull(typeLabel(item.kind()), item.year?.toString()).joinToString(" · "), focused)
    }
}

/** An anime or a manga from the anime catalogue, drawn like the films. */
@Composable
private fun WorkPoster(work: AniTitle, onClick: (CardOrigin) -> Unit) {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.width(POSTER_WIDTH)) {
        FocusCard(POSTER_WIDTH, POSTER_HEIGHT, onClick, corner = 16.dp, onFocus = { focused = it }) {
            Art(work.cover, Modifier.fillMaxSize())
        }
        val details = listOfNotNull(
            work.year?.toString(),
            if (work.kind == MediaKind.ANIME) work.format?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() } else work.chapters?.let { "$it ch." },
        ).joinToString(" · ")
        PosterCaption(work.title, details, focused)
    }
}

@Composable
private fun PosterCaption(title: String, detail: String, focused: Boolean) {
    Spacer(Modifier.height(16.dp))
    Label(title, Type.Meta.copy(fontSize = 25.sp, fontWeight = FontWeight.SemiBold), if (focused) Palette.OnBackground else Palette.Secondary, maxLines = 1)
    if (detail.isNotEmpty()) Label(detail, Type.Body, Palette.Secondary.copy(alpha = 0.6f), maxLines = 1)
}

@Composable
private fun LiveRail(items: List<TvCatalog.Hit>, onOpen: (TvCatalog.Hit) -> Unit) {
    LazyRow(RailEdge, horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(start = RAIL_ROOM, end = 42.dp)) {
        items(items, key = { "${it.channel.id}:${it.program?.start ?: 0}" }) { hit -> LiveResultCard(hit) { onOpen(hit) } }
    }
}

@Composable
private fun WorkRail(items: List<AniTitle>, onOpen: (AniTitle, CardOrigin?) -> Unit) {
    LazyRow(RailEdge, horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(start = RAIL_ROOM, end = 42.dp)) {
        items(items, key = { it.id }) { work -> WorkResultCard(work) { origin -> onOpen(work, origin) } }
    }
}

@Composable
private fun WorkResults(source: SearchSourceResults<AniTitle>, name: Text, onOpen: (AniTitle, CardOrigin?) -> Unit) {
    when {
        !source.available -> Label(tr(Text.NoSourceConfigured), Type.Meta, Palette.Secondary)
        source.failed -> SourceUnavailable(name)
        source.items.isEmpty() -> Label(tr(Text.NoResults), Type.Meta, Palette.Secondary)
        else -> LazyVerticalGrid(
            GridCells.Fixed(POSTER_COLUMNS),
            contentPadding = PaddingValues(start = POSTER_ROOM, top = POSTER_ROOM, end = 80.dp, bottom = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(POSTER_GAP),
            verticalArrangement = Arrangement.spacedBy(40.dp),
            modifier = Modifier.testTag("search-results"),
        ) {
            items(source.items, key = { it.id }) { work -> WorkPoster(work) { origin -> onOpen(work, origin) } }
        }
    }
}

@Composable
private fun SearchSourceStatus(results: SearchResults<TmdbItem, TvCatalog.Hit, AniTitle, AniTitle>) {
    listOf(
        results.titles to Text.SearchTitles,
        results.live to Text.SearchLive,
        results.anime to Text.SearchAnime,
        results.manga to Text.SearchManga,
    ).filter { (source, _) -> source.failed }.forEach { (_, name) -> SourceUnavailable(name) }
}

@Composable
private fun SourceUnavailable(name: Text) {
    Label(tr(Text.SearchSourceUnavailable, tr(name)), Type.Meta, Palette.Secondary, maxLines = 2)
}

@Composable
private fun LiveResultCard(hit: TvCatalog.Hit, onClick: () -> Unit) {
    val title = hit.program?.title ?: TvCatalog.shortLabel(hit.channel.name)
    val detail = hit.program?.let { "${TvCatalog.shortLabel(hit.channel.name)} · ${clock(it.start)}–${clock(it.end)}" } ?: tr(Text.SearchLive)
    FocusCard(560.dp, 160.dp, { onClick() }, corner = 32.dp) { focused ->
        val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.RowFill)
        Row(Modifier.fillMaxSize().background(fill).padding(20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            // The channel's logo on a dark plate, so a white logo still shows on the white focus fill.
            Box(Modifier.size(128.dp, 96.dp).clip(RoundedCornerShape(14.dp)).background(androidx.compose.ui.graphics.Color(0xFF1C1C1E)).padding(10.dp)) {
                com.sokoul.pc.live.ChannelLogo(hit.channel.name, hit.channel.logo, Modifier.fillMaxSize(), guideId = hit.channel.epgKey)
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                // Dark on the white focus fill: the title stayed white and vanished on the TV (2026-11-18).
                Label(title, Type.Meta.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), if (focused) Palette.OnFocus else Palette.OnBackground, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Label(detail, Type.Body, if (focused) Palette.OnFocus.copy(alpha = 0.72f) else Palette.Secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun WorkResultCard(work: AniTitle, onClick: (CardOrigin) -> Unit) {
    FocusCard(560.dp, 160.dp, onClick, corner = 32.dp) { focused ->
        val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.RowFill)
        Row(Modifier.fillMaxSize().background(fill).padding(20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Art(work.cover, Modifier.size(80.dp, 120.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.fillMaxWidth()) {
                Label(work.title, Type.Meta.copy(fontSize = 28.sp, fontWeight = FontWeight.Normal), if (focused) Palette.OnFocus else Palette.OnBackground, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                val details = listOfNotNull(
                    work.year?.toString(),
                    if (work.kind == MediaKind.ANIME) work.format?.replace('_', ' ') else work.chapters?.let { "$it ${if (it == 1) "chapter" else "chapters"}" },
                ).joinToString(" · ")
                SingleLine(details, Type.Body, if (focused) Palette.OnFocus.copy(alpha = 0.72f) else Palette.Secondary)
            }
        }
    }
}
