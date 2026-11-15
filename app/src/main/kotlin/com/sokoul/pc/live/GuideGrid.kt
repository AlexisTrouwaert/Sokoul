package com.sokoul.pc.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.Platform
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.FocusSource
import com.sokoul.pc.ui.LocalActiveTab
import com.sokoul.pc.ui.LocalHoverFocus
import com.sokoul.pc.ui.moveWithArrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HOUR_MS = 3_600_000L
private const val HALF_HOUR_MS = 1_800_000L
private const val LOAD_WINDOW = 24
private const val LOAD_STEP = 6
private const val PAGE_ROWS = 6
private const val NUMBER_WAIT_MS = 1_200L
private const val SCHEDULE_LOAD_DELAY_MS = 120L

private val Glide = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
private val Follow = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Sizes of one level of the guide, in dp. */
internal data class GuideSizes(val row: Float, val hour: Float, val logoW: Float, val logoH: Float)

internal fun guideSizes(level: GuideLevel, television: Boolean = Platform.IS_TV) = when (level) {
    GuideLevel.Guide -> if (television) GuideSizes(row = FormulerGuideStyle.rowHeight, hour = 620f, logoW = 76f, logoH = 44f) else GuideSizes(row = 86f, hour = 620f, logoW = 104f, logoH = 60f)
    GuideLevel.Panorama -> GuideSizes(row = 58f, hour = 340f, logoW = 74f, logoH = 44f)
}

private val COLUMN_END = if (Platform.IS_TV) 340.dp else 372.dp
private val RULER = if (Platform.IS_TV) 44.dp else 46.dp
private val BLOCK_GAP = 6.dp

/** The focus of the guide: a row and a moment. Moving up or down stays at that moment, as on a paper guide. */
class GuideFocus {
    var row by mutableIntStateOf(0)
    var anchor by mutableLongStateOf(0L)
    var channelId: String? = null
}

