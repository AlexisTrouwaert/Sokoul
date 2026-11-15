package com.sokoul.pc.collections

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.data.net.TmdbPerson
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.FilmFacts
import com.sokoul.pc.data.RecurringActor
import com.sokoul.pc.data.SagaDetail
import com.sokoul.pc.data.SagaFacts
import com.sokoul.pc.data.SagaProfile
import com.sokoul.pc.data.SagaTile
import com.sokoul.pc.data.TitleExtras
import com.sokoul.pc.home.TitleLogo
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.durationLabel
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.theme.CardMetrics
import com.sokoul.pc.theme.InterDisplay
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.title.PersonCard
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.CardRow
import com.sokoul.pc.ui.FocusCard
import com.sokoul.pc.ui.FocusSource
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.PillButton
import com.sokoul.pc.ui.PlayPill
import com.sokoul.pc.ui.PosterCard
import com.sokoul.pc.ui.SingleLine
import com.sokoul.pc.ui.SmartLandscape
import com.sokoul.pc.ui.keepClearOfEdges
import com.sokoul.pc.ui.tvFocusable
import java.util.Locale

/** The screen's height: each room fills it. */
internal val RoomHeight = 1080.dp
private val StatWidth = 414.dp
private val StatHeight = 232.dp
private val FilmThumbWidth = 320.dp
private val FilmThumbHeight = 180.dp
private val GalleryWidth = 720.dp
private val GalleryHeight = 405.dp
private const val MAX_BARS = 12
/** The first images of the gallery are the rooms' backgrounds. */
private const val ART_AFTER_ROOMS = 5
private const val OVERVIEW_LINES = 13

/** One figure of the saga. Its value is drawn from 0 to 1 so that it can count up as the room comes into view. */
private data class Stat(val label: String, val image: String?, val tag: String, val value: (Float) -> String, val open: ((CardOrigin) -> Unit)? = null)

/** The rooms of the museum, in visiting order. A room with nothing to show is not on the tour. */
enum class SagaRoom(val label: Text) { Work(Text.RoomWork), Films(Text.RoomFilms), Awards(Text.RoomAwards), Faces(Text.RoomFaces), Behind(Text.RoomBehind), Next(Text.RoomNext) }

internal fun roomsOf(p: SagaProfile?, series: List<TmdbItem>): List<SagaRoom> {
    if (p == null) return if (series.isEmpty()) emptyList() else listOf(SagaRoom.Next)
    return buildList {
        if (p.overview != null || p.boxOffice != null) add(SagaRoom.Work)
        if (p.films.isNotEmpty()) add(SagaRoom.Films)
        if (p.films.count { it.info?.revenue != null } >= 2 || p.oscarsWon > 0 || p.awardsWon > 0) add(SagaRoom.Awards)
        if (p.cast.isNotEmpty() || p.directorPeople.isNotEmpty()) add(SagaRoom.Faces)
        if (p.trailers.isNotEmpty() || p.gallery.isNotEmpty()) add(SagaRoom.Behind)
        if (series.isNotEmpty() || p.similar.isNotEmpty()) add(SagaRoom.Next)
    }
}

/**
 * The saga's museum, below its entrance: one room per screen, each with its own artwork, the information first. The
 * arrows go from room to room. [onRoom] says which one is being visited, and each counts up its figures on arrival.
 */
