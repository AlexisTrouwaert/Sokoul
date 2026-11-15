package com.sokoul.pc.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.tvFocusable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun WatchlistDrawer(
    reminders: List<Reminder>,
    modifier: Modifier = Modifier,
    onWatch: (Reminder) -> Unit,
    onRemove: (Reminder) -> Unit,
    onClose: () -> Unit,
) {
    val tv = com.sokoul.pc.Platform.IS_TV
    val first = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    // The drawer must hold the remote even when empty, or focus falls through to the navigation bar behind it.
    LaunchedEffect(reminders.isEmpty()) {
        repeat(if (tv) 6 else 1) { attempt ->
            if (attempt == 0) androidx.compose.runtime.withFrameNanos { } else kotlinx.coroutines.delay(80)
            runCatching { if (reminders.isEmpty()) { if (tv) emptyFocus.requestFocus() } else first.requestFocus() }
        }
    }
    // On TV the drawer matches the group column: same width, same text edge as the guide, Apple TV type sizes.
    val inset = if (tv) 24.dp else 12.dp
    Column(
        modifier.fillMaxHeight()
            .background(FormulerGuideStyle.sheet)
            .padding(start = if (tv) Metrics.PageMargin - inset else Metrics.PageMargin, end = if (tv) 32.dp else 24.dp,
                top = if (tv) 64.dp else 120.dp, bottom = 40.dp)
            .then(if (tv && reminders.isEmpty()) Modifier.focusRequester(emptyFocus).focusable() else Modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Escape || event.key == Key.DirectionRight) {
                    onClose()
                    true
                } else false
            }
            .testTag("tv-watchlist-drawer"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Label(if (tv) com.sokoul.pc.data.L("Reminders", "Rappels").text() else tr(Text.LiveWatchlist),
            if (tv) Type.RowTitle.copy(fontSize = 48.sp) else Type.RowTitle, modifier = Modifier.padding(start = inset).testTag("tv-watchlist-title"), maxLines = 1)
        if (reminders.isEmpty()) {
            Label(if (tv) com.sokoul.pc.data.L("No reminder yet. In the guide, choose a programme to come and press OK.",
                    "Aucun rappel pour l'instant. Dans le guide, choisissez une émission à venir et appuyez sur OK.").text() else tr(Text.LiveWatchlistEmpty),
                if (tv) Type.Body.copy(fontSize = 29.sp) else Type.Body, Palette.Secondary, Modifier.padding(start = inset, top = 4.dp).fillMaxWidth(), maxLines = 4)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(reminders, key = { "${it.channelId}:${it.start}" }) { reminder ->
                    var focused by remember { mutableStateOf(false) }
                    val ownFocus = remember { FocusRequester() }
                    val rowFocus = if (reminder == reminders.first()) first else ownFocus
                    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color.Transparent)
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp)
                            .clip(RoundedCornerShape(22.dp)).background(fill)
                            .padding(start = if (tv) inset else 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            Modifier.weight(1f).tvFocusable(
                                onFocusChanged = { focused = it },
                                onClick = { onWatch(reminder) },
                                requester = rowFocus,
                            ).padding(vertical = 4.dp).testTag("tv-watchlist-watch"),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Label(reminder.title, if (tv) Type.Meta.copy(fontSize = 31.sp, fontWeight = FontWeight.SemiBold) else Type.Meta,
                                if (focused) Palette.OnFocus else Palette.OnBackground, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Label(
                                    reminder.channel,
                                    if (tv) Type.Body.copy(fontSize = 25.sp) else Type.Body,
                                    if (focused) Palette.OnFocus.copy(alpha = 0.72f) else Palette.Secondary,
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Label(
                                    formatStart(reminder.start),
                                    if (tv) Type.Body.copy(fontSize = 25.sp) else Type.Body,
                                    if (focused) Palette.OnFocus.copy(alpha = 0.72f) else Palette.Secondary,
                                    maxLines = 1,
                                )
                            }
                        }
                        var removeFocused by remember { mutableStateOf(false) }
                        val removeFill by animateColorAsState(if (removeFocused) Palette.FocusFill else Palette.ButtonFill)
                        val removeShape = RoundedCornerShape(50)
                        Box(
                            Modifier.width(104.dp).heightIn(min = 48.dp).clip(removeShape).background(removeFill)
                                .border(1.dp, if (removeFocused) Color.Transparent else Palette.Hairline, removeShape)
                                .tvFocusable(onFocusChanged = { removeFocused = it }, onClick = { onRemove(reminder) })
                                .padding(horizontal = 8.dp).testTag("tv-watchlist-remove"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Label(
                                tr(Text.LiveRemoveReminder),
                                Type.Meta.copy(fontWeight = FontWeight.SemiBold),
                                if (removeFocused) Palette.OnFocus else Palette.OnBackground,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatStart(timestamp: Long): String {
    val locale = if (com.sokoul.pc.i18n.Strings.isFrench()) Locale.FRENCH else Locale.ENGLISH
    val pattern = if (locale == Locale.FRENCH) "d MMM · HH:mm" else "MMM d · HH:mm"
    val formatter = DateTimeFormatter.ofPattern(pattern, locale)
    return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(formatter)
}
