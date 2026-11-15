package com.sokoul.pc.anime

import androidx.compose.animation.AnimatedVisibility
import com.sokoul.pc.ui.glassSurface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.ui.ImageLoader
import com.sokoul.pc.AppDeps
import com.sokoul.pc.Platform
import com.sokoul.pc.data.L
import com.sokoul.pc.data.anime.ReadChapter
import com.sokoul.pc.data.anime.ReadPage
import com.sokoul.pc.data.anime.ReaderFit
import com.sokoul.pc.data.anime.ReaderLogic
import com.sokoul.pc.data.anime.ReaderMode
import com.sokoul.pc.data.anime.ReaderRequest
import com.sokoul.pc.theme.CardMetrics
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.title.LabeledRoundButton
import com.sokoul.pc.ui.FocusCard
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.LocalPinch
import com.sokoul.pc.ui.tvFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CONTROLS_HIDE_MS = 12_000L
private const val HINT_MS = 1_800L
private const val PRELOAD = 4
private const val TURN_MS = 240
private const val ZOOM_STEP = 0.25f
private const val MAX_ZOOM = 4f
private const val SIDE = 0.3f
private const val PAN_STEP = 160f
private const val TURN_SHIFT = 0.05f
private const val TURN_FADE = 0.5f
private const val AUTO_FRAME_MS = 16L
private val SPEEDS = listOf(0f, 0.6f, 1.2f, 2.2f)
private val DIMS = listOf(0f, 0.2f, 0.4f, 0.6f)

private enum class Panel { None, Pages, Chapters }

private suspend fun loadPage(page: ReadPage): ImageBitmap? = when (page) {
    is ReadPage.Url -> ImageLoader.load(page.url)
    is ReadPage.File -> withContext(Dispatchers.IO) { runCatching { Platform.decodeImage(page.read()) }.getOrNull() }
}

/** What the reader shows, for the tools. */
data class ReaderReport(
    val chapter: String,
    val page: Int,
    val total: Int,
    val mode: String,
    val rightToLeft: Boolean,
    val zoom: Float,
    val chapterIndex: Int,
    val chapters: Int,
    val fit: String,
    val controls: Boolean,
    val panel: String,
    val autoSpeed: Int,
    val dim: Int,
)