@Composable
internal fun SagaRooms(
    deps: AppDeps,
    tile: SagaTile,
    profile: SagaProfile?,
    visited: Set<SagaRoom>,
    onRoom: (SagaRoom, Int) -> Unit,
    onFocused: (Boolean) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit,
    onOpenPerson: (TmdbPerson, CardOrigin) -> Unit,
    onPlayTrailer: (String, String) -> Unit,
) {
    val detail by produceState(SagaDetail(tile.films, emptyList()), tile.key) { value = deps.catalog.sagaDetail(tile) }
    val rooms = roomsOf(profile, detail.series)
    val tops = remember { mutableStateMapOf<SagaRoom, Int>() }
    Column(Modifier.fillMaxWidth().onFocusChanged { onFocused(it.hasFocus) }) {
        rooms.forEach { room ->
            val images = roomImages(profile, tile)
            RoomFrame(room, images[room] ?: tile.backdrop, Modifier.onGloballyPositioned { tops[room] = it.positionInParent().y.toInt() }, { onRoom(room, tops[room] ?: 0) }) {
                val reveal by androidx.compose.animation.core.animateFloatAsState(
                    if (room in visited) 1f else 0f,
                    androidx.compose.animation.core.tween(REVEAL_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                )
                val p = profile
                when (room) {
                    SagaRoom.Work -> if (p != null) WorkRoom(p, reveal, onOpenTitle)
                    SagaRoom.Films -> if (p != null) FilmsRoom(deps, tile, p, onPlay, onOpenTitle)
                    SagaRoom.Awards -> if (p != null) AwardsRoom(p, reveal, onOpenTitle)
                    SagaRoom.Faces -> if (p != null) FacesRoom(p, onOpenPerson)
                    SagaRoom.Behind -> if (p != null) BehindRoom(p, onPlayTrailer)
                    SagaRoom.Next -> NextRoom(deps, detail.series, p?.similar.orEmpty(), onOpenTitle)
                }
            }
        }
    }
}

private const val REVEAL_MS = 1400

private fun roomImages(p: SagaProfile?, tile: SagaTile): Map<SagaRoom, String?> {
    val g = p?.gallery.orEmpty()
    return mapOf(
        SagaRoom.Work to (g.getOrNull(0) ?: tile.backdrop),
        SagaRoom.Awards to (p?.biggest?.film?.backdrop ?: g.getOrNull(1)),
        SagaRoom.Faces to (g.getOrNull(2) ?: tile.backdrop),
        SagaRoom.Behind to (g.getOrNull(3) ?: tile.backdrop),
        SagaRoom.Next to (g.getOrNull(4) ?: tile.backdrop),
    )
}

@Composable
private fun RoomFrame(room: SagaRoom, image: String?, modifier: Modifier, onEnter: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.fillMaxWidth().heightIn(min = RoomHeight).testTag("room-${room.name}")
            .onFocusChanged { if (it.hasFocus) onEnter() },
    ) {
        Art(image, Modifier.matchParentSize(), placeholder = Color.Black)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black, 0.18f to Color(0xD9000000), 0.82f to Color(0xD9000000), 1f to Color.Black)))
        content()
    }
}

@Composable
private fun RoomTitle(room: SagaRoom) {
    BasicText(tr(room.label), Modifier.padding(start = Metrics.PageMargin, top = 90.dp, bottom = 40.dp), style = Type.RowTitle.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 58.sp, color = Color.White))
}

// --- The work

@Composable
private fun WorkRoom(p: SagaProfile, reveal: Float, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        RoomTitle(SagaRoom.Work)
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.PageMargin), horizontalArrangement = Arrangement.spacedBy(60.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                p.overview?.let { Label(it, Type.Meta.copy(fontSize = 31.sp, lineHeight = 45.sp, fontWeight = FontWeight.Normal), maxLines = OVERVIEW_LINES) }
                p.overviewSource?.let { Label(tr(Text.SourceCredit, it), Type.Body, Palette.Secondary) }
                Fact(Text.Genres, p.genres.take(5).joinToString(" · "))
                Fact(Text.Director, p.directors.take(4).joinToString(", "))
                Fact(Text.Studios, p.studios.joinToString(", "))
            }
            val stats = workStats(p)
            Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
                stats.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) { pair.forEach { StatTile(it, reveal, onOpenTitle = onOpenTitle) } }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: Text, value: String) {
    if (value.isBlank()) return
    Column {
        Label(tr(label), Type.Body, Palette.Secondary)
        Label(value, Type.Meta, maxLines = 2)
    }
}

private fun workStats(p: SagaProfile): List<Stat> {
    val art = p.films.map { it.film.backdrop }
    return buildList {
        p.boxOffice?.let { v -> add(Stat(tr(Text.StatBoxOffice), p.biggest?.film?.backdrop, "stat-box-office", { r -> SagaFacts.money((v * r).toLong()) })) }
        add(Stat(tr(Text.StatFilms), art.getOrNull(1), "stat-films", { r -> "${(p.films.size * r).toInt()}" }))
        p.runtimeMin?.let { v -> add(Stat(tr(Text.StatRuntime), art.getOrNull(2), "stat-runtime", { r -> tr(Text.HoursLong, (v * r / 60).toInt()) })) }
        p.imdb?.let { v -> add(Stat(tr(Text.StatImdb), art.getOrNull(3), "stat-imdb", { r -> String.format(Locale.getDefault(), "%.1f", v * r) })) }
    }
}

