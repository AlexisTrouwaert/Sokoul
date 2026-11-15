package com.sokoul.pc.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.discovery.Release
import com.sokoul.iptv.discovery.ReleaseCalendar
import com.sokoul.iptv.discovery.Roulette
import com.sokoul.iptv.discovery.RouletteFilters
import com.sokoul.pc.AppDeps
import com.sokoul.pc.anime.Choices
import com.sokoul.pc.anime.ToolTile
import com.sokoul.pc.anime.takeFocusOnOpen
import com.sokoul.pc.data.G
import com.sokoul.pc.data.L
import com.sokoul.pc.data.Platform
import com.sokoul.pc.i18n.Strings
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
import com.sokoul.pc.ui.PillButton
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** The discovery pages opened from the home row. */
enum class DiscoverView { Calendar, Roulette, Review }

private data class DiscoverTool(val name: L, val tag: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val view: DiscoverView)

private val TOOLS = listOf(
    DiscoverTool(L("Release calendar", "Calendrier des sorties"), "tool-releases", Icons.Filled.CalendarMonth, DiscoverView.Calendar),
    DiscoverTool(L("Roulette: let chance choose", "Roulette : laissez le hasard choisir"), "tool-roulette", Icons.Filled.Casino, DiscoverView.Roulette),
    DiscoverTool(L("Your year in review", "Votre année en revue"), "tool-review", Icons.Filled.AutoGraph, DiscoverView.Review),
)

/** The home row of discovery tools, drawn like the anime page's own tools. */
@Composable
fun DiscoverToolsRow(up: FocusRequester?, onActive: (Boolean) -> Unit, focusFor: (Int) -> FocusRequester?, onOpen: (DiscoverView, CardOrigin?) -> Unit) {
    CardRow(L("Discover", "Découvrir").text(), TOOLS, key = { it.tag }, testTag = "row-discover", onActive = onActive, upTarget = up) { i, t ->
        ToolTile(t.name.text(), t.tag, focusFor(i), t.icon) { origin -> onOpen(t.view, origin) }
    }
}

private fun Release.asItem() = TmdbItem(tmdbId, title, null, null, image, image, null, null, mediaType = kind)

/** The page behind each tool, closed with Escape or the back button. */
@Composable
fun DiscoverPage(deps: AppDeps, view: DiscoverView, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit, onClose: () -> Unit) {
    val data = deps.catalog.discover ?: return
    val root = takeFocusOnOpen()
    Box(
        Modifier.fillMaxSize().background(Palette.Background).testTag("discover-${view.name.lowercase()}").onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        }.focusRequester(root).focusable(),
    ) {
        when (view) {
            DiscoverView.Calendar -> CalendarPage(deps, data, onOpenTitle)
            DiscoverView.Roulette -> RoulettePage(data, onOpenTitle)
            DiscoverView.Review -> ReviewPage(data, onOpenTitle)
        }
        BackButton(onClick = onClose, modifier = Modifier.padding(start = 40.dp, top = 44.dp).testTag("discover-back"))
    }
}

@Composable
private fun CalendarPage(deps: AppDeps, data: com.sokoul.pc.data.DiscoverData, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    val today = LocalDate.now()
    var onlyMine by remember { mutableStateOf(false) }
    val releases by produceState<List<Release>?>(null) {
        val mine = deps.myList.items.filter { it.mediaType == "tv" }.map { it.id }
        // Off the interface thread: the database can be busy with a long IPTV sync.
        val watched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { deps.db.history(300) }
        val followed = (mine + watched.filter { it.kind == "tv" }.map { it.tmdbId }).toSet()
        value = runCatching { data.releases(followed, today) }.getOrDefault(emptyList())
    }
    var focused by remember { mutableStateOf<Release?>(null) }
    val days = remember(releases, onlyMine) { ReleaseCalendar.week(releases.orEmpty().filter { !onlyMine || it.followed }, today) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(CALENDAR_HERO)) {
            focused?.image?.let { Art(it, Modifier.fillMaxSize(), placeholder = Palette.Background) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 1f to Palette.Background)))
            Column(Modifier.fillMaxSize().padding(start = Metrics.PageMargin, end = Metrics.PageMargin, top = 110.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(focused?.title ?: L("This week's releases", "Les sorties de la semaine").text(), Type.RowTitle.copy(fontSize = Type.RowTitle.fontSize * 1.4f), maxLines = 1)
                focused?.let { r -> Label(subtitle(r), Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 1) }
                Spacer(Modifier.weight(1f))
                Choices(listOf(false to L("Films and series", "Films et séries"), true to L("My series", "Mes séries")), onlyMine, "releases-filter") { onlyMine = it }
            }
        }
        when {
            releases == null -> Label(L("Loading…", "Chargement…").text(), Type.Body, Palette.Secondary, Modifier.padding(Metrics.PageMargin))
            days.isEmpty() -> Label(L("Nothing announced this week.", "Rien d'annoncé cette semaine.").text(), Type.Body, Palette.Secondary, Modifier.padding(Metrics.PageMargin))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 60.dp)) {
                items(days, key = { it.first.toString() }) { (day, list) ->
                    val name = if (day == today) L("Today", "Aujourd'hui").text() else day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() }
                    CardRow("$name ${day.dayOfMonth}", list, key = { "${it.kind}-${it.tmdbId}-${it.episode}" }, testTag = "releases-$day") { i, r ->
                        ReleaseCard(r, onFocus = { focused = r }) { origin -> onOpenTitle(list.map { it.asItem() }, i, origin) }
                    }
                }
            }
        }
    }
}

