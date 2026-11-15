package com.sokoul.pc.playback.ui
import com.sokoul.pc.playback.session.LiveChannel
import com.sokoul.pc.playback.session.PlaybackSession
import com.sokoul.pc.playback.session.StreamHealth
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.Program
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.glassSurface
import com.sokoul.pc.ui.panelEnter
import com.sokoul.pc.ui.tvFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

internal fun clock(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms))

internal fun healthLabel(h: StreamHealth) = tr(
    when (h) {
        StreamHealth.Measuring -> Text.HealthMeasuring
        StreamHealth.Good -> Text.HealthGood
        StreamHealth.Weak -> Text.HealthWeak
        StreamHealth.Bad -> Text.HealthBad
    },
)

internal fun healthColor(h: StreamHealth) = when (h) {
    StreamHealth.Measuring -> Palette.Secondary
    StreamHealth.Good -> Color(0xFF34C759)
    StreamHealth.Weak -> Color(0xFFFF9F0A)
    StreamHealth.Bad -> Palette.Error
}

/** "6,2 Mb/s", or "850 kb/s" under one megabit. */
internal fun bitrateLabel(kbps: Int): String =
    if (kbps >= 1000) String.format(Locale.getDefault(), "%.1f Mb/s", kbps / 1000f) else "$kbps kb/s"

internal enum class LiveProgramAction { SetReminder, RemoveReminder, Replay, None }

internal fun liveProgramAction(program: Program, now: Long, catchupDays: Int, reminded: Boolean): LiveProgramAction = when {
    program.start > now -> if (reminded) LiveProgramAction.RemoveReminder else LiveProgramAction.SetReminder
    program.end <= now && program.end > program.start && catchupDays > 0 && program.start >= now - catchupDays * 24L * 60 * 60_000 -> LiveProgramAction.Replay
    else -> LiveProgramAction.None
}

/** What the player measures on this stream, as it plays, and the other streams of the same channel. */
@Composable
internal fun StreamDetailsPanel(session: PlaybackSession, status: String?, modifier: Modifier = Modifier) {
    val reading = session.reading
    val sample = reading.sample
    val live = session.live
    val variants by produceState(emptyList<LiveChannel>(), live?.key) {
        value = live?.let { withContext(Dispatchers.IO) { it.variants() } }.orEmpty()
    }
    Column(
        modifier.width(720.dp).heightIn(max = 900.dp).panelEnter().glassSurface(RoundedCornerShape(com.sokoul.pc.theme.Corners.Modal), com.sokoul.pc.theme.Corners.Modal)
            .verticalScroll(rememberScrollState()).padding(horizontal = com.sokoul.pc.ui.PanelMetrics.Edge, vertical = 40.dp).testTag("player-details"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(healthColor(reading.health)))
            Label(healthLabel(reading.health), com.sokoul.pc.theme.PanelType.Headline, modifier = Modifier.testTag("player-health"), maxLines = 1)
        }
        reading.averageKilobits?.let { kbps ->
            Label(bitrateLabel(kbps), com.sokoul.pc.theme.PanelType.Title, modifier = Modifier.testTag("player-bitrate"), maxLines = 1)
            if (reading.history.size >= 2) Sparkline(reading.history, Modifier.fillMaxWidth().height(70.dp))
        }
        if (sample != null) {
            val fps = sample.framesPerSecond?.let { String.format(Locale.getDefault(), "%.0f %s", it, tr(Text.FramesPerSecond)) }
            val size = sample.width.takeIf { it > 0 }?.let { "$it×${sample.height}" }
            DetailRow(tr(Text.DetailPicture), listOfNotNull(size, fps, sample.videoCodec).joinToString(" · "))
            val hz = sample.audioHz?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.1f kHz", it / 1000f) }
            val channels = sample.audioChannels?.takeIf { it > 0 }?.let { tr(Text.AudioChannels, it) }
            DetailRow(tr(Text.DetailSound), listOfNotNull(sample.audioCodec, hz, channels).joinToString(" · "))
            DetailRow(tr(Text.DetailLost), "${sample.lost} / ${sample.displayed + sample.lost}")
            if (sample.corrupted + sample.discontinuities > 0) DetailRow(tr(Text.DetailErrors), "${sample.corrupted + sample.discontinuities}")
        } else {
            status?.let { Label(it, com.sokoul.pc.theme.PanelType.Callout, Palette.Secondary, maxLines = 2) }
        }
        if (live != null && variants.size > 1) {
            Spacer(Modifier.height(10.dp))
            Label(tr(Text.OtherStreams), com.sokoul.pc.theme.PanelType.Section, Palette.Secondary, maxLines = 1)
            variants.forEach { v ->
                ListRow(v.title, null, v.key == live.key, Modifier.testTag("variant-${v.key}")) { session.playVariant(v) }
            }
        }
    }
}

