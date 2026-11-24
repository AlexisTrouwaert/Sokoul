package com.sokoul.pc.live.sport

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.LiveLogic
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.pc.AppDeps
import com.sokoul.pc.Platform
import com.sokoul.pc.data.L
import com.sokoul.pc.live.ChannelLogo
import com.sokoul.pc.live.SportsEvent
import com.sokoul.pc.live.SportsFailure
import com.sokoul.pc.live.SportsServer
import com.sokoul.pc.live.SportsSources
import com.sokoul.pc.live.TvCatalog
import com.sokoul.pc.live.label
import com.sokoul.pc.playback.session.LiveChannel
import com.sokoul.pc.playback.sources.LocalServerKey
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val sportsGroup = Regex("\\bsports?\\b", RegexOption.IGNORE_CASE)

/** A sports channel of the provider's guide, with what it shows now. */
internal data class SportChannel(val channel: Channel, val nowNext: LiveLogic.NowNext?)

/**
 * The Sport page (Figma "V2 · Sport · TV · héros / défilé / match"), adapted to Sokoul: a match in the hero, its clubs'
 * colours and crests, then the live matches, tonight's, the results, tomorrow's, the sports channels on air and the
 * competitions. OK on a match opens "Where to watch": the Sokoul servers that have it and the provider's channels that
 * show it. Matches come from ESPN's public scoreboards; the streams stay Sokoul's.
 */