/**
 * A manga made for the screen it is on. Reading takes the whole picture and asks nothing of the remote but its arrows;
 * OK brings the tools, and Back puts them away.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MangaReader(deps: AppDeps, request: ReaderRequest, onToggleFullscreen: () -> Unit, onClose: () -> Unit) {
    val anime = deps.anime
    val saved = remember { anime?.readerPrefs() }
    val scope = rememberCoroutineScope()
    var chapterIndex by remember { mutableIntStateOf(request.startChapter.coerceIn(request.chapters.indices)) }
    var page by remember { mutableIntStateOf(request.startPage) }
    var mode by remember { mutableStateOf(if (request.mode == ReaderMode.Strip) ReaderMode.Strip else saved?.mode ?: if (Platform.IS_TV) ReaderMode.Double else ReaderMode.Single) }
    var fit by remember { mutableStateOf(saved?.fit ?: ReaderFit.Height) }
    var rtl by remember { mutableStateOf(request.rightToLeft) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var dim by remember { mutableIntStateOf(saved?.dim ?: 0) }
    var autoSpeed by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(!Platform.IS_TV) }
    var panel by remember { mutableStateOf(Panel.None) }
    var activity by remember { mutableIntStateOf(0) }
    var hint by remember { mutableIntStateOf(0) }
    var hintShown by remember { mutableStateOf(false) }
    val wide = remember { mutableStateMapOf<String, Boolean>() }
    val chapter = request.chapters[chapterIndex]
    val pages by produceState<List<ReadPage>?>(null, chapter.key) { value = runCatching { chapter.pages() }.getOrDefault(emptyList()) }
    val total = pages?.size ?: 0
    val turn = remember { Animatable(0f) }
    val root = remember { FocusRequester() }
    val firstTool = remember { FocusRequester() }
    val strip = rememberLazyListState()
    val pageScroll = rememberScrollState()
    val density = LocalDensity.current
    val scrollStep = with(density) { 620.dp.toPx() }
    val stripWidth = with(density) { (STRIP_WIDTH.toPx() * zoom).toDp() }
    val scrollsInPage = mode != ReaderMode.Strip && fit == ReaderFit.Width

    SideEffect {
        deps.readerReport = ReaderReport(chapter.label, page, total, mode.name, rtl, zoom, chapterIndex, request.chapters.size, fit.name, controls, panel.name, autoSpeed, dim)
    }
    LaunchedEffect(mode, fit, dim) { anime?.saveReaderPrefs(mode, fit, dim) }
    LaunchedEffect(controls, panel) {
        delay(FOCUS_SETTLE_MS)
        if (panel == Panel.None) runCatching { (if (controls) firstTool else root).requestFocus() }
    }
    LaunchedEffect(activity, controls, panel) {
        if (controls && panel == Panel.None && Platform.IS_TV) {
            delay(CONTROLS_HIDE_MS)
            controls = false
        }
    }
    LaunchedEffect(hint) {
        if (hint > 0) {
            hintShown = true
            delay(HINT_MS)
            hintShown = false
        }
    }
    // Back where it was left, chapter and page.
    LaunchedEffect(chapter.key, page, total) {
        if (total > 0) anime?.saveReading(request, chapter, page.coerceAtMost(total - 1), total)
    }
    LaunchedEffect(total) { if (total > 0 && page >= total) page = total - 1 }
    // The long strip opens on the page being read, then reports where the reader has scrolled to.
    LaunchedEffect(chapter.key, total, mode) {
        if (total > 0 && mode == ReaderMode.Strip) {
            strip.scrollToItem(page.coerceIn(0, total - 1))
            snapshotFlow { strip.firstVisibleItemIndex }.collect { page = it }
        }
    }
    LaunchedEffect(chapter.key, page, scrollsInPage) { if (scrollsInPage) pageScroll.scrollTo(0) }
    // The next pages are already there when they are asked for.
    LaunchedEffect(chapter.key, page, total) {
        pages?.drop(page)?.take(PRELOAD + 1)?.forEach { launch { loadPage(it) } }
    }

    fun goChapter(delta: Int, toEnd: Boolean = false) {
        val next = chapterIndex + delta
        if (next !in request.chapters.indices) {
            hint++
            return
        }
        chapterIndex = next
        page = if (toEnd) Int.MAX_VALUE else 0
        zoom = 1f
        pan = Offset.Zero
        hint++
    }

    /** The pages on screen now: a double page already printed as one image stands alone. */
    fun shownNow(list: List<ReadPage>, at: Int): List<Int> {
        val p = at.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val shown = ReaderLogic.shown(if (fit == ReaderFit.Width) ReaderMode.Single else mode, p, list.size, rtl)
        return if (shown.size == 2 && shown.any { wide["${chapter.key}-$it"] == true }) listOf(p) else shown
    }

    fun turnPage(forward: Boolean) {
        val list = pages ?: return
        if (list.isEmpty()) return
        val target = if (forward) {
            page + shownNow(list, page).size
        } else {
            val before = page - 1
            if (before >= 1 && shownNow(list, before - 1).size == 2) before - 1 else before
        }
        when {
            target >= total -> goChapter(1)
            target < 0 -> goChapter(-1, toEnd = true)
            else -> {
                page = target
                zoom = 1f
                pan = Offset.Zero
                hint++
                val from = if (forward == rtl) -1f else 1f
                scope.launch {
                    turn.snapTo(from)
                    turn.animateTo(0f, tween(TURN_MS, easing = FastOutSlowInEasing))
                }
            }
        }
    }

    fun changeZoom(delta: Float) {
        zoom = (zoom + delta).coerceIn(1f, MAX_ZOOM)
        if (zoom == 1f) pan = Offset.Zero
        activity++
    }

    // Reading hands free: the strip, or a page as wide as the screen, goes by on its own.
    LaunchedEffect(autoSpeed, mode, fit, chapter.key, total) {
        val speed = SPEEDS[autoSpeed]
        if (speed == 0f || total == 0 || !(mode == ReaderMode.Strip || scrollsInPage)) return@LaunchedEffect
        while (true) {
            delay(AUTO_FRAME_MS)
            val px = speed * density.density
            if (mode == ReaderMode.Strip) {
                if (!strip.canScrollForward) goChapter(1) else strip.scrollBy(px)
            } else if (pageScroll.value >= pageScroll.maxValue && pageScroll.maxValue > 0) {
                delay(AUTO_PAGE_PAUSE_MS)
                turnPage(true)
            } else {
                pageScroll.scrollBy(px)
            }
        }
    }

    // Fingers apart zooms in; together zooms out, then leaves.
    val pinch = LocalPinch.current
    DisposableEffect(pinch) {
        pinch.screenIn = { changeZoom(ZOOM_STEP * 2) }
        pinch.screenOut = { if (zoom > 1f) changeZoom(-ZOOM_STEP * 2) else onClose() }
        onDispose {
            pinch.screenIn = null
            pinch.screenOut = null
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).testTag("reader")
            .onPointerEvent(PointerEventType.Move) {
                if (!Platform.IS_TV) {
                    controls = true
                    activity++
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                if (e.keyboardModifiers.isCtrlPressed || panel != Panel.None) return@onPointerEvent
                if (mode != ReaderMode.Strip && !scrollsInPage) turnPage(e.changes.first().scrollDelta.y > 0)
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                activity++
                when {
                    e.key == Key.Escape && panel != Panel.None -> {
                        panel = Panel.None
                        true
                    }
                    e.key == Key.Escape && controls -> {
                        controls = false
                        true
                    }
                    e.key == Key.Escape -> {
                        onClose()
                        true
                    }
                    // The tools are open: the arrows and OK belong to them.
                    panel != Panel.None || controls -> false
                    e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                        controls = true
                        true
                    }
                    e.key == Key.DirectionLeft -> {
                        if (zoom > 1f) pan = pan.copy(x = pan.x + PAN_STEP) else if (mode != ReaderMode.Strip) turnPage(forward = rtl) else goChapter(-1)
                        true
                    }
                    e.key == Key.DirectionRight -> {
                        if (zoom > 1f) pan = pan.copy(x = pan.x - PAN_STEP) else if (mode != ReaderMode.Strip) turnPage(forward = !rtl) else goChapter(1)
                        true
                    }
                    e.key == Key.DirectionDown -> {
                        when {
                            mode == ReaderMode.Strip -> scope.launch { strip.animateScrollBy(scrollStep) }
                            scrollsInPage && pageScroll.value < pageScroll.maxValue -> scope.launch { pageScroll.animateScrollBy(scrollStep) }
                            zoom > 1f -> pan = pan.copy(y = pan.y - PAN_STEP)
                            else -> turnPage(true)
                        }
                        true
                    }
                    e.key == Key.DirectionUp -> {
                        when {
                            mode == ReaderMode.Strip -> scope.launch { strip.animateScrollBy(-scrollStep) }
                            scrollsInPage && pageScroll.value > 0 -> scope.launch { pageScroll.animateScrollBy(-scrollStep) }
                            zoom > 1f -> pan = pan.copy(y = pan.y + PAN_STEP)
                            else -> turnPage(false)
                        }
                        true
                    }
                    e.key == Key.Spacebar -> {
                        if (mode == ReaderMode.Strip || scrollsInPage) autoSpeed = if (autoSpeed == 0) 1 else 0 else turnPage(true)
                        true
                    }
                    e.key == Key.PageDown -> {
                        turnPage(true)
                        true
                    }
                    e.key == Key.PageUp || e.key == Key.Backspace -> {
                        turnPage(false)
                        true
                    }
                    e.key == Key.MoveHome -> {
                        page = 0
                        true
                    }
                    e.key == Key.MoveEnd -> {
                        page = (total - 1).coerceAtLeast(0)
                        true
                    }
                    e.key == Key.N || e.key == Key.MediaNext || e.key == Key.MediaFastForward -> {
                        goChapter(1)
                        true
                    }
                    e.key == Key.P || e.key == Key.MediaPrevious || e.key == Key.MediaRewind -> {
                        goChapter(-1)
                        true
                    }
                    e.key == Key.Plus || e.key == Key.Equals || e.key == Key.NumPadAdd -> {
                        changeZoom(ZOOM_STEP)
                        true
                    }
                    e.key == Key.Minus || e.key == Key.NumPadSubtract -> {
                        changeZoom(-ZOOM_STEP)
                        true
                    }
                    e.key == Key.Zero || e.key == Key.NumPad0 -> {
                        zoom = 1f
                        pan = Offset.Zero
                        true
                    }
                    e.key == Key.M -> {
                        mode = ReaderLogic.nextMode(mode)
                        true
                    }
                    e.key == Key.D -> {
                        rtl = !rtl
                        true
                    }
                    e.key == Key.L -> {
                        panel = Panel.Chapters
                        true
                    }
                    e.key == Key.F || e.key == Key.F11 -> {
                        onToggleFullscreen()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(root)
            .focusable(),
    ) {
        val width = constraints.maxWidth.toFloat()
        val list = pages
        when {
            list == null -> Label(L("Opening the chapter…", "Ouverture du chapitre…").text(), Type.Meta, Palette.Secondary, modifier = Modifier.align(Alignment.Center))
            // MangaDex still lists chapters whose pages it took down at a publisher's request: say so, and where to go.
            list.isEmpty() -> Label(
                L(
                    "This chapter is no longer available: its source took it down. The chapter list button shows the others.",
                    "Ce chapitre n'est plus disponible : sa source l'a retiré. Le bouton de la liste des chapitres montre les autres.",
                ).text(),
                Type.Meta,
                maxLines = 2,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 120.dp).testTag("reader-error"),
            )
            mode == ReaderMode.Strip -> LazyColumn(Modifier.fillMaxSize().testTag("reader-strip"), state = strip) {
                itemsIndexed(list, key = { i, _ -> "${chapter.key}-$i" }) { _, p ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { WidePage(p, Modifier.width(stripWidth)) }
                }
            }
            scrollsInPage -> Column(Modifier.fillMaxSize().verticalScroll(pageScroll).testTag("reader-width")) {
                WidePage(list[page.coerceIn(list.indices)], Modifier.fillMaxWidth())
            }
            else -> Box(
                Modifier.fillMaxSize().clipToBounds()
                    .pointerInput(chapter.key, rtl, mode) {
                        detectTapGestures { at ->
                            // The sides turn the page, the middle shows or hides the tools.
                            when {
                                at.x < width * SIDE -> turnPage(forward = rtl)
                                at.x > width * (1 - SIDE) -> turnPage(forward = !rtl)
                                else -> controls = !controls
                            }
                        }
                    }
                    .pointerInput(zoom) {
                        if (zoom > 1f) detectDragGestures { change, drag ->
                            change.consume()
                            pan += drag
                        }
                    }
                    .graphicsLayer {
                        translationX = turn.value * size.width * TURN_SHIFT + pan.x
                        translationY = pan.y
                        alpha = 1f - kotlin.math.abs(turn.value) * TURN_FADE
                        scaleX = zoom
                        scaleY = zoom
                    },
            ) {
                val shown = shownNow(list, page)
                Row(Modifier.fillMaxSize().testTag("reader-pages"), horizontalArrangement = Arrangement.Center) {
                    shown.forEachIndexed { i, index ->
                        // Two pages meet in the middle, as in an open book.
                        val side = if (shown.size == 1) Alignment.Center else if (i == 0) Alignment.CenterEnd else Alignment.CenterStart
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = side) {
                            // A webtoon read as pages (it came back from "Keep reading", whose book has no country):
                            // its tall pages switch the reader to the strip it is drawn for.
                            FitPage(list[index], onTall = { if (mode != ReaderMode.Strip) mode = ReaderMode.Strip }) { isWide -> wide["${chapter.key}-$index"] = isWide }
                        }
                    }
                }
            }
        }
        // Read at night: only the picture dims, never the tools.
        if (DIMS[dim] > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DIMS[dim])))

        // While reading, only a thread says how far one has got.
        if (total > 0 && !controls && panel == Panel.None) {
            val done = ((page + 1).toFloat() / total).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x22FFFFFF))) {
                Box(Modifier.align(if (rtl && mode != ReaderMode.Strip) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(done).background(Color(0x99FFFFFF)))
            }
        }
        AnimatedVisibility(hintShown && !controls && panel == Panel.None, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC1C1C1E)).padding(horizontal = 24.dp, vertical = 10.dp)) {
                Label("${chapter.label} · ${pageLabel(list, page, total) { l, at -> shownNow(l, at) }}", Type.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.testTag("reader-hint"))
            }
        }

        AnimatedVisibility(controls && panel == Panel.None, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(300.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000)))))
                Column(Modifier.padding(start = Metrics.PageMargin, top = 44.dp)) {
                    Label(request.title, Type.RowTitle, maxLines = 1)
                    Label(chapter.label, Type.Meta, Palette.BarText, maxLines = 1, modifier = Modifier.testTag("reader-chapter"))
                }
                chapter.credit?.let { Label(it, Type.Body, Palette.Secondary, modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = Metrics.PageMargin), maxLines = 1) }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = Metrics.PageMargin).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Label(pageLabel(list, page, total) { l, at -> shownNow(l, at) }, Type.Meta.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag("reader-page"), maxLines = 1)
                        Label(ReaderLogic.modeName(mode, rtl).text(), Type.Body, Palette.BarText, modifier = Modifier.testTag("reader-mode-name"), maxLines = 1)
                    }
                    if (total > 0) ReaderProgress(page, total, rtl && mode != ReaderMode.Strip) { p -> page = p }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabeledRoundButton(Icons.Filled.GridView, L("Pages", "Pages").text(), { panel = Panel.Pages }, Modifier.testTag("reader-pages-button"), firstTool, onFocused = { activity++ })
                        LabeledRoundButton(Icons.AutoMirrored.Filled.List, L("Chapters", "Chapitres").text(), { panel = Panel.Chapters }, Modifier.testTag("reader-chapters"), onFocused = { activity++ })
                        LabeledRoundButton(Icons.Filled.SkipPrevious, L("Previous chapter", "Chapitre précédent").text(), { goChapter(-1) }, Modifier.testTag("reader-prev-chapter"), onFocused = { activity++ })
                        LabeledRoundButton(Icons.Filled.SkipNext, L("Next chapter", "Chapitre suivant").text(), { goChapter(1) }, Modifier.testTag("reader-next-chapter"), onFocused = { activity++ })
                        LabeledRoundButton(Icons.Filled.AutoStories, ReaderLogic.modeShort(mode).text(), { mode = ReaderLogic.nextMode(mode) }, Modifier.testTag("reader-mode"), onFocused = { activity++ })
                        LabeledRoundButton(Icons.Filled.FitScreen, ReaderLogic.fitName(fit).text(), { fit = if (fit == ReaderFit.Height) ReaderFit.Width else ReaderFit.Height }, Modifier.testTag("reader-fit"), onFocused = { activity++ })
                        LabeledRoundButton(Icons.Filled.SwapHoriz, (if (rtl) L("Right to left", "Sens japonais") else L("Left to right", "Sens occidental")).text(), { rtl = !rtl }, Modifier.testTag("reader-direction"), onFocused = { activity++ })
                        LabeledRoundButton(Icons.Filled.ZoomOut, L("Zoom out", "Réduire").text(), { changeZoom(-ZOOM_STEP) }, Modifier.testTag("reader-zoom-out"), onFocused = { activity++ })
                        LabeledRoundButton(Icons.Filled.ZoomIn, L("Zoom in ${(zoom * 100).toInt()} %", "Agrandir ${(zoom * 100).toInt()} %").text(), { changeZoom(ZOOM_STEP) }, Modifier.testTag("reader-zoom-in"), onFocused = { activity++ })
                        LabeledRoundButton(Icons.Filled.BrightnessMedium, L("Brightness ${100 - (DIMS[dim] * 100).toInt()} %", "Luminosité ${100 - (DIMS[dim] * 100).toInt()} %").text(), { dim = (dim + 1) % DIMS.size }, Modifier.testTag("reader-brightness"), onFocused = { activity++ })
                        LabeledRoundButton(
                            Icons.Filled.Speed,
                            (if (autoSpeed == 0) L("Auto scroll off", "Défilement auto coupé") else L("Auto scroll $autoSpeed", "Défilement auto $autoSpeed")).text(),
                            {
                                autoSpeed = (autoSpeed + 1) % SPEEDS.size
                                if (autoSpeed > 0 && mode != ReaderMode.Strip) fit = ReaderFit.Width
                            },
                            Modifier.testTag("reader-auto"),
                            onFocused = { activity++ },
                        )
                    }
                }
            }
        }
        if (panel == Panel.Chapters && list != null) {
            ChapterList(request.chapters, chapterIndex, Modifier.align(Alignment.CenterEnd).padding(end = 60.dp, top = 40.dp, bottom = 40.dp)) { i ->
                chapterIndex = i
                page = 0
                panel = Panel.None
                controls = false
            }
        }
        if (panel == Panel.Pages && !list.isNullOrEmpty()) {
            PagesPanel(list, page, Modifier.align(Alignment.BottomStart)) { p ->
                page = p
                panel = Panel.None
                controls = false
                hint++
            }
        }
    }
}