/** The bitrate of the last minute: one thin line, no axis, the number above says the value. */
@Composable
private fun Sparkline(values: List<Int>, modifier: Modifier) {
    Canvas(modifier) {
        val top = values.max().coerceAtLeast(1).toFloat()
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val at = Offset(i * step, size.height * (1f - v / top * 0.92f))
            if (i == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
        }
        drawPath(path, Palette.OnBackground, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun DetailRow(name: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Label(name, com.sokoul.pc.theme.PanelType.Callout, Palette.Secondary, Modifier.width(200.dp), maxLines = 1)
        Label(value, com.sokoul.pc.theme.PanelType.Callout.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
    }
}

@Composable
private fun ListRow(title: String, detail: String?, current: Boolean, modifier: Modifier = Modifier, requester: FocusRequester = remember { FocusRequester() }, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val lead: (@Composable (Color) -> Unit)? = leading?.let { art -> @Composable { _: Color -> art() } }
    com.sokoul.pc.ui.PanelOption(title, modifier, subtitle = detail, selected = current, requester = requester, leading = lead, onClick = onClick)
}

/** The channels of the group, without leaving the picture. */
@Composable
internal fun ChannelsPanel(session: PlaybackSession, modifier: Modifier = Modifier, onClose: () -> Unit = {}, onPicked: () -> Unit) {
    val list = session.liveList
    val current = list.indexOfFirst { it.key == session.liveBaseKey }.coerceAtLeast(0)
    val state = rememberLazyListState(current)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(4) { androidx.compose.runtime.withFrameNanos { }; if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect } }
    // Holding Up or Down speeds up after half a second (HoldScroll decides, the list only moves).
    val hold = remember { HoldScroll() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    EdgeDrawer(start = true, width = LIVE_DRAWERS.channels.dp, modifier = modifier.testTag("player-channels"), onSideKey = onClose) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp).onPreviewKeyEvent { e ->
                val direction = when (e.key) {
                    Key.DirectionDown -> FocusDirection.Down
                    Key.DirectionUp -> FocusDirection.Up
                    else -> return@onPreviewKeyEvent false
                }
                when (e.type) {
                    KeyEventType.KeyUp -> {
                        hold.up()
                        false
                    }
                    KeyEventType.KeyDown -> {
                        val rows = hold.down()
                        if (rows == 1) {
                            false
                        } else {
                            repeat(rows) { focusManager.moveFocus(direction) }
                            true
                        }
                    }
                    else -> false
                }
            },
            state = state,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(list, key = { _, c -> c.key }) { i, c ->
                val program by produceState<String?>(null, c.key) { value = withContext(Dispatchers.IO) { c.program().now?.title } }
                ListRow(
                    com.sokoul.pc.live.TvCatalog.label(c.title), program, c.key == session.liveBaseKey, requester = if (i == current) first else remember { FocusRequester() },
                    leading = {
                        Box(Modifier.size(84.dp, 50.dp).clip(RoundedCornerShape(10.dp)).background(Palette.RowFill)) {
                            com.sokoul.pc.live.ChannelLogo(c.title, c.logo, Modifier.fillMaxHeight().fillMaxWidth().padding(4.dp))
                        }
                    },
                ) {
                    session.playLive(c, list)
                    onPicked()
                }
            }
        }
    }
}