/** A large figure over the dimmed artwork of a film. */
@Composable
private fun StatTile(stat: Stat, reveal: Float, width: Dp = StatWidth, height: Dp = StatHeight, onOpenTitle: ((List<TmdbItem>, Int, CardOrigin?) -> Unit)? = null) {
    FocusCard(width, height, { stat.open?.invoke(it) }, tag = stat.tag) {
        Art(stat.image, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x8C000000), 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val text = stat.value(reveal)
            val size = if (text.length > 12) 34.sp else 62.sp
            BasicText(text, style = Type.RowTitle.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.1f, color = Color.White), maxLines = 2)
            SingleLine(stat.label, Type.Meta, Color(0xCCFFFFFF))
        }
    }
}

// --- The films, one at a time

@Composable
private fun FilmsRoom(deps: AppDeps, tile: SagaTile, p: SagaProfile, onPlay: (PlayRequest) -> Unit, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    val films = p.films
    var selected by remember(tile.key) { mutableStateOf(0) }
    val facts = films.getOrNull(selected) ?: return
    val extras by produceState(TitleExtras(), facts.film.id) { value = deps.catalog.titleExtras("movie", facts.film.id, facts.info) }
    val list = rememberLazyListState()
    Box(Modifier.fillMaxWidth().height(RoomHeight)) {
        Crossfade(facts.info?.backdrop ?: facts.film.backdrop, Modifier.fillMaxSize()) { image -> Art(image, Modifier.fillMaxSize(), placeholder = Color.Black) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE6000000), 0.62f to Color(0x4D000000), 1f to Color(0x1A000000))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black, 0.2f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black)))
        Column(Modifier.padding(start = Metrics.PageMargin, top = 90.dp).width(1020.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TitleLogo(facts.info?.logo, facts.film.title)
            Label(listOfNotNull(facts.film.year?.toString(), facts.info?.runtimeMin?.takeIf { it > 0 }?.let { durationLabel(it) }, facts.info?.director).joinToString(" · "), Type.Meta)
            Ratings(facts)
            (facts.film.overview ?: facts.info?.overview)?.let { Label(it, Type.Meta.copy(fontWeight = FontWeight.Normal), Color(0xE6FFFFFF), maxLines = 5) }
            val money = listOfNotNull(
                facts.info?.revenue?.let { "${tr(Text.StatBoxOffice)} ${SagaFacts.money(it)}" },
                facts.info?.budget?.let { "${tr(Text.StatBudget)} ${SagaFacts.money(it)}" },
            ).joinToString(" · ")
            if (money.isNotEmpty()) Label(money, Type.Meta, Palette.Secondary)
            awardsLine(facts)?.let { Label(it, Type.Meta, Palette.Secondary, maxLines = 1) }
            if (extras.offers.isNotEmpty()) Label(tr(Text.AlsoOn, extras.offers.take(4).joinToString(", ") { it.service }), Type.Meta, Palette.Secondary, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayPill(tr(Text.Watch), { onPlay(PlayRequest("movie", facts.film.id, null, null, facts.film.title, tile.name, facts.film.backdrop)) }, Modifier.testTag("film-watch"))
                PillButton(tr(Text.FilmPage), { onOpenTitle(films.map { it.film }, selected, null) }, Modifier.testTag("film-page"))
            }
        }
        LazyRow(
            Modifier.align(Alignment.BottomStart).padding(bottom = 50.dp).testTag("films-row"),
            state = list,
            contentPadding = PaddingValues(horizontal = Metrics.PageMargin, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            itemsIndexed(films, key = { _, f -> f.film.id }) { i, f ->
                Box(Modifier.keepClearOfEdges(list, i)) {
                    FocusCard(FilmThumbWidth, FilmThumbHeight, { onOpenTitle(films.map { it.film }, i, it) }, tag = "film-$i", onFocus = { if (it) selected = i }) {
                        SmartLandscape(deps.catalog, f.film)
                    }
                }
            }
        }
    }
}