private fun pageLabel(list: List<ReadPage>?, page: Int, total: Int, shown: (List<ReadPage>, Int) -> List<Int>): String {
    if (list.isNullOrEmpty() || total == 0) return ""
    val s = shown(list, page).sorted()
    return (if (s.size == 2) "${s[0] + 1}-${s[1] + 1}" else "${s[0] + 1}") + " / $total"
}

/** As tall as the screen, whatever its shape. */
@Composable
private fun FitPage(page: ReadPage, onTall: () -> Unit = {}, onSize: (wide: Boolean) -> Unit) {
    val image by produceState<ImageBitmap?>(null, page) { value = loadPage(page) }
    val bitmap = image
    if (bitmap == null) {
        Label(L("Loading…", "Chargement…").text(), Type.Body, Palette.Secondary, maxLines = 1)
    } else {
        LaunchedEffect(bitmap) {
            onSize(bitmap.width > bitmap.height)
            if (ReaderLogic.isStripPage(bitmap.width, bitmap.height)) onTall()
        }
        Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = Alignment.Center)
    }
}

/** As wide as it is given, and as tall as it needs. */
@Composable
private fun WidePage(page: ReadPage, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, page) { value = loadPage(page) }
    val bitmap = image
    if (bitmap == null) {
        Box(modifier.height(1000.dp), contentAlignment = Alignment.Center) { Label(L("Loading…", "Chargement…").text(), Type.Body, Palette.Secondary, maxLines = 1) }
    } else {
        Image(bitmap, null, modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)), contentScale = ContentScale.FillWidth)
    }
}