@Composable
internal fun SportHome(
    deps: AppDeps,
    first: FocusRequester,
    active: Boolean,
    onPlay: (String, StreamRequest, String, String?, () -> Boolean) -> Unit,
    onLive: (LiveChannel, List<LiveChannel>) -> Unit,
) {
    val profile = deps.profiles.current
    val scores = remember(deps) { SportScores(deps.smallHttp) }
    val source = remember(deps) {
        SportsSources(deps.smallHttp,
            { ProviderConfig.key(deps.db, "mainapi_url") ?: ProviderConfig.MAINAPI_DEFAULT_URL.takeUnless { Platform.IS_TV } },
            { ProviderConfig.key(deps.db, "mainapi") ?: LocalServerKey.read() })
    }
    var refresh by remember { mutableIntStateOf(0) }
    var updatedAt by remember { mutableStateOf<Long?>(null) }
    val matches by produceState<List<SportMatch>?>(null, refresh, profile.id) {
        if (profile.kids) { value = emptyList(); return@produceState }
        value = scores.matches(force = refresh > 0)
        updatedAt = System.currentTimeMillis()
    }
    val channels by produceState(emptyList<SportChannel>(), profile.id, refresh) {
        value = if (profile.kids) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                val sports = deps.tv.groups().asSequence()
                    .filter { !it.locked && sportsGroup.containsMatchIn(it.name) }
                    .flatMap { deps.tv.channels(it.id).asSequence() }
                    .distinctBy { it.nameKey }.take(MAX_CHANNELS).toList()
                val programs = deps.tv.programs(sports)
                sports.map { SportChannel(it, programs[it.id]) }
            }.getOrDefault(emptyList())
        }
    }
    // What the owner's channels show now as a game, read from their guide: covers sports ESPN does not.
    val onAir by produceState(emptyList<Pair<Channel, com.sokoul.desktop.data.Program>>(), profile.id, refresh) {
        value = if (profile.kids) emptyList() else withContext(Dispatchers.IO) {
            SportGuideMatch.liveGames({ word, from, until -> deps.tv.searchTv(word, true, 0, from, until).hits })
        }
    }
    var kind by remember(profile.id) { mutableStateOf<SportKind?>(null) }
    var league by remember(profile.id) { mutableStateOf<SportLeague?>(null) }
    var opened by remember(profile.id) { mutableStateOf<SportMatch?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val tab = LocalActiveTab.current

    val all = matches.orEmpty()
    val shown = all.filter { (kind == null || it.league.kind == kind) && (league == null || it.league == league) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    fun day(m: SportMatch) = Instant.ofEpochMilli(m.start).atZone(zone).toLocalDate()
    val live = shown.filter { it.state == MatchState.Live }.sortedBy { it.league.ordinal }
    val tonight = shown.filter { it.state == MatchState.Upcoming && day(it) == today }.sortedBy { it.start }
    val tomorrow = shown.filter { it.state == MatchState.Upcoming && day(it) == today.plusDays(1) }.sortedBy { it.start }
    val results = shown.filter { it.state == MatchState.Finished }.sortedByDescending { it.start }
    // The hero: what is being played, else what starts next, else what has just ended.
    val heroes = (live + tonight + tomorrow + results).distinctBy { it.id }.take(HERO_COUNT)

    val openMatch: (SportMatch) -> Unit = { opened = it }
    val playChannel: (Channel) -> Unit = { channel ->
        scope.launch {
            // A channel found in the guide may be outside the provider's "sport" groups: it then plays on its own.
            val all2 = withContext(Dispatchers.IO) { deps.tv.toLive(channels.map { it.channel }) }
            val own = all2.firstOrNull { it.key == channel.id } ?: withContext(Dispatchers.IO) { deps.tv.toLive(channel) }
            if (own != null && deps.profiles.current.id == profile.id && !deps.profiles.current.kids && active) {
                onLive(own, if (own in all2) all2 else listOf(own))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background).testTag("sports-screen")) {
        LazyColumn(Modifier.fillMaxSize().testTag("sports-events"), state = list, contentPadding = PaddingValues(bottom = 96.dp)) {
            when {
                profile.kids -> item("kids") { Notice(L("Choose an adult profile to browse these external sports sources.", "Choisissez un profil adulte pour consulter ces sources sportives externes.").text()) }
                matches == null -> item("loading") { Notice(L("Loading sports…", "Chargement du sport…").text()) }
                else -> {
                    if (heroes.isNotEmpty()) item("hero") { MatchHero(heroes, first, onWatch = openMatch) }
                    else item("none") { Notice(L("No events are listed right now.", "Aucune rencontre n’est annoncée pour le moment.").text(), top = 160.dp) }
                    item("filters") {
                        FilterRow(kind, league, updatedAt, Modifier.focusProperties { if (tab != null && heroes.isEmpty()) up = tab },
                            onKind = { kind = it; league = null }, onLeague = { league = null }, onRefresh = { refresh++ },
                            firstRequester = if (heroes.isEmpty()) first else null)
                    }
                    if (live.isNotEmpty()) item("live") { MatchRow(L("Live", "En direct").text(), "live", live, openMatch) }
                    if (tonight.isNotEmpty()) item("tonight") { MatchRow(L("Today", "Aujourd'hui").text(), "today", tonight, openMatch) }
                    if (results.isNotEmpty()) item("results") { MatchRow(L("Results", "Résultats").text(), "results", results, openMatch) }
                    if (tomorrow.isNotEmpty()) item("tomorrow") { MatchRow(L("Tomorrow", "Demain").text(), "tomorrow", tomorrow, openMatch) }
                    if (onAir.isNotEmpty()) item("on-air") {
                        CardRow(L("On your channels now", "Sur vos chaînes maintenant").text(), onAir, key = { "${it.first.id}:${it.second.start}" }, testTag = "row-sport-on-air") { _, (c, p) ->
                            ChannelCard(SportChannel(c, LiveLogic.NowNext(p, null))) { playChannel(c) }
                        }
                    }
                    if (channels.isNotEmpty()) item("channels") {
                        CardRow(L("Sports channels on air", "Chaînes sport en direct").text(), channels, key = { it.channel.id }, testTag = "row-sport-channels") { _, c ->
                            ChannelCard(c) { playChannel(c.channel) }
                        }
                    }
                    val leagues = SportLeague.entries.filter { l -> all.any { it.league == l } }
                    if (leagues.isNotEmpty() && league == null) item("competitions") {
                        CardRow(L("Competitions", "Compétitions").text(), leagues, key = { it.name }, testTag = "row-competitions") { _, l ->
                            CompetitionTile(l, all.filter { it.league == l }) {
                                league = l
                                kind = null
                                scope.launch { list.animateScrollToItem(1) }
                            }
                        }
                    }
                }
            }
        }
        opened?.let { m ->
            WhereToWatch(deps, m, source, channels, onClose = { opened = null }, onChannel = { opened = null; playChannel(it) }) { id, stream, title ->
                onPlay(id, stream, title, m.home.logo) { deps.profiles.current.id == profile.id && !deps.profiles.current.kids }
            }
        }
    }
}

@Composable
private fun Notice(text: String, top: Dp = 140.dp) {
    Box(Modifier.fillMaxWidth().padding(start = Metrics.PageMargin, end = Metrics.PageMargin, top = top)) { TvPanelNotice(text) }
}

/** The match in the hero: its clubs' colours on a slant, the crests large, the score, how it stands, and Watch. */
@Composable
private fun MatchHero(matches: List<SportMatch>, first: FocusRequester, onWatch: (SportMatch) -> Unit) {
    var index by remember(matches.map { it.id }) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    // Another match every eight seconds, but never under the viewer's eyes: focus on the hero stops it.
    LaunchedEffect(matches.size, focused) {
        if (matches.size < 2 || focused) return@LaunchedEffect
        while (true) {
            delay(HERO_TURN_MS)
            index = (index + 1) % matches.size
        }
    }
    val m = matches[index.coerceIn(0, matches.lastIndex)]
    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT).testTag("sport-hero")) {
        AnimatedContent(m, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "hero") { shown ->
            HeroArt(shown, Modifier.fillMaxSize())
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = Metrics.PageMargin, bottom = 72.dp).width(760.dp)
                .onFocusChanged { focused = it.hasFocus }
                .onPreviewKeyEvent { e ->
                    // At the edge of the buttons, left and right change the match, as on the mock-up.
                    if (e.type != KeyEventType.KeyDown || matches.size < 2) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> { index = (index + 1) % matches.size; true }
                        Key.DirectionLeft -> { index = (index - 1 + matches.size) % matches.size; true }
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Label(m.league.label.uppercase(), Type.RowTitle.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp), maxLines = 1)
                StatusPill(m)
            }
            TeamLine(m.home, m.scored)
            TeamLine(m.away, m.scored)
            Label(listOfNotNull(startLine(m), m.venue).joinToString(" · "), Type.Meta, Palette.Secondary, maxLines = 1)
            m.broadcasts.takeIf { it.isNotEmpty() }?.let { Label(broadcastLine(m), Type.Body, Palette.Secondary, maxLines = 1) }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(L("Watch", "Regarder").text(), { onWatch(m) }, Modifier.testTag("sport-hero-watch"), first)
            }
            if (matches.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                matches.indices.forEach { i ->
                    Box(Modifier.height(8.dp).width(if (i == index) 32.dp else 8.dp).clip(CircleShape).background(if (i == index) Palette.OnBackground else Color(0x66FFFFFF)))
                }
            }
        }
    }
}

