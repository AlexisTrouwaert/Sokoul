package com.sokoul.pc.collections

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.data.net.TmdbPerson
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.CatalogApi
import com.sokoul.pc.data.FilmFacts
import com.sokoul.pc.data.SagaAction
import com.sokoul.pc.data.SagaDetail
import com.sokoul.pc.data.SagaEntry
import com.sokoul.pc.data.SagaFacts
import com.sokoul.pc.data.SagaProfile
import com.sokoul.pc.data.SagaTile
import com.sokoul.pc.data.SagaTimeline
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.durationLabel
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.theme.InterDisplay
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.title.HeroLayer
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.heavyBlur
import com.sokoul.pc.ui.BackButton
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.DominantColor
import com.sokoul.pc.ui.FocusCard
import com.sokoul.pc.ui.FocusSource
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.LocalHoverFocus
import com.sokoul.pc.ui.LocalPinch
import com.sokoul.pc.ui.PlayPill
import com.sokoul.pc.ui.SingleLine
import com.sokoul.pc.ui.SmartLandscape
import com.sokoul.pc.ui.keepClearOfEdges
import com.sokoul.pc.ui.rememberHasFrames
import com.sokoul.pc.ui.tvFocusable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CardWidth = 414.dp
private val CardHeight = 232.dp
private val Step = 444.dp
private val StopSize = 30.dp
private const val SLIDE_DISTANCE = 1720f
private const val ENTER_MILLIS = 720
private const val TEXT_DELAY_MILLIS = 500L
private const val PROFILE_DELAY_MILLIS = 300L
private const val ROTATE_MILLIS = 8000L
private const val DRIFT_MILLIS = 30000
private const val DRIFT_SCALE = 1.08f
private const val REVEAL_SCROLL = 150
private const val ROOM_ALIGN_DELAY_MS = 150L

