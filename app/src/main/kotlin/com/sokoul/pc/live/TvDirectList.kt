package com.sokoul.pc.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.Platform
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Form
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.tvFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How the TV page shows its channels: the list of what is on now (Direct), or the grid of the evening (Guide). */
enum class TvView { Direct, Guide }

/**
 * The Direct view (Figma "V2 · TV · TV · direct"): one row per channel — number and ★, logo, what is on with its hours
 * and the time left, a progress line, then what follows. The focused row is white and drives the header above; OK
 * watches. Schedules are read once for the whole group, off the interface thread.
 */
@Composable
internal fun TvDirectList(
    tv: TvCatalog,
    channels: List<Channel>,
    focusRow: Int,
    favorites: Set<String>,
    now: Long,
    firstRow: FocusRequester,
    upTarget: FocusRequester?,
    onSchedules: (Map<String, List<Program>>) -> Unit,
    onFocusRow: (Int) -> Unit,
    onWatch: (Channel) -> Unit,
    onFavorite: (Channel) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenActions: () -> Unit,
) {
    val television = Platform.IS_TV && !Form.tablet && !Form.isPhone
    val window = if (television) focusRow / 12 else 0
    val schedules by produceState(emptyMap<String, List<Program>>(), channels, window) {
        val visible = if (television) channels.subList(
            (window * 12 - 12).coerceIn(0, channels.size),
            (window * 12 + 24).coerceIn(0, channels.size),
        ) else channels
        value = withContext(Dispatchers.IO) { runCatching { tv.schedules(visible) }.getOrDefault(emptyMap()) }
        onSchedules(value)
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (focusRow - 2).coerceAtLeast(0))
    val requesters = remember(channels) { HashMap<Int, FocusRequester>() }
    LaunchedEffect(channels, focusRow) {
        if (television && focusRow in channels.indices && list.layoutInfo.visibleItemsInfo.none { it.index == focusRow }) {
            list.scrollToItem((focusRow - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("tv-direct"),
        state = list,
        contentPadding = PaddingValues(start = Metrics.PageMargin, end = Metrics.PageMargin, top = 14.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(channels.size, key = { channels[it].id }) { i ->
            val c = channels[i]
            val rows = schedules[c.id].orEmpty()
            val nowNext = com.sokoul.desktop.data.LiveLogic.nowNext(rows, now)
            val requester = if (i == focusRow) firstRow else requesters.getOrPut(i) { FocusRequester() }
            DirectRow(
                c, nowNext.now, nowNext.next, c.id in favorites, now, requester,
                Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionUp -> if (i == 0 && upTarget != null) { runCatching { upTarget.requestFocus() }; true } else false
                        Key.DirectionLeft -> { onOpenDrawer(); true }
                        Key.Menu, Key.F -> if (e.key == Key.F) { onFavorite(c); true } else { onOpenActions(); true }
                        else -> false
                    }
                },
                onFocused = { onFocusRow(i) },
            ) { onWatch(c) }
        }
    }
}

@Composable
private fun DirectRow(
    c: Channel, now: Program?, next: Program?, favorite: Boolean, t: Long, requester: FocusRequester, modifier: Modifier,
    onFocused: () -> Unit, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color(0xFF1C1C1E), tween(140))
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, tween(140))
    val ink = if (focused) Palette.OnFocus else Palette.OnBackground
    val soft = if (focused) Palette.OnFocus.copy(alpha = 0.6f) else Palette.Secondary
    val wide = !(com.sokoul.pc.ui.Form.tabletPortrait || com.sokoul.pc.ui.Form.isPortrait)
    Row(
        modifier.fillMaxWidth().height(ROW_H).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp)).background(fill)
            .tvFocusable(onFocusChanged = { focused = it; if (it) onFocused() }, onClick = onClick, requester = requester)
            .padding(horizontal = 18.dp)
            .testTag("tv-direct-${c.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(Modifier.size(132.dp, 74.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E0E10)), contentAlignment = Alignment.Center) {
            ChannelLogo(c.name, c.logo, Modifier.size(112.dp, 58.dp), guideId = c.epgKey)
        }
        // The programme first, on a line of its own; the channel and the time left under it, then the progress.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Label(now?.title ?: TvCatalog.shortLabel(c.name), Type.Meta.copy(fontWeight = FontWeight.SemiBold, fontSize = 30.sp), ink, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (favorite) Icon(Icons.Filled.Star, null, Modifier.size(20.dp), tint = ink)
                Label(
                    listOfNotNull(c.number.takeIf { it > 0 }?.toString(), TvCatalog.shortLabel(c.name).takeIf { now != null }, now?.let { remaining(it, t) }).joinToString(" · "),
                    Type.Body, soft, maxLines = 1,
                )
            }
            if (now != null) Box(Modifier.fillMaxWidth(0.7f).height(4.dp).clip(CircleShape).background(if (focused) Color(0x26000000) else Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(((t - now.start).toFloat() / (now.end - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)).background(ink))
            }
        }
        // What follows, only where the width allows it.
        if (wide) Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            next?.let {
                Label(L("Next · ", "Ensuite · ").text() + hm(it.start), Type.Body, soft, maxLines = 1)
                Label(it.title, Type.Body.copy(fontSize = 26.sp), ink.copy(alpha = 0.85f), maxLines = 1)
            }
        }
    }
}