@Composable
private fun TeamLine(team: SportTeam, scored: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Art(team.logo, Modifier.size(72.dp), scale = ContentScale.Fit, placeholder = Color.Transparent)
        Label(team.abbr, Type.RowTitle.copy(fontSize = 84.sp, fontWeight = FontWeight.Black), modifier = Modifier.width(300.dp), maxLines = 1)
        if (scored) Label(team.score ?: "–", Type.RowTitle.copy(fontSize = 84.sp, fontWeight = FontWeight.Black), maxLines = 1)
    }
}

/** "● LIVE · 5'", "20:45", "Sun. 15:00" or "Full time", in a dark capsule; red only for live. */
@Composable
private fun StatusPill(m: SportMatch, small: Boolean = false) {
    val text = statusText(m)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xB3000000)).padding(horizontal = if (small) 14.dp else 18.dp, vertical = if (small) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (m.state == MatchState.Live) Box(Modifier.size(if (small) 10.dp else 12.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
        Label(text, Type.Body.copy(fontWeight = FontWeight.Bold), Palette.OnBackground, maxLines = 1)
    }
}

internal fun statusText(m: SportMatch, now: Long = System.currentTimeMillis()): String = when (m.state) {
    MatchState.Live -> L("LIVE", "EN DIRECT").text() + (m.clock?.let { " · $it" } ?: "")
    MatchState.Finished -> L("Full time", "Terminé").text()
    MatchState.Off -> L("Postponed", "Reporté").text()
    MatchState.Upcoming -> {
        val at = Instant.ofEpochMilli(m.start).atZone(ZoneId.systemDefault())
        val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
        if (at.toLocalDate() == LocalDate.now(ZoneId.systemDefault())) time
        else at.format(DateTimeFormatter.ofPattern("EEE", java.util.Locale.getDefault())).replaceFirstChar { it.uppercase() } + " " + time
    }
}

private fun broadcastLine(m: SportMatch): String =
    (if (m.usualBroadcasts) L("Usually on ", "Habituellement sur ") else L("On ", "Diffusé sur ")).text() + m.broadcasts.joinToString(", ")

private fun startLine(m: SportMatch): String? = when (m.state) {
    MatchState.Upcoming, MatchState.Live -> (if (m.league.kind == SportKind.Football) L("Kick-off ", "Coup d'envoi ") else L("Tip-off ", "Début ")).text() + Instant.ofEpochMilli(m.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    else -> null
}

/** The two colours on a slant with the crests large on the right; black on the left, where the text is. */
@Composable
private fun HeroArt(m: SportMatch, modifier: Modifier) {
    Box(modifier) {
        val home = teamColor(m.home)
        val away = teamColor(m.away)
        val upright = Form.tabletPortrait || Form.isPortrait
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(Color.Black)
            if (upright) {
                // Upright, the two colours split the whole width, the slant through the middle.
                drawPath(Path().apply { moveTo(0f, 0f); lineTo(w * 0.58f, 0f); lineTo(w * 0.42f, h); lineTo(0f, h); close() }, home)
                drawPath(Path().apply { moveTo(w * 0.58f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w * 0.42f, h); close() }, away)
            } else {
            drawPath(Path().apply { moveTo(w * 0.40f, 0f); lineTo(w * 0.78f, 0f); lineTo(w * 0.62f, h); lineTo(w * 0.24f, h); close() }, home)
            drawPath(Path().apply { moveTo(w * 0.78f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w * 0.62f, h); close() }, away)
            }
            // The text side stays dark whatever the colours: black to transparent over the first half.
            if (upright) drawRect(Brush.verticalGradient(0.35f to Color.Transparent, 0.6f to Color(0xCC000000), 1f to Color.Black))
            else drawRect(Brush.horizontalGradient(0f to Color.Black, 0.30f to Color.Black, 0.55f to Color(0x99000000), 0.75f to Color.Transparent))
            drawRect(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xE6000000)))
        }
        if (Form.tabletPortrait || Form.isPortrait) {
            // Upright: the crests side by side at the top, the text keeps the bottom.
            Row(Modifier.align(Alignment.TopCenter).padding(top = 150.dp), horizontalArrangement = Arrangement.spacedBy(120.dp)) {
                Art(m.home.logo, Modifier.size(240.dp), scale = ContentScale.Fit, placeholder = Color.Transparent)
                Art(m.away.logo, Modifier.size(240.dp), scale = ContentScale.Fit, placeholder = Color.Transparent)
            }
        } else {
            Art(m.home.logo, Modifier.align(Alignment.CenterEnd).padding(end = 560.dp, bottom = 80.dp).size(HERO_CREST), scale = ContentScale.Fit, placeholder = Color.Transparent)
            Art(m.away.logo, Modifier.align(Alignment.CenterEnd).padding(end = 140.dp, bottom = 80.dp).size(HERO_CREST), scale = ContentScale.Fit, placeholder = Color.Transparent)
        }
    }
}