private fun subtitle(r: Release): String =
    if (r.kind == "tv") L("Season ${r.season} · Episode ${r.episode}", "Saison ${r.season} · Épisode ${r.episode}").text()
    else L("Film", "Film").text()

@Composable
private fun ReleaseCard(r: Release, onFocus: () -> Unit, onClick: (CardOrigin) -> Unit) {
    FocusCard(CardMetrics.EpisodeWidth, CARD_H, onClick, onFocus = { if (it) onFocus() }) { _ ->
        Art(r.image, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color(0x22000000), 1f to Color(0xF2000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Label(if (r.kind == "tv") L("S${r.season} · E${r.episode}", "S${r.season} · É${r.episode}").text() else L("Film", "Film").text(),
                Type.Body.copy(fontWeight = FontWeight.Bold), if (r.followed) Palette.OnBackground else Palette.Secondary, maxLines = 1)
            Label(r.title, Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

@Composable
private fun RoulettePage(data: com.sokoul.pc.data.DiscoverData, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    val scope = rememberCoroutineScope()
    val random = remember { Random(System.nanoTime()) }
    var kind by remember { mutableStateOf("movie") }
    var genre by remember { mutableStateOf<Int?>(null) }
    var maxRuntime by remember { mutableStateOf<Int?>(null) }
    var platform by remember { mutableStateOf<Platform?>(null) }
    var recent by remember { mutableStateOf(emptyList<Int>()) }
    var drawn by remember { mutableStateOf<TmdbItem?>(null) }
    var empty by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf(false) }
    val spin = {
        if (!spinning) scope.launch {
            spinning = true
            val filters = RouletteFilters(kind, genre, if (kind == "movie") maxRuntime else null, minRating = 6.5, providerId = platform?.providerId, region = Strings.region())
            val first = runCatching { data.roulette(filters, Roulette.page(random, null)) }.getOrDefault(emptyList())
            val pool = first.ifEmpty { runCatching { data.roulette(filters, 1) }.getOrDefault(emptyList()) }
            val pick = Roulette.draw(pool, recent, { it.id }, random)
            drawn = pick
            empty = pick == null
            pick?.let { recent = Roulette.remember(recent, it.id) }
            spinning = false
        }
    }
    LaunchedEffect(Unit) { spin() }
    val genres = if (kind == "movie") {
        listOf(null to L("All", "Tous"), G.ACTION to L("Action", "Action"), G.COMEDY to L("Comedy", "Comédie"), G.DRAMA to L("Drama", "Drame"),
            G.THRILLER to L("Thriller", "Thriller"), G.HORROR to L("Horror", "Horreur"), G.SCIFI to L("Sci-fi", "Science-fiction"), G.ANIMATION to L("Animation", "Animation"))
    } else {
        listOf(null to L("All", "Tous"), G.TV_ACTION to L("Action", "Action"), G.COMEDY to L("Comedy", "Comédie"), G.DRAMA to L("Drama", "Drame"),
            G.CRIME to L("Crime", "Policier"), G.TV_SCIFI to L("Sci-fi", "Science-fiction"), G.ANIMATION to L("Animation", "Animation"))
    }
    Box(Modifier.fillMaxSize()) {
        drawn?.backdrop?.let { Art(it, Modifier.fillMaxSize(), placeholder = Palette.Background) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2000000), 0.65f to Color(0x66000000), 1f to Color(0x22000000))))
        Column(Modifier.fillMaxSize().padding(start = Metrics.PageMargin, end = Metrics.PageMargin, top = 110.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label(L("Roulette", "Roulette").text(), Type.Meta.copy(fontWeight = FontWeight.SemiBold), Palette.Secondary)
            Label(
                when {
                    spinning && drawn == null -> L("Spinning…", "Ça tourne…").text()
                    empty -> L("Nothing matches. Widen the filters.", "Rien ne correspond. Élargissez les filtres.").text()
                    else -> drawn?.title.orEmpty()
                },
                Type.RowTitle.copy(fontSize = Type.RowTitle.fontSize * 1.6f), maxLines = 2, modifier = Modifier.width(1100.dp).testTag("roulette-title"),
            )
            drawn?.let { d ->
                Label(listOfNotNull(d.year?.toString(), d.rating?.let { "★ %.1f".format(it) }).joinToString(" · "), Type.Meta, Palette.BarText)
                d.overview?.let { Label(it, Type.Body, Palette.BarText, maxLines = 3, modifier = Modifier.width(1000.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(L("Spin again", "Relancer").text(), { spin() }, Modifier.testTag("roulette-spin"))
                drawn?.let { d -> PillButton(L("Open", "Voir la fiche").text(), { onOpenTitle(listOf(d), 0, null) }, Modifier.testTag("roulette-open")) }
            }
            Spacer(Modifier.weight(1f))
            Choices(listOf("movie" to L("Film", "Film"), "tv" to L("Series", "Série")), kind, "roulette-kind") { kind = it; genre = null }
            Choices(genres, genre, "roulette-genre") { genre = it }
            if (kind == "movie") Choices(listOf(null to L("Any length", "Toute durée"), 100 to L("Under 1h40", "Moins d'1 h 40"), 120 to L("Under 2h", "Moins de 2 h")), maxRuntime, "roulette-length") { maxRuntime = it }
            Choices(listOf<Pair<Platform?, L>>(null to L("Everywhere", "Partout")) + Platform.entries.filter { it != Platform.Hulu }.map { it to L(it.label, it.label) }, platform, "roulette-platform") { platform = it }
        }
    }
}

private val CALENDAR_HERO = 400.dp
private val CARD_H = 200.dp