private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** "20 min left", "1 h 10 left". */
internal fun remaining(p: Program, t: Long): String? {
    val min = ((p.end - t) / 60_000L).toInt()
    if (min <= 0 || t < p.start) return null
    val text = if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(min % 60)}"
    return L("$text left", "$text restantes").text()
}

/**
 * The TV page's controls, by view (spec `live-tv`): the group, Direct | Guide and search always; Now / Tonight / Tomorrow
 * only in the Guide, where they move the grid. Upright, the active group gets its own line.
 */
@Composable
internal fun TvControlRow(
    groupName: String,
    count: Int?,
    view: TvView,
    groupRequester: FocusRequester?,
    viewRequester: FocusRequester?,
    modifier: Modifier,
    onGroups: () -> Unit,
    onView: (TvView) -> Unit,
    onNow: () -> Unit,
    onTonight: () -> Unit,
    onTomorrow: () -> Unit,
    round: @Composable RowScope.() -> Unit,
) {
    var jump by remember { mutableIntStateOf(0) }
    val upright = com.sokoul.pc.ui.Form.tabletPortrait || com.sokoul.pc.ui.Form.isPortrait
    val group: @Composable () -> Unit = {
        Capsule(groupRequester ?: remember { FocusRequester() }, Modifier.testTag("tv-group-chip"), onClick = onGroups) { ink ->
            Icon(Icons.AutoMirrored.Filled.List, L("Channel groups", "Groupes de chaînes").text(), Modifier.size(26.dp), tint = ink)
            Label(groupName, Type.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 27.sp), ink,
                Modifier.widthIn(max = if (upright) 400.dp else 320.dp), maxLines = 1)
            count?.let { Label(L("$it channels", "$it chaînes").text(), Type.Body, ink.copy(alpha = 0.75f), maxLines = 1) }
        }
    }
    val jumps: @Composable () -> Unit = {
        Segments(listOf(L("Now", "Maintenant").text(), L("Tonight", "Ce soir").text(), L("Tomorrow", "Demain").text()), jump, "tv-jump") {
            jump = it
            when (it) { 0 -> onNow(); 1 -> onTonight(); else -> onTomorrow() }
        }
    }
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(CONTROL_H).padding(horizontal = Metrics.PageMargin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!upright) group()
            Segments(listOf(L("Direct", "Direct").text(), L("Guide", "Guide").text()), view.ordinal, "tv-view", viewRequester) { onView(TvView.entries[it]) }
            if (!upright && view == TvView.Guide) jumps()
            Spacer(Modifier.weight(1f))
            round()
        }
        if (upright) {
            Box(Modifier.padding(horizontal = Metrics.PageMargin)) { group() }
            if (view == TvView.Guide) Box(Modifier.padding(horizontal = Metrics.PageMargin)) { jumps() }
        }
    }
}

@Composable
private fun Capsule(requester: FocusRequester, modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.(Color) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color(0xFF2C2C2E), tween(140))
    val ink = if (focused) Palette.OnFocus else Palette.OnBackground
    Row(
        modifier.height(CONTROL_H).clip(RoundedCornerShape(50)).background(fill)
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content(ink) }
}