/** In a right-to-left book, the bar fills from the right too. */
@Composable
private fun ReaderProgress(page: Int, total: Int, fromRight: Boolean, onSeek: (Int) -> Unit) {
    val fraction = ((page + 1).toFloat() / total).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x44FFFFFF)).testTag("reader-progress")
            .pointerInput(total, fromRight) {
                detectTapGestures { at ->
                    val f = (at.x / size.width).coerceIn(0f, 1f).let { if (fromRight) 1f - it else it }
                    onSeek((f * (total - 1)).toInt())
                }
            },
    ) {
        Box(Modifier.align(if (fromRight) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight().fillMaxWidth(fraction).background(Palette.OnBackground))
    }
}

/** Every page of the chapter, to go straight to one. */
@Composable
private fun PagesPanel(pages: List<ReadPage>, current: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val state = rememberLazyListState((current - 1).coerceAtLeast(0))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(FOCUS_SETTLE_MS)
        runCatching { first.requestFocus() }
    }
    Column(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000), Color.Black))).padding(top = 90.dp, bottom = 36.dp).testTag("reader-pages-panel"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Label(L("Pages", "Pages").text(), Type.RowTitle, modifier = Modifier.padding(start = Metrics.PageMargin), maxLines = 1)
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = Metrics.PageMargin, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(CardMetrics.Gap)) {
            itemsIndexed(pages) { i, p ->
                val own = remember { FocusRequester() }
                val image by produceState<ImageBitmap?>(null, p) { value = loadPage(p) }
                FocusCard(THUMB_W, THUMB_H, { onPick(i) }, tag = "reader-thumb-$i", requester = if (i == current.coerceIn(pages.indices)) first else own) { _ ->
                    image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Box(Modifier.fillMaxSize().background(Palette.ButtonFill))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(10.dp)) {
                        Label("${i + 1}", Type.Body.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterList(chapters: List<ReadChapter>, current: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val state = rememberLazyListState(current)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(FOCUS_SETTLE_MS)
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        modifier.width(640.dp).fillMaxHeight().glassSurface(RoundedCornerShape(40.dp), 40.dp).padding(16.dp).testTag("reader-chapter-list"),
        state = state,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(chapters, key = { _, c -> c.key }) { i, c ->
            var has by remember { mutableStateOf(false) }
            val own = remember { FocusRequester() }
            val fill by animateColorAsState(if (has) Palette.FocusFill else if (i == current) Palette.ActiveFill else Color.Transparent)
            Box(
                Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(33.dp)).background(fill)
                    .tvFocusable(onFocusChanged = { has = it }, onClick = { onPick(i) }, requester = if (i == current) first else own)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Label(c.label, Type.Meta, if (has) Palette.OnFocus else Palette.OnBackground, maxLines = 1)
            }
        }
    }
}

private const val FOCUS_SETTLE_MS = 60L
private const val AUTO_PAGE_PAUSE_MS = 1_500L
private val STRIP_WIDTH = 1000.dp
private val THUMB_W = 190.dp
private val THUMB_H = 270.dp