/** OMDb's figures in the interface's language: its own sentence is in English. */
private fun awardsLine(f: FilmFacts): String? {
    val o = f.omdb ?: return null
    return listOfNotNull(
        o.oscarsWon.takeIf { it > 0 }?.let { if (it == 1) tr(Text.OneOscar) else tr(Text.OscarsCount, it) },
        o.oscarNominations.takeIf { it > 0 }?.let { if (it == 1) tr(Text.OneOscarNomination) else tr(Text.OscarNominationsCount, it) },
        o.awardsWon.takeIf { it > 0 }?.let { if (it == 1) tr(Text.OneAward) else tr(Text.AwardsCount, it) },
    ).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun Ratings(f: FilmFacts) {
    val chips = listOfNotNull(
        f.omdb?.imdb?.let { "IMDb %.1f".format(it) },
        f.omdb?.rottenTomatoes?.let { "Rotten Tomatoes $it %" },
        f.omdb?.metacritic?.let { "Metacritic $it" },
        f.film.rating?.let { "TMDB %.1f".format(it) },
    )
    if (chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        chips.forEach { chip ->
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 18.dp, vertical = 7.dp)) { Label(chip, Type.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1) }
        }
    }
}

// --- The awards

@Composable
private fun AwardsRoom(p: SagaProfile, reveal: Float, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    val films = p.films.map { it.film }
    fun openFilm(film: TmdbItem): (CardOrigin) -> Unit = { origin -> onOpenTitle(films, films.indexOf(film), origin) }
    fun art(i: Int) = p.gallery.getOrNull(i + ART_AFTER_ROOMS) ?: films.getOrNull(i)?.backdrop
    val stats = buildList {
        if (p.oscarsWon > 0) add(Stat(tr(Text.StatOscars), art(0), "stat-oscars", { r -> "${(p.oscarsWon * r).toInt()}" }))
        if (p.awardsWon > 0) add(Stat(tr(Text.StatAwards), art(1), "stat-awards", { r -> "${(p.awardsWon * r).toInt()}" }))
        p.rottenTomatoes?.let { v -> add(Stat(tr(Text.StatCritics), art(2), "stat-critics", { r -> "${(v * r).toInt()} %" })) }
        p.metacritic?.let { v -> add(Stat(tr(Text.StatMetacritic), art(3), "stat-metacritic", { r -> "${(v * r).toInt()}" })) }
        p.budget?.let { v -> add(Stat(tr(Text.StatBudget), art(4), "stat-budget", { r -> SagaFacts.money((v * r).toLong()) })) }
        p.returnOnBudget?.let { v -> add(Stat(tr(Text.StatReturn), art(5), "stat-return", { r -> String.format(Locale.getDefault(), "× %.1f", v * r) })) }
        p.best?.let { f -> add(Stat(tr(Text.StatBest), f.film.backdrop, "stat-best", { f.film.title }, openFilm(f.film))) }
        p.biggest?.let { f -> add(Stat(tr(Text.StatBiggest), f.film.backdrop, "stat-biggest", { f.film.title }, openFilm(f.film))) }
    }
    Column(Modifier.fillMaxWidth()) {
        RoomTitle(SagaRoom.Awards)
        BoxOfficeBars(p, reveal, onOpenTitle)
        Spacer(Modifier.height(36.dp))
        val row = rememberLazyListState()
        LazyRow(state = row, contentPadding = PaddingValues(horizontal = Metrics.PageMargin, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(30.dp), modifier = Modifier.testTag("saga-awards")) {
            itemsIndexed(stats, key = { _, s -> s.tag }) { i, stat ->
                Box(Modifier.keepClearOfEdges(row, i)) { StatTile(stat, reveal) }
            }
        }
    }
}

/** One series, so one neutral color; each bar carries its own value, and each row opens its film. */
@Composable
private fun BoxOfficeBars(profile: SagaProfile, reveal: Float, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    val rows = profile.films.filter { it.info?.revenue != null }.take(MAX_BARS)
    if (rows.size < 2) return
    val most = rows.maxOf { it.info?.revenue ?: 0L }.toFloat()
    val films = rows.map { it.film }
    Column(Modifier.padding(horizontal = Metrics.PageMargin - 16.dp).testTag("saga-box-office"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row ->
            val revenue = row.info?.revenue ?: 0L
            // Bars unfold one after the other.
            val own = (reveal * (rows.size + 3) - i).div(3f).coerceIn(0f, 1f)
            var focused by remember { mutableStateOf(false) }
            val fill by animateColorAsState(if (focused) Color(0x33FFFFFF) else Color.Transparent)
            val origin = remember { FocusRequester() }
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(fill)
                    .tvFocusable(onFocusChanged = { focused = it }, onClick = { onOpenTitle(films, i, CardOrigin(origin, androidx.compose.ui.geometry.Rect.Zero)) }, requester = origin)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(520.dp)) { SingleLine(row.film.title, Type.Meta, if (focused) Palette.OnBackground else Color(0xD9FFFFFF)) }
                Spacer(Modifier.width(24.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    Box(
                        Modifier.width(maxWidth * (revenue / most) * own).height(14.dp)
                            .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                            .background(if (focused) Color.White else Color(0xB3FFFFFF)),
                    )
                }
                Spacer(Modifier.width(24.dp))
                Box(Modifier.width(130.dp), contentAlignment = Alignment.CenterEnd) { Label(SagaFacts.money(revenue), Type.Meta, maxLines = 1) }
            }
        }
    }
}