/** A club's colour, darkened so that white text and crests stay readable; grey when the club has none. */
private fun teamColor(t: SportTeam): Color {
    val c = Color(t.color ?: 0xFF2C2C2E)
    // Very light colours (white shirts) are brought down to a light grey, as the mock-up does for Auxerre.
    val light = c.red * 0.3f + c.green * 0.59f + c.blue * 0.11f
    return if (light > 0.85f) Color(0xFFBDBDC2) else c.copy(red = c.red * 0.85f, green = c.green * 0.85f, blue = c.blue * 0.85f)
}

@Composable
private fun FilterRow(
    kind: SportKind?, league: SportLeague?, updatedAt: Long?, modifier: Modifier,
    onKind: (SportKind?) -> Unit, onLeague: () -> Unit, onRefresh: () -> Unit, firstRequester: FocusRequester?,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Metrics.PageMargin, end = Metrics.PageMargin, top = 28.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelChip(L("All sports", "Tous les sports").text(), Modifier.testTag("sports-category"), selected = kind == null && league == null,
            requester = firstRequester ?: remember { FocusRequester() }) { onKind(null) }
        PanelChip(L("Football", "Football").text(), Modifier.testTag("sports-category-football"), selected = kind == SportKind.Football) { onKind(SportKind.Football) }
        PanelChip(L("Basketball", "Basket").text(), Modifier.testTag("sports-category-basket"), selected = kind == SportKind.Basketball) { onKind(SportKind.Basketball) }
        if (league != null) PanelChip("${league.label}  ✕", Modifier.testTag("sports-league"), selected = true) { onLeague() }
        Spacer(Modifier.width(18.dp))
        PanelChip(L("Refresh", "Actualiser").text(), Modifier.testTag("sports-refresh"), icon = androidx.compose.material.icons.Icons.Filled.Refresh) { onRefresh() }
        updatedAt?.let {
            Label(L("Updated at ", "Mis à jour à ").text() + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), Type.Body, Palette.Secondary, maxLines = 1)
        }
    }
}