/**
 * A saga opens like a title on Apple TV, in two steps: the card zooms into a reduced card between its neighbors, then a
 * second activation gives the full page. There, the screen takes the artwork of the film in focus, the films sit on a
 * timeline carrying their years, and everything gathered about the saga follows below.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun SagaScreen(
    deps: AppDeps,
    entries: List<SagaEntry>,
    startIndex: Int,
    origin: CardOrigin?,
    onClose: () -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit,
    onOpenPerson: (TmdbPerson, CardOrigin) -> Unit,
    onPlayTrailer: (String, String) -> Unit,
    onTop: Boolean = true,
) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(entries.indices)) }
    val entry = entries[index]
    val tile by produceState(entry.tile, entry.key) {
        value = entry.tile
        if (value == null) value = entry.load(deps.catalog)
    }
    val before by rememberNeighborImage(deps.catalog, entries.getOrNull(index - 1))
    val after by rememberNeighborImage(deps.catalog, entries.getOrNull(index + 1))
    // Opened without a card, from a button: there is nothing to zoom from, and the choice is already made.
    var full by remember { mutableStateOf(origin == null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val trailer = deps.trailer
    val root = remember { FocusRequester() }
    val primary = remember { FocusRequester() }

    val current = tile
    val profile by produceState<SagaProfile?>(null, current?.key) {
        value = null
        if (current != null) {
            delay(PROFILE_DELAY_MILLIS)
            value = deps.catalog.sagaProfile(current)
        }
    }
    var focusedFilm by remember(entry.key) { mutableStateOf<TmdbItem?>(null) }
    var sectionsFocused by remember { mutableStateOf(false) }
    var rotation by remember(entry.key) { mutableIntStateOf(0) }

    val enter = remember { Animatable(if (origin == null) 1f else 0f) }
    val textAlpha = remember { Animatable(if (origin == null) 1f else 0f) }
    val slide = remember { Animatable(0f) }
    val expand by animateFloatAsState(if (full) 1f else 0f, tween(420, easing = FastOutSlowInEasing))
    val idle = focusedFilm == null && !sectionsFocused

    LaunchedEffect(Unit) {
        runCatching { if (full) primary.requestFocus() else root.requestFocus() }
        launch { enter.animateTo(1f, tween(ENTER_MILLIS, easing = FastOutSlowInEasing)) }
        delay(TEXT_DELAY_MILLIS)
        textAlpha.animateTo(1f, tween(400))
    }
    // The newest film has the sharpest trailer. It plays while nothing below the timeline is being read.
    val trailerKey = profile?.trailers?.lastOrNull()?.video?.key
    val trailerOwner = remember { Any() }
    LaunchedEffect(trailerKey, idle, onTop) {
        if (onTop && idle && trailerKey != null) trailer.prepare(listOf(trailerKey), current?.name.orEmpty(), trailerOwner) else trailer.release(trailerOwner)
    }
    LaunchedEffect(sectionsFocused) {
        if (!sectionsFocused && full && !FocusSource.lastWasMouse) scroll.animateScrollTo(0)
    }
    val films = current?.films.orEmpty()
    LaunchedEffect(full, idle, trailer.playing, films, onTop) {
        // Off the interface dispatcher, and in the real app only (like the drift below): each new backdrop starts a
        // crossfade, and a UI test would never find the screen at rest.
        if (FocusSource.trackRealMouse && onTop && full && idle && !trailer.playing && films.size > 1) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            while (true) {
                delay(ROTATE_MILLIS)
                rotation++
            }
        }
    }

    fun step(delta: Int) {
        val next = index + delta
        if (next !in entries.indices) return
        trailer.stop()
        index = next
        scope.launch {
            slide.snapTo(delta * SLIDE_DISTANCE)
            slide.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
        }
    }

    fun open() {
        if (full) return
        full = true
        runCatching { primary.requestFocus() }
    }

    val pinch = LocalPinch.current
    DisposableEffect(pinch) {
        pinch.screenIn = { open() }
        pinch.screenOut = {
            if (full) {
                full = false
                scope.launch { scroll.animateScrollTo(0) }
                runCatching { root.requestFocus() }
            } else {
                trailer.stop()
                onClose()
            }
        }
        onDispose {
            pinch.screenIn = null
            pinch.screenOut = null
        }
    }

    val rotating = films.mapNotNull { it.backdrop }.takeIf { rotation > 0 && it.isNotEmpty() }?.let { it[rotation % it.size] }
    val shown = focusedFilm?.backdrop ?: rotating ?: current?.backdrop
    val blockColor by produceState(Palette.PillFill, entry.key, current?.backdrop) { value = DominantColor.of(current?.backdrop ?: current?.poster) }
    val hasFrames = rememberHasFrames(deps.player)
    // An endless animation never lets a test find the screen at rest: only the real app drifts.
    val drift = if (FocusSource.trackRealMouse && onTop) {
        rememberInfiniteTransition().animateFloat(1f, DRIFT_SCALE, infiniteRepeatable(tween(DRIFT_MILLIS), RepeatMode.Reverse))
    } else {
        remember { mutableStateOf(1f) }
    }
    var roomJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var currentRoom by remember(entry.key) { mutableStateOf<SagaRoom?>(null) }
    var visited by remember(entry.key) { mutableStateOf(emptySet<SagaRoom>()) }
    val roomsTop = with(androidx.compose.ui.platform.LocalDensity.current) { RoomHeight.roundToPx() }
    val rooms = roomsOf(profile, emptyList())
    LaunchedEffect(sectionsFocused) { if (!sectionsFocused) currentRoom = null }

    CompositionLocalProvider(LocalHoverFocus provides { full && !scroll.isScrollInProgress }) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag("saga")
                .pointerInput(Unit) {}
                .onPointerEvent(PointerEventType.Scroll) { e ->
                    if (!full && e.changes.first().scrollDelta.y != 0f && !e.keyboardModifiers.isCtrlPressed) open()
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Escape -> {
                            trailer.stop()
                            onClose()
                            true
                        }
                        full -> false
                        e.key == Key.DirectionLeft -> {
                            step(-1)
                            true
                        }
                        e.key == Key.DirectionRight -> {
                            step(1)
                            true
                        }
                        e.key == Key.DirectionDown || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                            open()
                            true
                        }
                        else -> true
                    }
                }
                .focusRequester(root)
                .focusable(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f * enter.value)))
            if (enter.value >= 1f) {
                Box(Modifier.fillMaxSize().background(if (full) Color.Black else Palette.Background))
                if (full) {
                    Art(current?.backdrop, Modifier.fillMaxSize().heavyBlur(90.dp), placeholder = Color.Black)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x80000000), 1f to Color(0xF2000000))))
                }
            }
            HeroLayer(
                deps = deps,
                backdrop = shown,
                neighbors = before to after,
                origin = origin,
                enter = enter.value,
                expand = expand,
                slide = { slide.value },
                scrollPx = { scroll.value },
                blockColor = blockColor,
                videoOn = trailer.playing && hasFrames && idle,
                onNeighbor = { step(it) },
                onTap = { open() },
                imageScale = { if (trailer.playing) 1f else drift.value },
            )
            if (current != null) {
                Column(Modifier.fillMaxSize().graphicsLayer { alpha = textAlpha.value }.verticalScroll(scroll, enabled = full)) {
                    SagaHero(
                        deps, current, profile, full, lerp(170.dp, 104.dp, expand), primary, onPlay, onOpenTitle,
                        onFilmFocused = { film, focused ->
                            if (focused) focusedFilm = film else if (focusedFilm == film) focusedFilm = null
                        },
                        onPlayFocused = { if (it) open() },
                    )
                    if (full) {
                        SagaRooms(
                            deps, current, profile, visited,
                            onRoom = { room, top ->
                                currentRoom = room
                                visited = visited + room
                                // The arrows land a room at the top of the screen; the mouse wheel stays free.
                                // After the small scroll Compose makes to show the focus, which would stop this one.
                                if (keyboardMoves()) {
                                    roomJob?.cancel()
                                    roomJob = scope.launch {
                                        delay(ROOM_ALIGN_DELAY_MS)
                                        scroll.animateScrollTo(roomsTop + top)
                                    }
                                }
                            },
                            onFocused = { sectionsFocused = it },
                            onPlay = onPlay,
                            onOpenTitle = onOpenTitle,
                            onOpenPerson = onOpenPerson,
                            onPlayTrailer = onPlayTrailer,
                        )
                    }
                }
                if (full && currentRoom != null) {
                    RoomMarker(rooms, currentRoom, Modifier.align(Alignment.CenterEnd).padding(end = 26.dp))
                }
            }
            // Reading down the page, it would sit on top of the text. Escape still goes back.
            if (scroll.value < REVEAL_SCROLL) {
                BackButton(
                    onClick = {
                        trailer.stop()
                        onClose()
                    },
                    modifier = Modifier.padding(start = if (full) 40.dp else 150.dp, top = if (full) 44.dp else 74.dp).testTag("saga-back"),
                )
            }
        }
    }
}

@Composable
private fun rememberNeighborImage(catalog: CatalogApi, entry: SagaEntry?) = produceState<String?>(null, entry?.key) {
    value = null
    if (entry != null) value = entry.load(catalog)?.let { it.backdrop ?: it.poster }
}

@Composable
private fun SagaHero(
    deps: AppDeps,
    tile: SagaTile,
    profile: SagaProfile?,
    full: Boolean,
    top: androidx.compose.ui.unit.Dp,
    primary: FocusRequester,
    onPlay: (PlayRequest) -> Unit,
    onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit,
    onFilmFocused: (TmdbItem, Boolean) -> Unit,
    onPlayFocused: (Boolean) -> Unit,
) {
    val detail by produceState(SagaDetail(tile.films, emptyList()), tile.key) { value = deps.catalog.sagaDetail(tile) }
    val finished = remember(tile.key) { deps.catalog.finishedMovies() }
    var story by remember(tile.key) { mutableStateOf(false) }
    val canChoose = tile.chronological.isNotEmpty()
    val ordered = remember(detail, story) { SagaTimeline.ordered(detail.films, tile.chronological, story) }
    val next = remember(ordered) { SagaTimeline.next(ordered, finished) }
    val inProgress = remember(ordered) { ordered.firstOrNull { deps.catalog.resumeOf("movie", it.id) != null } }
    val action = SagaTimeline.action(ordered, finished, inProgress, next)
    val timeline = rememberLazyListState()
    val factsById = remember(profile) { profile?.films.orEmpty().associateBy { it.film.id } }

    // A new order starts reading from its first film.
    LaunchedEffect(story) { timeline.animateScrollToItem(0) }

    Column(Modifier.fillMaxWidth().height(RoomHeight)) {
        Column(Modifier.fillMaxWidth().padding(top = top), horizontalAlignment = Alignment.CenterHorizontally) {
            SagaName(tile.name, tile.logo, 62, 210.dp, Modifier.fillMaxWidth().padding(horizontal = 260.dp).testTag("saga-name"))
            Spacer(Modifier.height(44.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
                val target = when (action) {
                    is SagaAction.Resume -> action.film
                    is SagaAction.Next -> action.film
                    is SagaAction.Start -> ordered.firstOrNull()
                    SagaAction.Rewatch -> ordered.firstOrNull()
                }
                if (target != null) {
                    PlayPill(
                        actionLabel(action),
                        { onPlay(PlayRequest("movie", target.id, null, null, target.title, tile.name, target.backdrop)) },
                        Modifier.testTag("saga-play"),
                        primary,
                        onFocused = onPlayFocused,
                    )
                }
                if (full && canChoose) OrderSwitch(story) { story = it }
            }
        }
        if (full) {
            Spacer(Modifier.height(96.dp))
            LazyRow(
                state = timeline,
                contentPadding = PaddingValues(horizontal = Metrics.PageMargin, vertical = 20.dp),
                modifier = Modifier.testTag("saga-timeline"),
            ) {
                itemsIndexed(ordered, key = { _, film -> film.id }) { i, film ->
                    Box(Modifier.animateItem().keepClearOfEdges(timeline, i)) {
                        TimelineCard(deps.catalog, i + 1, film, factsById[film.id], film.id in finished, film.id == next?.id, i == ordered.lastIndex, { onFilmFocused(film, it) }) {
                            onOpenTitle(ordered, i, it)
                        }
                    }
                }
            }
        }
    }
}

private fun actionLabel(action: SagaAction): String = when (action) {
    is SagaAction.Start -> tr(Text.SagaStart)
    is SagaAction.Resume -> tr(Text.Resume)
    is SagaAction.Next -> tr(Text.Watch)
    SagaAction.Rewatch -> tr(Text.SagaRewatch)
}

/** A capsule with both orders, the chosen one lit: the same control as the search tabs. */
@Composable
private fun OrderSwitch(story: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .height(Metrics.ButtonHeight)
            .clip(RoundedCornerShape(50))
            .background(Palette.ButtonFill)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(50))
            .padding(5.dp)
            .testTag("saga-order"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OrderOption(tr(Text.OrderRelease), !story, "order-release") { onChange(false) }
        OrderOption(tr(Text.OrderStory), story, "order-story") { onChange(true) }
    }
}