// --- The faces

@Composable
private fun FacesRoom(p: SagaProfile, onOpenPerson: (TmdbPerson, CardOrigin) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        RoomTitle(SagaRoom.Faces)
        if (p.cast.isNotEmpty()) People(tr(Text.RowRecurringCast), p.cast, "saga-cast", onOpenPerson)
        if (p.directorPeople.isNotEmpty()) People(tr(Text.RowDirectors), p.directorPeople, "saga-directors", onOpenPerson)
    }
}

@Composable
private fun People(title: String, people: List<RecurringActor>, tag: String, onOpenPerson: (TmdbPerson, CardOrigin) -> Unit) {
    CardRow(title, people, key = { "${it.person.id}" }, testTag = tag) { _, actor ->
        PersonCard(actor.person.copy(role = if (actor.films == 1) tr(Text.OneFilm) else tr(Text.FilmsCount, actor.films)), onOpenPerson)
    }
}

// --- Behind the scenes

@Composable
private fun BehindRoom(p: SagaProfile, onPlayTrailer: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RoomTitle(SagaRoom.Behind)
        if (p.trailers.isNotEmpty()) {
            CardRow(tr(Text.RowTrailers), p.trailers, key = { it.video.key }, testTag = "saga-trailers") { _, t ->
                FocusCard(CardMetrics.EpisodeWidth, CardMetrics.TrailerHeight, { onPlayTrailer(t.film.title, t.video.key) }) { focused ->
                    Art("https://i.ytimg.com/vi/${t.video.key}/hqdefault.jpg", Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE6000000))))
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        Label(t.film.title, Type.Meta, maxLines = 2)
                        Icon(Icons.Filled.PlayArrow, null, tint = if (focused) Palette.OnBackground else Palette.Secondary, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        if (p.gallery.isNotEmpty()) {
            CardRow(tr(Text.RowGallery), p.gallery, key = { it }, testTag = "saga-gallery") { i, image ->
                FocusCard(GalleryWidth, GalleryHeight, { }, tag = "gallery-$i") { Art(image, Modifier.fillMaxSize()) }
            }
        }
    }
}

// --- And next

@Composable
private fun NextRoom(deps: AppDeps, series: List<TmdbItem>, similar: List<TmdbItem>, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RoomTitle(SagaRoom.Next)
        if (series.isNotEmpty()) {
            CardRow(tr(Text.SameUniverse), series, key = { it.id }, testTag = "saga-series") { i, item ->
                PosterCard(deps.catalog, item, null, captionAlways = false, rank = null, onClick = { onOpenTitle(series, i, it) })
            }
        }
        if (similar.isNotEmpty()) {
            CardRow(tr(Text.RowIfYouLiked), similar, key = { it.id }, testTag = "saga-similar") { i, item ->
                PosterCard(deps.catalog, item, null, captionAlways = false, rank = null, onClick = { onOpenTitle(similar, i, it) })
            }
        }
    }
}

/** A discreet marker of where the visit is. */
@Composable
internal fun RoomMarker(rooms: List<SagaRoom>, current: SagaRoom?, modifier: Modifier = Modifier) {
    Column(modifier.testTag("room-marker"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rooms.forEach { room ->
            val here = room == current
            Box(Modifier.size(if (here) 12.dp else 8.dp).clip(CircleShape).background(if (here) Color.White else Color(0x66FFFFFF)))
        }
    }
}

/** The room a focus move should bring to the top of the screen, unless the mouse is in charge. */
internal fun keyboardMoves() = !FocusSource.lastWasMouse