/** The app is laid out on a 1920-wide reference (ScaledRoot): the drawers' widths are fixed in its units. */
private val LIVE_DRAWERS = DrawerWidths.of(com.sokoul.pc.ui.REFERENCE_WIDTH.toInt())

/** What this channel shows next. */
@Composable
internal fun ProgramsPanel(session: PlaybackSession, reminders: com.sokoul.pc.live.Reminders?, modifier: Modifier = Modifier, onClose: () -> Unit = {}, replayOnly: Boolean = false) {
    val live = session.live
    val programs by produceState(emptyList<Program>(), live?.key) {
        value = live?.let { withContext(Dispatchers.IO) { it.schedule() } }.orEmpty()
    }
    val now = System.currentTimeMillis()
    val replayDays = live?.capabilities?.catchupDays?.coerceAtLeast(0) ?: 0
    val replayCutoff = now - replayDays * 24L * 60 * 60_000
    val visiblePrograms = programs.filter { (!replayOnly && it.end > now) || (replayDays > 0 && it.start >= replayCutoff && it.end <= now) }
        .let { if (replayOnly) it.sortedByDescending { p -> p.start } else it }
    val focusedIndex = visiblePrograms.indexOfFirst { it.start <= now && it.end > now }
        .takeIf { it >= 0 } ?: 0
    val first = remember { FocusRequester() }
    LaunchedEffect(visiblePrograms, focusedIndex) {
        if (visiblePrograms.isNotEmpty()) runCatching { first.requestFocus() }
    }
    EdgeDrawer(start = false, width = LIVE_DRAWERS.programs.dp, modifier = modifier.testTag("player-programs"), onSideKey = onClose) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(if (replayOnly) "Replay" else tr(Text.Programs), Type.Body, Palette.Secondary, Modifier.padding(start = 22.dp, bottom = 6.dp), maxLines = 1)
            // What is on now, pictured: the programme's picture, else the channel's logo.
            if (!replayOnly && visiblePrograms.isNotEmpty()) com.sokoul.pc.live.ProgramPicture(
                visiblePrograms.firstOrNull { it.start <= now && it.end > now },
                live?.title.orEmpty(),
                live?.logo,
                Modifier.padding(horizontal = 22.dp, vertical = 8.dp).fillMaxWidth().aspectRatio(16f / 9f).testTag("player-programs-picture"),
            )
            if (visiblePrograms.isEmpty()) Label(if (replayOnly) com.sokoul.pc.data.L("No provider replay available for this channel", "Aucun replay fourni pour cette chaîne").text() else tr(Text.NoPrograms), Type.Meta, Palette.Secondary, Modifier.padding(start = 22.dp))
            visiblePrograms.forEachIndexed { i, p ->
                val channelId = session.liveBaseKey ?: live?.key.orEmpty()
                val reminded = reminders?.has(channelId, p.start) == true
                val action = liveProgramAction(p, now, replayDays, reminded)
                val detail = buildList {
                    if (replayOnly) add(DateTimeFormatter.ofPattern("EEE dd MMM", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(p.start)))
                    add("${clock(p.start)} - ${clock(p.end)}")
                    if (action == LiveProgramAction.RemoveReminder) add(tr(Text.LiveReminded))
                    else if (action == LiveProgramAction.SetReminder && reminders != null) add(tr(Text.LiveRemind))
                    if (action == LiveProgramAction.Replay) add(tr(Text.LiveReplay))
                }.joinToString(" · ")
                ListRow(
                    p.title,
                    detail,
                    reminded || (p.start <= now && p.end > now),
                    modifier = Modifier.testTag("player-program-${p.start}"),
                    requester = if (i == focusedIndex) first else remember { FocusRequester() },
                ) {
                    when (action) {
                        LiveProgramAction.SetReminder, LiveProgramAction.RemoveReminder -> reminders?.toggle(com.sokoul.pc.live.Reminder(
                            channelId, live?.let { com.sokoul.pc.live.TvCatalog.shortLabel(it.title) }.orEmpty(), p.title, p.start,
                        ))
                        LiveProgramAction.Replay -> session.playReplay(p)
                        LiveProgramAction.None -> Unit
                    }
                }
            }
        }
    }
}