@Composable
private fun OrderOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when {
            focused -> Palette.FocusFill
            selected -> Palette.RowFill
            else -> Color.Transparent
        },
    )
    Box(
        Modifier.height(Metrics.ButtonHeight - 10.dp).clip(RoundedCornerShape(50)).background(fill).testTag(tag)
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick).padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        val color = when {
            focused -> Palette.OnFocus
            selected -> Palette.OnBackground
            else -> Palette.BarText
        }
        BasicText(label, style = Type.Nav.copy(color = color))
    }
}

/** A stop on the timeline: a marker and the year on a line running to the next film, then the film and its number. */
@Composable
private fun TimelineCard(
    catalog: CatalogApi,
    number: Int,
    film: TmdbItem,
    facts: FilmFacts?,
    seen: Boolean,
    isNext: Boolean,
    last: Boolean,
    onFocus: (Boolean) -> Unit,
    onClick: (CardOrigin) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(Step)) {
        Row(Modifier.fillMaxWidth().height(StopSize), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(StopSize).clip(CircleShape)
                    .background(if (isNext || seen) Palette.FocusFill else Palette.ButtonFill)
                    .border(2.dp, if (isNext || seen) Color.Transparent else Palette.BarText, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (seen) Icon(Icons.Filled.Check, null, tint = Palette.OnFocus, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Label(film.year?.toString().orEmpty(), Type.Button.copy(fontSize = 24.sp), if (focused || isNext) Palette.OnBackground else Palette.BarText, maxLines = 1)
            if (!last) {
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f).height(2.dp).background(if (seen) Palette.FocusFill else Color(0x66FFFFFF)))
            }
        }
        Spacer(Modifier.height(18.dp))
        FocusCard(CardWidth, CardHeight, onClick, tag = "timeline-${film.id}", onFocus = {
            focused = it
            onFocus(it)
        }) {
            SmartLandscape(catalog, film)
            BasicText(
                number.toString(),
                Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 6.dp),
                style = Type.RowTitle.copy(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 60.sp, color = Color(0xE6FFFFFF)),
            )
        }
        Spacer(Modifier.height(16.dp))
        // The space stays reserved: the row must not jump as focus moves along it.
        Column(Modifier.width(CardWidth).height(70.dp)) {
            if (focused) {
                SingleLine(film.title, Type.Meta, Palette.OnBackground)
                SingleLine(filmLine(facts), Type.Body, Palette.Secondary)
            }
        }
    }
}

/** Rating, runtime and box office of a film, whichever are known. */
private fun filmLine(facts: FilmFacts?): String = listOfNotNull(
    facts?.omdb?.imdb?.let { "IMDb $it" },
    facts?.info?.runtimeMin?.takeIf { it > 0 }?.let { durationLabel(it) },
    facts?.info?.revenue?.let { SagaFacts.money(it) },
).joinToString(" · ")