/**
 * The whole guide is one drawn surface that owns its keys. Going through it recomposes nothing: the time axis, the
 * lens on the focused row and the white focus are animated values read while drawing, so a video playing above is not
 * disturbed, and a group of thousands of channels costs the same as a group of ten.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun GuideGrid(
    catalog: TvCatalog,
    channels: List<Channel>,
    focus: GuideFocus,
    favorites: Set<String>,
    level: GuideLevel,
    now: Long,
    requester: FocusRequester,
    schedule: (String) -> List<Program>,
    onSchedules: (Map<String, List<Program>>) -> Unit,
    reminded: (String, Long) -> Boolean,
    onActivity: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenActions: () -> Unit,
    onOpenDetails: () -> Unit,
    onWatch: (Channel) -> Unit,
    onFavorite: (Channel) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    loadingEnabled: Boolean = true,
    /** Where Up from the first row goes before the navigation bar (the TV control row); null keeps the tab. */
    upTarget: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onLongOk: () -> Unit = onOpenActions,
) {
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 2048)
    val size = guideSizes(level)
    val focusManager = LocalFocusManager.current
    val activeTab = LocalActiveTab.current
    val hoverAllowed = LocalHoverFocus.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var has by remember { mutableStateOf(false) }
    val okPress = remember { GuideOkPress() }
    var okJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val longPressMs = androidx.compose.ui.platform.LocalViewConfiguration.current.longPressTimeoutMillis
    val currentLongOk by androidx.compose.runtime.rememberUpdatedState(onLongOk)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { okJob?.cancel(); okPress.cancel() } }
    val focusOpacity by animateFloatAsState(if (has) 1f else 0.35f, tween(FormulerGuideStyle.focusMs))
    val origin = remember { now - now % HOUR_MS - 6 * HOUR_MS }
    fun rel(t: Long) = (t - origin).toFloat()

    val scrollY = remember { Animatable(0f) }
    val leftEdge = remember { Animatable(rel(now - GuideLogic.PAST_SHOWN_MS)) }
    val hour = remember { Animatable(size.hour) }
    val rowSize = remember { Animatable(size.row) }
    val rowsIn = remember { Animatable(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val logos = remember { mutableStateMapOf<String, ImageBitmap>() }
    val requests = remember { GuideWindowRequests() }
    val channelIds = remember(channels) { channels.map { it.id } }
    val rowLayers = if (Platform.IS_TV) {
        val layers = List(12) { rememberGraphicsLayer() }
        remember(layers) { GuideRowLayers(layers) }
    } else null
    val lastNavigation = remember { longArrayOf(0L) }
    var rapidNavigation by remember { mutableStateOf(false) }

    LaunchedEffect(level) {
        launch { hour.animateTo(size.hour, tween(420)) }
        launch { rowSize.animateTo(size.row, tween(420)) }
    }
    // The channels of a new group arrive from the side, one after the other.
    LaunchedEffect(channels) {
        if (Platform.IS_TV) rowsIn.snapTo(1f) else {
            rowsIn.snapTo(0f)
            rowsIn.animateTo(1f, tween(620))
        }
        if (Platform.IS_TV) {
            focus.channelId?.let { id -> channels.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { focus.row = it } }
            focus.row = focus.row.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
            focus.channelId = channels.getOrNull(focus.row)?.id
        }
    }
    // Only what is near gets read: a group can hold thousands of channels.
    val window = focus.row / LOAD_STEP
    LaunchedEffect(channels, window, now / 60_000L, loadingEnabled) {
        if (!loadingEnabled) { requests.invalidate(); return@LaunchedEffect }
        val requestKey = GuideWindowKey(channelIds, window, now / 60_000L)
        val ticket = requests.begin(requestKey, reuse = Platform.IS_TV) ?: return@LaunchedEffect
        delay(SCHEDULE_LOAD_DELAY_MS)
        val shown = channels.subList((window * LOAD_STEP - LOAD_WINDOW / 2).coerceAtLeast(0), (window * LOAD_STEP + LOAD_WINDOW).coerceAtMost(channels.size))
        val local = withContext(Dispatchers.IO) { catalog.schedules(shown) }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        requests.deliver(ticket, requestKey) { onSchedules(local) }
        // What the big guide does not cover, the provider may still know.
        launch {
            val short = catalog.fillShortGuide(shown, focus.anchor, schedule)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            requests.deliver(ticket, requestKey) { onSchedules(short) }
        }
        launch(Dispatchers.IO) {
            catalog.refreshMissingGuide(shown)
            val refreshed = catalog.schedules(shown)
            withContext(Dispatchers.Main) { requests.deliver(ticket, requestKey) { onSchedules(refreshed.filterValues { it.isNotEmpty() }) } }
        }
        // Shaped logos (sharpest source, margins cut, plate when dark), keyed by channel: one without a playlist logo
        // may still get iptv-org's.
        shown.forEach { c ->
            if (c.id !in logos) launch { ChannelLogos.prepare(c.name, c.logo, c.epgKey)?.let { logos[c.id] = it } }
        }
    }
    val focusedChannel = channels.getOrNull(focus.row)
    val focusedProgram = focusedChannel?.let { GuideLogic.programAt(schedule(it.id), focus.anchor) }
    val programKey = focusedProgram?.start to focusedProgram?.end
    LaunchedEffect(focus.row, programKey, level, viewport, rapidNavigation) {
        suspend fun follow(value: Animatable<Float, *>, target: Float) {
            if (Platform.IS_TV && rapidNavigation) value.snapTo(target)
            else value.animateTo(target, if (Platform.IS_TV) tween(120) else Follow)
        }
        val h = viewport.height / density
        val currentScroll = scrollY.value
        val targetScroll = GuideLogic.scrollFor(focus.row, channels.size, size.row, h - RULER.value, currentScroll)
        if (targetScroll != currentScroll) launch { follow(scrollY, targetScroll) }
        val program = focusedProgram
        val visible = ((viewport.width / density - COLUMN_END.value) / size.hour * HOUR_MS).toLong()
        if (program != null) {
            val edge = GuideLogic.leftEdgeFor(program.start, program.end, origin + leftEdge.value.toLong(), now, visible)
            launch { follow(leftEdge, rel(edge)) }
        } else {
            // Nothing known at that moment: the axis still goes there.
            launch { follow(leftEdge, rel(maxOf(focus.anchor - HALF_HOUR_MS / 3, now - GuideLogic.PAST_SHOWN_MS))) }
        }
    }

    fun move(row: Int) {
        focus.row = row.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        focus.channelId = channels.getOrNull(focus.row)?.id
    }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(typed) {
        if (typed.isNotEmpty()) {
            delay(NUMBER_WAIT_MS)
            typed.toIntOrNull()?.let { n -> channels.indexOfFirst { it.number == n }.takeIf { it >= 0 }?.let(::move) }
            typed = ""
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .testTag("tv-guide")
            // The grid is drawn, not composed: tell TalkBack what the focus is on, and announce each move.
            .semantics {
                val channel = channels.getOrNull(focus.row)
                val program = channel?.let { GuideLogic.programAt(schedule(it.id), focus.anchor) }
                contentDescription = listOfNotNull(
                    channel?.let { TvCatalog.shortLabel(it.name) },
                    program?.let { "${it.title}, ${com.sokoul.pc.playback.ui.clock(it.start)} – ${com.sokoul.pc.playback.ui.clock(it.end)}" },
                ).joinToString(" · ").ifEmpty { com.sokoul.pc.data.L("TV guide", "Guide TV").text() }
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            }
            .focusRequester(requester)
            .onFocusChanged {
                has = it.isFocused
                if (!it.isFocused) { okJob?.cancel(); okPress.cancel() }
                onFocusChanged(it.isFocused)
            }
            .onKeyEvent { e ->
                if (Platform.IS_TV && e.key in listOf(Key.Enter, Key.NumPadEnter)) {
                    if (e.type == KeyEventType.KeyDown) {
                        onActivity()
                        channels.getOrNull(focus.row)?.let { channel ->
                            if (okPress.down(channel.id)) okJob = scope.launch {
                                delay(longPressMs)
                                if (has && loadingEnabled && okPress.expire(channel.id)) currentLongOk()
                            }
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        okJob?.cancel()
                        okPress.up()?.let { id -> channels.getOrNull(focus.row)?.takeIf { it.id == id }?.let(onWatch) }
                    }
                    return@onKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (Platform.IS_TV) { okJob?.cancel(); okPress.cancel() }
                FocusSource.lastWasMouse = false
                onActivity()
                if (Platform.IS_TV && e.key in listOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight, Key.PageDown, Key.PageUp)) {
                    val at = System.nanoTime()
                    rapidNavigation = lastNavigation[0] != 0L && at - lastNavigation[0] < 180_000_000L
                    lastNavigation[0] = at
                }
                val c = channels.getOrNull(focus.row)
                val list = c?.let { schedule(it.id) }.orEmpty()
                val p = GuideLogic.programAt(list, focus.anchor)
                when (e.key) {
                    Key.DirectionDown -> {
                        move(focus.row + 1)
                        true
                    }
                    Key.DirectionUp -> if (focus.row > 0) {
                        move(focus.row - 1)
                        true
                    } else {
                        FocusSource.lastWasMouse = false
                        val tab = upTarget ?: activeTab
                        if (tab == null) {
                            focusManager.moveWithArrow(e)
                        } else {
                            scope.launch {
                                androidx.compose.runtime.withFrameNanos { }
                                runCatching { tab.requestFocus() }
                            }
                            true
                        }
                    }
                    Key.PageDown -> {
                        move(focus.row + PAGE_ROWS)
                        true
                    }
                    Key.PageUp -> {
                        move(focus.row - PAGE_ROWS)
                        true
                    }
                    Key.DirectionRight -> {
                        p?.let { GuideLogic.next(list, it) }?.let { focus.anchor = it.start }
                        true
                    }
                    Key.DirectionLeft -> {
                        val before = p?.let { GuideLogic.previous(list, it, now) }
                        if (before != null) focus.anchor = before.start else onOpenDrawer()
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        c?.let(onWatch)
                        true
                    }
                    Key.Menu -> {
                        if (Platform.IS_TV) onOpenActions() else c?.let(onFavorite)
                        true
                    }
                    Key.F -> {
                        c?.let(onFavorite)
                        true
                    }
                    Key.Info, Key.F1, Key.I -> {
                        onOpenDetails()
                        true
                    }
                    Key.MoveHome -> {
                        focus.anchor = now
                        true
                    }
                    Key.MoveEnd -> {
                        focus.anchor = GuideLogic.jumpTarget(list, GuideLogic.tonight(now))
                        true
                    }
                    else -> digitOf(e.key)?.let {
                        typed += it
                        true
                    } ?: e.utf16CodePoint.toChar().takeIf { it.isLetter() }?.let {
                        // Typing in the guide is searching in it.
                        onSearch(it.toString())
                        true
                    } ?: false
                }
            }
            .focusable()
            .pointerInput(channels, loadingEnabled) {
                if (!loadingEnabled) return@pointerInput
                detectTapGestures(
                    onTap = { at ->
                    hit(at, channels, schedule, scrollY.value, rowSize.value, hour.value, origin + leftEdge.value.toLong(), density)?.let { (row, t) ->
                        focus.row = row
                        focus.anchor = t
                        runCatching { requester.requestFocus() }
                        channels.getOrNull(row)?.let(onWatch)
                    }
                    },
                    onLongPress = { at ->
                        hit(at, channels, schedule, scrollY.value, rowSize.value, hour.value, origin + leftEdge.value.toLong(), density)?.let { (row, t) ->
                            focus.row = row
                            focus.anchor = t
                            runCatching { requester.requestFocus() }
                            onOpenActions()
                        }
                    },
                )
            }
            .onPointerEvent(PointerEventType.Move) { e ->
                if (loadingEnabled && hoverAllowed() && FocusSource.pointerReallyMoved()) {
                    hit(e.changes.first().position, channels, schedule, scrollY.value, rowSize.value, hour.value, origin + leftEdge.value.toLong(), density)?.let { (row, t) ->
                        FocusSource.lastWasMouse = true
                        focus.row = row
                        focus.anchor = t
                    }
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                if (!loadingEnabled) return@onPointerEvent
                val d = e.changes.first().scrollDelta
                if (d.x != 0f) {
                    scope.launch { leftEdge.snapTo(leftEdge.value + d.x * HALF_HOUR_MS / 2) }
                } else if (!e.keyboardModifiers.isCtrlPressed) {
                    move(focus.row + if (d.y > 0) 1 else -1)
                }
            },
    ) {
        drawGuide(
            measurer, channels, schedule, reminded, favorites, focus, logos, now, has, density, typed,
            scrollY.value, leftEdge.value, origin, hour.value, rowSize.value, rowsIn.value,
            size, focusOpacity = focusOpacity, rowLayers = rowLayers,
        )
    }
}

internal fun digitOf(key: Key): String? = when (key) {
    Key.Zero, Key.NumPad0 -> "0"
    Key.One, Key.NumPad1 -> "1"
    Key.Two, Key.NumPad2 -> "2"
    Key.Three, Key.NumPad3 -> "3"
    Key.Four, Key.NumPad4 -> "4"
    Key.Five, Key.NumPad5 -> "5"
    Key.Six, Key.NumPad6 -> "6"
    Key.Seven, Key.NumPad7 -> "7"
    Key.Eight, Key.NumPad8 -> "8"
    Key.Nine, Key.NumPad9 -> "9"
    else -> null
}

/** The row and the moment under a point of the guide. */
private fun hit(
    at: Offset,
    channels: List<Channel>,
    schedule: (String) -> List<Program>,
    scrollY: Float,
    row: Float,
    hour: Float,
    leftEdge: Long,
    density: Float,
): Pair<Int, Long>? {
    val y = (at.y / density) - RULER.value + scrollY
    val i = GuideLogic.rowAt(y, channels.size, row) ?: return null
    val x = at.x / density - COLUMN_END.value
    val t = leftEdge + (x / hour * HOUR_MS).toLong().coerceAtLeast(0)
    val p = GuideLogic.programAt(schedule(channels[i].id), t)
    return i to (p?.start?.coerceAtLeast(t.coerceAtMost(p.end - 1)) ?: t)
}

internal fun DrawScope.drawGuide(
    measurer: TextMeasurer,
    channels: List<Channel>,
    schedule: (String) -> List<Program>,
    reminded: (String, Long) -> Boolean,
    favorites: Set<String>,
    focus: GuideFocus,
    logos: Map<String, ImageBitmap>,
    now: Long,
    focused: Boolean,
    density: Float,
    typed: String,
    scrollY: Float,
    leftEdgeRel: Float,
    origin: Long,
    hourDp: Float,
    rowDp: Float,
    rowsIn: Float,
    target: GuideSizes,
    television: Boolean = Platform.IS_TV,
    focusOpacity: Float = 1f,
    rowLayers: GuideRowLayers? = null,
) {
    val d = density
    val w = size.width
    val rulerH = (if (television) 44.dp else 46.dp).toPx()
    val columnEnd = (if (television) 340.dp else 372.dp).toPx()
    val margin = Metrics.PageMargin.toPx()
    val pxPerMs = hourDp * d / HOUR_MS
    val leftEdge = origin + leftEdgeRel.toLong()
    fun x(t: Long) = columnEnd + (t - leftEdge) * pxPerMs
    val row = rowDp * d

    // The rows
    val firstY = scrollY * d
    clipRect(top = rulerH) {
        var i = GuideLogic.rowAt(firstY / d, channels.size, rowDp) ?: 0
        val shownFrom = i
        while (i < channels.size) {
            val top = rulerH + GuideLogic.rowTop(i, rowDp) * d - firstY
            if (top > size.height) break
            val height = row
            val arrival = if (rowsIn >= 1f) 1f else (rowsIn * (ARRIVING_ROWS + 3) - (i - shownFrom)).coerceIn(0f, 1f)
            if (arrival > 0f) {
                val slide = (1f - arrival) * 60f * d
                val c = channels[i]
                if (television && rowLayers != null) {
                    val programs = schedule(c.id)
                    val selectedRow = i == focus.row
                    val selectedStart = if (selectedRow) GuideLogic.programAt(programs, focus.anchor)?.start else null
                    val reminderStarts = programs.asSequence()
                        .filter { x(it.end) >= columnEnd && x(it.start) <= w && reminded(c.id, it.start) }
                        .map { it.start }.toSet()
                    // Read every invalidation input here, even when the display list can be reused.
                    val key = listOf(c, programs, logos[c.id], c.id in favorites, now, leftEdge,
                        hourDp, rowDp, w, d, fontScale, layoutDirection, selectedRow, selectedStart,
                        reminderStarts, tr(Text.LiveNoGuide), target, measurer)
                    val rowIndex = i
                    translate(top = top) {
                        rowLayers.draw(this, rowIndex, key,
                            IntSize(kotlin.math.ceil(w).toInt(), kotlin.math.ceil(height).toInt())) {
                            drawChannelCell(measurer, c, logos[c.id], c.id in favorites, margin, 0f, height, 1f, target, d, columnEnd)
                            clipRect(left = columnEnd, top = 0f, bottom = height) {
                                drawBlocks(programs, now, 0f, height, 1f, 0f, ::x, columnEnd, w, selectedStart, true)
                                val lineX = x(now)
                                if (lineX > columnEnd) drawRect(Palette.Error, Offset(lineX - d, 0f), Size(2 * d, height))
                                drawBlockTexts(measurer, programs, now, 0f, height, 1f, 0f, ::x, columnEnd, w, d, target, selectedRow, true) {
                                    it in reminderStarts
                                }
                            }
                        }
                    }
                } else {
                    drawChannelCell(measurer, c, logos[c.id], c.id in favorites, margin + slide, top, height, arrival, target, d, columnEnd)
                    clipRect(left = columnEnd, top = top - 2, bottom = top + height + 2) {
                        drawBlocks(schedule(c.id), now, top, height, arrival, slide, ::x, columnEnd, w,
                            if (i == focus.row) GuideLogic.programAt(schedule(c.id), focus.anchor)?.start else null, television)
                    }
                }
            }
            i++
        }

        // Now: a thin line through the guide, under the words so that it never crosses out a title.
        val lineX = x(now)
        if ((!television || rowLayers == null) && lineX > columnEnd) drawRect(Palette.Error, Offset(lineX - 1 * d, rulerH), Size(2 * d, size.height - rulerH))

        i = shownFrom
        while ((!television || rowLayers == null) && i < channels.size) {
            val top = rulerH + GuideLogic.rowTop(i, rowDp) * d - firstY
            if (top > size.height) break
            val height = row
            val arrival = if (rowsIn >= 1f) 1f else (rowsIn * (ARRIVING_ROWS + 3) - (i - shownFrom)).coerceIn(0f, 1f)
            if (arrival > 0f) {
                val slide = (1f - arrival) * 60f * d
                clipRect(left = columnEnd, top = top - 2, bottom = top + height + 2) {
                    val id = channels[i].id
                    drawBlockTexts(measurer, schedule(id), now, top, height, arrival, slide, ::x, columnEnd, w, d, target, i == focus.row, television) { start -> reminded(id, start) }
                }
            }
            i++
        }

        // Focus is an outline within the programme's actual time-proportional bounds.
        channels.getOrNull(focus.row)?.let { c ->
            val p = GuideLogic.programAt(schedule(c.id), focus.anchor)
            val gap = BLOCK_GAP.toPx()
            val top = rulerH + focus.row * row - firstY + gap / 2
            val left = (if (p == null) columnEnd else x(p.start)).coerceAtLeast(columnEnd) + gap / 2
            val right = (if (p == null) w else x(p.end)).coerceAtMost(w) - gap / 2
            if (focused && (television || p != null) && right > left) {
                if (!television) drawRoundRect(Palette.FocusFill.copy(alpha = 0.18f), Offset(left, top), Size(right - left, row - gap), CornerRadius((if (television) 8.dp else 16.dp).toPx()))
                drawRoundRect(Color.White.copy(alpha = if (television) 0.95f * focusOpacity else 0.95f), Offset(left, top), Size(right - left, row - gap), CornerRadius((if (television) 8.dp else 16.dp).toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2 * d))
            }
        }
    }

    // Time ruler, above everything that scrolls.
    clipRect(left = columnEnd, bottom = rulerH) {
        val step = if (target.hour < 400f) HOUR_MS else HALF_HOUR_MS
        var t = leftEdge - Math.floorMod(leftEdge, step)
        while (x(t) < w) {
            val tx = x(t)
            val clearOfNow = kotlin.math.abs(tx + 35 * d - x(now)) > 85 * d
            if (tx > columnEnd - 40 * d && clearOfNow) text(measurer, clock(t), Type.Body.copy(fontSize = 19.sp, color = Palette.Secondary), tx + 8 * d, 10 * d, if (television) (90 * d).toInt() else 90 * d.toInt(), 1)
            drawRect(Palette.Hairline, Offset(tx, rulerH - 12 * d), Size(1 * d, 12 * d))
            t += step
        }
        val nowX = x(now)
        if (nowX > columnEnd) {
            val pill = measurer.measure(clock(now), Type.Body.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White))
            val pw = pill.size.width + 18 * d
            drawRoundRect(Palette.Error, Offset(nowX - pw / 2, 8 * d), Size(pw, 30 * d), CornerRadius(15 * d))
            drawText(pill, topLeft = Offset(nowX - pill.size.width / 2f, 8 * d + (30 * d - pill.size.height) / 2))
        }
    }

    if (typed.isNotEmpty()) {
        val big = measurer.measure(typed, Type.RowTitle.copy(fontSize = 56.sp, color = Color.White))
        val pad = 24 * d
        val bx = w - big.size.width - pad * 3
        drawRoundRect(com.sokoul.pc.theme.Glass.Base, Offset(bx, rulerH + 16 * d), Size(big.size.width + pad * 2, big.size.height + pad), CornerRadius(22 * d))
        drawText(big, topLeft = Offset(bx + pad, rulerH + 16 * d + pad / 2))
    }
}

private fun DrawScope.drawChannelCell(
    measurer: TextMeasurer,
    c: Channel,
    logo: ImageBitmap?,
    favorite: Boolean,
    left: Float,
    top: Float,
    height: Float,
    alpha: Float,
    target: GuideSizes,
    d: Float,
    columnEnd: Float,
) {
    val boxW = target.logoW * d
    val boxH = target.logoH * d
    val boxY = top + (height - boxH) / 2
    drawRoundRect(Palette.ButtonFill.copy(alpha = alpha), Offset(left, boxY), Size(boxW, boxH), CornerRadius(12 * d))
    if (logo != null) {
        val pad = 6 * d
        val scale = minOf((boxW - pad * 2) / logo.width, (boxH - pad * 2) / logo.height)
        val dw = (logo.width * scale).toInt()
        val dh = (logo.height * scale).toInt()
        drawImage(
            logo,
            dstOffset = IntOffset((left + (boxW - dw) / 2).toInt(), (boxY + (boxH - dh) / 2).toInt()),
            dstSize = IntSize(dw, dh),
            alpha = alpha,
        )
    } else {
        // No logo anywhere: the channel's initials, as in the other lists.
        val initials = measurer.measure(LogoChoice.initials(c.name), Type.Body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Secondary.copy(alpha = alpha)), maxLines = 1)
        drawText(initials, topLeft = Offset(left + (boxW - initials.size.width) / 2, boxY + (boxH - initials.size.height) / 2))
    }
    val textLeft = left + boxW + 14 * d
    val width = (columnEnd - textLeft - 12 * d).toInt().coerceAtLeast(1)
    val nameColor = Palette.OnBackground.copy(alpha = alpha)
    val name = measurer.measure(
        TvCatalog.shortLabel(c.name),
        Type.Body.copy(fontSize = (if (target.row < 70f) 18 else 20).sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, color = nameColor),
        maxLines = if (target.row < 70f) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = width),
    )
    val number = measurer.measure((if (favorite) "★ " else "") + c.number.toString(), Type.Body.copy(fontSize = 16.sp, color = Palette.Secondary.copy(alpha = alpha)), maxLines = 1)
    val total = name.size.height + (if (target.row < 70f) 0 else number.size.height)
    val y = top + (height - total) / 2
    if (target.row >= 70f) drawText(number, topLeft = Offset(textLeft, y))
    drawText(name, topLeft = Offset(textLeft, y + if (target.row < 70f) 0 else number.size.height))
}

private fun DrawScope.drawBlocks(
    programs: List<Program>,
    now: Long,
    top: Float,
    height: Float,
    alpha: Float,
    slide: Float,
    x: (Long) -> Float,
    columnEnd: Float,
    w: Float,
    selectedStart: Long?,
    television: Boolean,
) {
    val gap = BLOCK_GAP.toPx()
    val corner = CornerRadius((if (television) 8.dp else 16.dp).toPx())
    val blockTop = top + gap / 2
    val blockH = height - gap
    if (programs.isEmpty()) {
        drawRoundRect(Palette.ButtonFill.copy(alpha = 0.45f * alpha), Offset(columnEnd + gap / 2 + slide, blockTop), Size(w - columnEnd, blockH), corner)
        return
    }
    for (p in programs) {
        val left = x(p.start) + gap / 2 + slide
        val right = x(p.end) - gap / 2 + slide
        if (left > w) break
        if (right < columnEnd) continue
        val on = p.start <= now && p.end > now
        val past = p.end <= now
        val fill = when {
            television && p.start == selectedStart -> FormulerGuideStyle.selected
            television && on -> FormulerGuideStyle.current
            television -> FormulerGuideStyle.surface
            on -> Palette.PillFill
            past -> Palette.ButtonFill.copy(alpha = 0.5f)
            right - left < TINY_BLOCK * density -> Palette.ButtonFill.copy(alpha = 0.55f)
            else -> Palette.ButtonFill
        }
        drawRoundRect(fill.copy(alpha = fill.alpha * alpha), Offset(left, blockTop), Size(right - left, blockH), corner)
        if (on && television) {
            val inset = 10 * density
            val fraction = ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            val width = ((right - left - inset * 2) * fraction).coerceAtLeast(0f)
            drawRect(FormulerGuideStyle.secondary, Offset(left + inset, blockTop + blockH - 5 * density), Size(width, 2 * density))
        } else if (on) {
            // What has been shown of it: the same block, a little lighter, up to now.
            val done = (x(now) + slide).coerceIn(left, right)
            clipRect(left, blockTop, done, blockTop + blockH) {
                drawRoundRect(Palette.ActiveFill.copy(alpha = 0.26f * alpha), Offset(left, blockTop), Size(right - left, blockH), corner)
            }
        }
    }
}

private fun DrawScope.drawBlockTexts(
    measurer: TextMeasurer,
    programs: List<Program>,
    now: Long,
    top: Float,
    height: Float,
    alpha: Float,
    slide: Float,
    x: (Long) -> Float,
    columnEnd: Float,
    w: Float,
    d: Float,
    target: GuideSizes,
    focusedRow: Boolean,
    television: Boolean,
    reminded: (Long) -> Boolean,
) {
    val gap = BLOCK_GAP.toPx()
    val small = target.row < 70f
    if (programs.isEmpty()) {
        // Said once, on the row looked at: a column of the same sentence says nothing more.
        if (!focusedRow) return
        val y = top + (height - 24 * d) / 2
        text(measurer, tr(Text.LiveNoGuide), Type.Body.copy(fontSize = (if (television) 20 else 18).sp,
            color = if (television) FormulerGuideStyle.secondary else Palette.Secondary.copy(alpha = 0.6f * alpha)), columnEnd + 22 * d + slide, y, (w - columnEnd - 40 * d).toInt(), 1)
        return
    }
    for (p in programs) {
        val left = x(p.start) + gap / 2 + slide
        val right = x(p.end) - gap / 2 + slide
        if (left > w) break
        if (right < columnEnd) continue
        val past = p.end <= now
        if (reminded(p.start)) drawCircle(Color.White, 5 * d, Offset(right - 16 * d, top + 18 * d))
        val pad = 14 * d
        // A programme begun before the edge keeps its title in view, and what cannot hold a word stays silent.
        val textX = maxOf(left + pad, columnEnd + pad)
        val textWidth = (right - textX - pad).toInt()
        if (textWidth < MIN_TEXT_WIDTH * d) continue
        val ink = if (television) (if (past) FormulerGuideStyle.muted else FormulerGuideStyle.ink) else Palette.OnBackground.copy(alpha = (if (past) 0.45f else 1f) * alpha)
        var y = top + (if (small) 15f else 18f) * d
        y += text(measurer, p.title, Type.Body.copy(fontSize = (if (small) 18 else if (television) 22 else 20).sp, fontWeight = FontWeight.SemiBold, color = ink), textX, y, textWidth, 1)
        if (!small) {
            y += 1 * d
            val timing = if (television) "${clock(p.start)} – ${clock(p.end)}" else clock(p.start)
            text(measurer, timing, Type.Body.copy(fontSize = (if (television) 18 else 17).sp, color = if (television) FormulerGuideStyle.secondary else Palette.Secondary.copy(alpha = alpha)), textX, y, textWidth, 1)
        }
    }
}

/** Draws text and says its height. The text engine keeps what it has measured, so the same text costs nothing twice. */
private fun DrawScope.text(measurer: TextMeasurer, value: String, style: TextStyle, x: Float, y: Float, width: Int, lines: Int): Float {
    val layout = measurer.measure(value, style, maxLines = lines, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width))
    drawText(layout, topLeft = Offset(x, y))
    return layout.size.height.toFloat()
}

private const val ARRIVING_ROWS = 10
private const val MIN_TEXT_WIDTH = 56
private const val TINY_BLOCK = 56f