@Composable
private fun MatchRow(title: String, id: String, matches: List<SportMatch>, onOpen: (SportMatch) -> Unit) {
    CardRow(title, matches, key = { it.id }, testTag = "row-sport-$id") { i, m -> MatchCard(m, "match-$id-$i") { onOpen(m) } }
}

/** A match: the colours and crests in 16:9, how it stands above left, the score above right; its name and place under it. */
@Composable
private fun MatchCard(m: SportMatch, tag: String, onClick: () -> Unit) {
    Column(Modifier.width(CARD_W), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FocusCard(CARD_W, CARD_H, { onClick() }, tag = tag) {
            CardArt(m)
            Box(Modifier.align(Alignment.TopStart).padding(14.dp)) { StatusPill(m, small = true) }
            if (m.scored) Box(Modifier.align(Alignment.TopEnd).padding(14.dp).clip(RoundedCornerShape(50)).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Label("${m.home.score ?: "–"} – ${m.away.score ?: "–"}", Type.Body.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        Label(m.title, Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        Label(listOfNotNull(m.league.label, m.venue).joinToString(" · "), Type.Body, Palette.Secondary, maxLines = 1)
    }
}

@Composable
private fun CardArt(m: SportMatch) {
    val home = teamColor(m.home)
    val away = teamColor(m.away)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawPath(Path().apply { moveTo(0f, 0f); lineTo(w * 0.56f, 0f); lineTo(w * 0.44f, h); lineTo(0f, h); close() }, home)
        drawPath(Path().apply { moveTo(w * 0.56f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w * 0.44f, h); close() }, away)
    }
    Row(Modifier.fillMaxSize().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Art(m.home.logo, Modifier.size(CARD_CREST), scale = ContentScale.Fit, placeholder = Color.Transparent) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Art(m.away.logo, Modifier.size(CARD_CREST), scale = ContentScale.Fit, placeholder = Color.Transparent) }
    }
}

/** A sports channel on air: its logo, LIVE, what it shows and how far along, as on the mock-up. */
@Composable
private fun ChannelCard(c: SportChannel, onClick: () -> Unit) {
    val now = c.nowNext?.now
    Column(Modifier.width(CARD_W), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FocusCard(CARD_W, CARD_H, { onClick() }, tag = "sport-channel-${c.channel.id}") {
            Box(Modifier.fillMaxSize().background(Color(0xFF1C1C1E)))
            ChannelLogo(c.channel.name, c.channel.logo, Modifier.align(Alignment.TopStart).padding(18.dp).size(140.dp, 64.dp), guideId = c.channel.epgKey)
            Row(Modifier.align(Alignment.TopEnd).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                Label(L("LIVE", "EN DIRECT").text(), Type.Body.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(now?.title ?: L("Programme not listed", "Programme non renseigné").text(), Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                if (now != null) {
                    val f = DateTimeFormatter.ofPattern("HH:mm")
                    val z = ZoneId.systemDefault()
                    Label("${Instant.ofEpochMilli(now.start).atZone(z).format(f)} – ${Instant.ofEpochMilli(now.end).atZone(z).format(f)}", Type.Body, Palette.Secondary, maxLines = 1)
                    Box(Modifier.width(CARD_W - 36.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(c.nowNext.progress(System.currentTimeMillis())).background(Palette.OnBackground))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Label(TvCatalog.shortLabel(c.channel.name), Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        c.nowNext?.next?.let { Label(L("Next: ", "Ensuite : ").text() + it.title, Type.Body, Palette.Secondary, maxLines = 1) }
    }
}

/** A competition: its name large, the crests of its clubs playing these days, how many matches. Opens it as a filter. */
@Composable
private fun CompetitionTile(league: SportLeague, matches: List<SportMatch>, onClick: () -> Unit) {
    FocusCard(CARD_W, COMP_H, { onClick() }, tag = "competition-${league.name}") {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2C2C2E), Color(0xFF121214)), Offset.Zero, Offset(900f, 500f))))
        Row(Modifier.align(Alignment.TopStart).padding(18.dp), horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
            matches.flatMap { listOf(it.home, it.away) }.distinctBy { it.abbr }.take(3).forEach { t ->
                Art(t.logo, Modifier.size(56.dp), scale = ContentScale.Fit, placeholder = Color.Transparent)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            // A long name ("Ligue des champions") goes on two lines rather than being cut.
            Label(league.label.uppercase(), Type.RowTitle.copy(fontWeight = FontWeight.Black, fontSize = if (league.label.length > 14) 30.sp else 36.sp, lineHeight = 34.sp), maxLines = 2)
            Label(L("${matches.size} matches", "${matches.size} matchs").text(), Type.Body, Palette.Secondary, maxLines = 1)
        }
    }
}

/** Accents and case set aside, so that "Paris Saint-Germain" finds "paris saint germain" in a server's title. */
internal fun sportKey(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9+]+"), " ").trim()

/** The Sokoul server's event for this match: both clubs named in its title (a significant word of each). */
internal fun sokoulEvent(m: SportMatch, events: List<SportsEvent>): SportsEvent? {
    fun words(t: SportTeam) = (sportKey(t.name).split(' ') + sportKey(t.abbr)).filter { it.length >= 3 && it !in COMMON }.toSet()
    val home = words(m.home)
    val away = words(m.away)
    return events.firstOrNull { e ->
        val title = sportKey(e.name).split(' ').toSet()
        home.any { it in title } && away.any { it in title }
    }
}

/** The provider's channels that show the match: their name carries a broadcaster's ("beIN Sports" in "beIN SPORTS 1 HD"). */
internal fun broadcasterChannels(m: SportMatch, channels: List<SportChannel>): List<SportChannel> {
    val names = m.broadcasts.map(::sportKey).filter { it.isNotEmpty() }
    if (names.isEmpty()) return emptyList()
    return channels.filter { c -> val k = sportKey(c.channel.name); names.any { k.contains(it) } }
}

private val COMMON = setOf("fc", "afc", "the", "club", "sporting", "united", "city", "real", "stade", "olympique")

/** "Where to watch" (Figma "V2 · Sport · TV · match"): the match on the left, the Sokoul servers and the channels on the right. */
@Composable
private fun WhereToWatch(
    deps: AppDeps,
    m: SportMatch,
    source: SportsSources,
    channels: List<SportChannel>,
    onClose: () -> Unit,
    onChannel: (Channel) -> Unit,
    onStream: (String, StreamRequest, String) -> Unit,
) {
    var serversRefresh by remember { mutableIntStateOf(0) }
    var preparing by remember { mutableStateOf<SportsServer?>(null) }
    var failure by remember { mutableStateOf<SportsFailure?>(null) }
    val found by produceState<Pair<SportsEvent?, List<SportsServer>>?>(null, m.id, serversRefresh) {
        val events = source.events()
        val event = events.value?.let { sokoulEvent(m, it) }
        failure = events.failure
        value = if (event == null) null to emptyList() else {
            val servers = source.servers(event)
            failure = servers.failure
            event to servers.value.orEmpty()
        }
    }
    LaunchedEffect(preparing) {
        val server = preparing ?: return@LaunchedEffect
        val event = found?.first ?: return@LaunchedEffect
        val result = source.resolve(event, server)
        preparing = null
        failure = result.failure
        result.value?.let { onStream("sports:${event.id}:${server.key}", it, m.title) }
    }
    // The owner's guide first: the channels with a programme naming both clubs at the match's time.
    val inGuide by produceState<List<Pair<Channel, com.sokoul.desktop.data.Program>>?>(null, m.id) {
        // One row per channel: "beIN SPORTS 1", "[BK]" and "[BK1]" are the same channel's streams.
        value = withContext(Dispatchers.IO) { SportGuideMatch.find(m) { word, from, until -> deps.tv.searchTv(word, true, 0, from, until).hits } }
            .distinctBy { (c, _) -> TvCatalog.shortLabel(c.name).replace(Regex("""\s*[\[(].*?[\])]"""), "").trim().lowercase() }
    }
    val tvChannels = remember(m.id, channels) { broadcasterChannels(m, channels) }
    Box(
        Modifier.fillMaxSize().testTag("sports-event-details")
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false },
    ) {
        HeroArt(m, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        // Upright (tablet portrait): the match on top, the panel under it, both full width.
        val upright = Form.isPortrait || Form.tabletPortrait
        Column(
            (if (upright) Modifier.align(Alignment.TopStart).padding(start = Metrics.PageMargin, end = Metrics.PageMargin, top = 150.dp).fillMaxWidth()
            else Modifier.align(Alignment.CenterStart).padding(start = Metrics.PageMargin).width(760.dp)),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Label(m.league.label.uppercase(), Type.RowTitle.copy(fontWeight = FontWeight.Black), maxLines = 1)
                StatusPill(m)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Art(m.home.logo, Modifier.size(150.dp), scale = ContentScale.Fit, placeholder = Color.Transparent)
                if (m.scored) Label("${m.home.score ?: "–"} – ${m.away.score ?: "–"}", Type.RowTitle.copy(fontSize = 110.sp, fontWeight = FontWeight.Black), maxLines = 1)
                Art(m.away.logo, Modifier.size(150.dp), scale = ContentScale.Fit, placeholder = Color.Transparent)
            }
            Label(m.title, Type.RowTitle.copy(fontSize = 46.sp), maxLines = 2)
            Label(listOfNotNull(startLine(m), m.venue).joinToString(" · "), Type.Meta, Palette.Secondary, maxLines = 1)
            val shownOn = inGuide?.firstOrNull()?.first
            if (shownOn != null) Label(L("On ", "Sur ").text() + TvCatalog.shortLabel(shownOn.name), Type.Body, Palette.Secondary, maxLines = 1)
            else m.broadcasts.takeIf { it.isNotEmpty() }?.let { Label(broadcastLine(m), Type.Body, Palette.Secondary, maxLines = 1) }
        }
        TvPanel(
            L("Where to watch", "Où regarder").text(), m.title,
            (if (upright) Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp) else Modifier.align(Alignment.CenterEnd).padding(end = 60.dp)).testTag("sport-where"),
            width = if (upright) 1000.dp else 860.dp, height = if (upright) 820.dp else 900.dp,
        ) {
            TvSectionLabel(L("ON YOUR CHANNELS", "SUR VOS CHAÎNES").text())
            val guide = inGuide
            when {
                guide == null -> TvPanelNotice(L("Looking for this match in your TV guide…", "Recherche de ce match dans votre guide TV…").text())
                guide.isNotEmpty() -> guide.forEach { (c, p) ->
                    val f = DateTimeFormatter.ofPattern("HH:mm")
                    val z = ZoneId.systemDefault()
                    TvActionRow(
                        TvCatalog.shortLabel(c.name),
                        "${p.title} · ${Instant.ofEpochMilli(p.start).atZone(z).format(f)} – ${Instant.ofEpochMilli(p.end).atZone(z).format(f)}",
                        Modifier.testTag("sports-guide-${c.id}"),
                        leading = { ChannelLogo(c.name, c.logo, Modifier.size(88.dp, 48.dp), guideId = c.epgKey) },
                    ) { onChannel(c) }
                }
                else -> TvPanelNotice(L("Your TV guide does not list this match.", "Votre guide TV ne mentionne pas ce match.").text())
            }
            if (guide != null && guide.isEmpty() && tvChannels.isNotEmpty()) TvSectionLabel(L("USUAL BROADCASTER", "DIFFUSEUR HABITUEL").text(), Modifier.padding(top = 12.dp))
            if (guide == null || guide.isNotEmpty()) Unit
            else if (tvChannels.isEmpty()) {
                TvPanelNotice(
                    if (m.broadcasts.isEmpty()) L("No broadcaster is announced for this match.", "Aucun diffuseur n’est annoncé pour ce match.").text()
                    else L("None of your channels shows ${m.broadcasts.joinToString()}.", "Aucune de vos chaînes ne correspond à ${m.broadcasts.joinToString()}.").text(),
                )
            } else tvChannels.forEach { c ->
                TvActionRow(TvCatalog.shortLabel(c.channel.name), c.nowNext?.now?.title, Modifier.testTag("sports-tv-${c.channel.id}"),
                    leading = { ChannelLogo(c.channel.name, c.channel.logo, Modifier.size(88.dp, 48.dp), guideId = c.channel.epgKey) }) { onChannel(c.channel) }
            }
            // A Sokoul server only when one exists and has the match: the owner's TV guide is the source otherwise.
            val serverList = found?.second.orEmpty()
            if (serverList.isNotEmpty() || preparing != null) {
                TvSectionLabel(L("SOKOUL SERVERS", "SERVEURS SOKOUL").text(), Modifier.padding(top = 12.dp))
                if (preparing != null) TvPanelNotice(L("Preparing the selected server…", "Préparation du serveur choisi…").text())
                else serverList.forEach { sv ->
                    TvActionRow(sv.title, L("Play in Sokoul", "Lire dans Sokoul").text(), Modifier.testTag("sports-server-${sv.key}")) { failure = null; preparing = sv }
                }
            }

        }
    }
}

private val HERO_HEIGHT = 940.dp
private val HERO_CREST = 300.dp
private val CARD_W = 420.dp
private val CARD_H = 236.dp
private val CARD_CREST = 96.dp
private val COMP_H = 200.dp
private const val HERO_COUNT = 5
private const val HERO_TURN_MS = 8_000L
private const val MAX_CHANNELS = 30