/** A segmented control on a dark track: the chosen segment grey, the focused one white. */
@Composable
private fun Segments(labels: List<String>, chosen: Int, tag: String, requester: FocusRequester? = null, onPick: (Int) -> Unit) {
    Row(
        Modifier.height(CONTROL_H).clip(RoundedCornerShape(50)).background(Color(0xFF2C2C2E)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            var focused by remember { mutableStateOf(false) }
            val fill by animateColorAsState(
                when { focused -> Palette.FocusFill; i == chosen -> Color(0xFF545458); else -> Color.Transparent }, tween(140),
            )
            Box(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(50)).background(fill)
                    .tvFocusable(onFocusChanged = { focused = it }, onClick = { onPick(i) }, requester = if (i == chosen && requester != null) requester else remember { FocusRequester() })
                    .padding(horizontal = 20.dp).testTag("$tag-$i"),
                contentAlignment = Alignment.Center,
            ) {
                Label(label, Type.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 26.sp), if (focused) Palette.OnFocus else Palette.OnBackground, maxLines = 1)
            }
        }
    }
}

private val ROW_H = 112.dp
internal val CONTROL_H = if (Platform.IS_TV) 60.dp else 56.dp

/** The Direct view's header height: the picture takes the width upright (16:9 of 1120 dp), two thirds sideways. */
internal fun directHeaderHeight(): androidx.compose.ui.unit.Dp =
    if (com.sokoul.pc.ui.Form.tabletPortrait || com.sokoul.pc.ui.Form.isPortrait) 640.dp else 560.dp

/**
 * The channel playing, large (after the Apple TV app's live pages): its picture fills the header — the whole width
 * upright, the right of the page sideways — and its name, programme, time left and Watch sit over it at the bottom
 * left, on a shade that keeps them readable whatever the picture.
 */
@Composable
internal fun ImmersiveLiveHeader(
    channel: Channel,
    program: Program?,
    now: Long,
    watchFocus: FocusRequester,
    onWatch: () -> Unit,
    preview: @Composable BoxScope.() -> Unit,
) {
    val upright = com.sokoul.pc.ui.Form.tabletPortrait || com.sokoul.pc.ui.Form.isPortrait
    val live = program != null && program.start <= now && program.end > now
    val tab = com.sokoul.pc.ui.LocalActiveTab.current
    Box(Modifier.fillMaxSize().testTag("tv-hero")) {
        Box(
            if (upright) Modifier.fillMaxSize()
            else Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.68f),
        ) {
            preview()
        }
        // The shade: from the left sideways, from the bottom upright; black where the text is.
        Box(
            Modifier.fillMaxSize().background(
                if (upright) androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent, 0.6f to Color(0x99000000), 1f to Palette.Background)
                else androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Palette.Background, 0.32f to Palette.Background, 0.5f to Color(0xB3000000), 0.75f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.75f to Color.Transparent, 1f to Palette.Background)))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = Metrics.PageMargin, end = Metrics.PageMargin, bottom = 28.dp)
                .widthIn(max = if (upright) 1000.dp else 900.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(104.dp, 52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                    ChannelLogo(channel.name, channel.logo, Modifier.size(92.dp, 44.dp), guideId = channel.epgKey)
                }
                // Without a programme the title below is the channel's name: said once, not twice.
                if (program != null) Label(TvCatalog.shortLabel(channel.name), Type.Meta.copy(fontWeight = FontWeight.SemiBold), Palette.OnBackground, maxLines = 1)
                if (live) Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE5342B)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Label(L("LIVE", "DIRECT").text(), Type.Body.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp), Color.White, maxLines = 1) }
            }
            Label(
                program?.title ?: TvCatalog.shortLabel(channel.name),
                Type.RowTitle.copy(fontFamily = com.sokoul.pc.theme.InterDisplay, fontSize = 54.sp, lineHeight = 58.sp),
                Palette.OnBackground, Modifier.testTag("tv-hero-title"), maxLines = 2,
            )
            if (program != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Label("${hm(program.start)} – ${hm(program.end)}", Type.Meta, Palette.Secondary, maxLines = 1)
                if (live) {
                    Box(Modifier.width(220.dp).height(5.dp).clip(CircleShape).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(((now - program.start).toFloat() / (program.end - program.start).coerceAtLeast(1)).coerceIn(0f, 1f)).background(Palette.OnBackground))
                    }
                    remaining(program, now)?.let { Label(it, Type.Meta, Palette.Secondary, maxLines = 1) }
                }
            }
            program?.description?.takeIf { it.isNotBlank() }?.let { Label(it, Type.Body, Palette.Secondary, maxLines = 2) }
            com.sokoul.pc.ui.PillButton(
                L("Watch full screen", "Regarder en plein écran").text(), onWatch,
                Modifier.padding(top = 6.dp).focusProperties { if (tab != null) up = tab }.testTag("tv-watch"), watchFocus,
            )
        }
    }
}
