package com.sokoul.pc.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.tvFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SEARCH_DELAY_MS = 250L

/**
 * What to watch, by name: typing in the guide opens this over it, on channels and on what they will show. It needs no
 * field to click: the first letter typed is already the search.
 */
@Composable
fun GuideSearch(tv: TvCatalog, firstLetters: String, onPick: (TvCatalog.Hit) -> Unit, onClose: () -> Unit) {
    if (com.sokoul.pc.Platform.IS_TV) {
        PersonalGuideSearch(tv, firstLetters, onPick, onClose)
        return
    }
    var query by remember { mutableStateOf(firstLetters) }
    val hits by produceState(emptyList<TvCatalog.Hit>(), query) {
        delay(SEARCH_DELAY_MS)
        value = withContext(Dispatchers.IO) { tv.search(query) }
    }
    val keys = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).testTag("tv-search")
            .focusRequester(keys)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val char = e.utf16CodePoint.toChar()
                when {
                    e.key == Key.Escape -> {
                        onClose()
                        true
                    }
                    e.key == Key.Backspace -> {
                        if (query.length <= 1) onClose() else query = query.dropLast(1)
                        runCatching { keys.requestFocus() }
                        true
                    }
                    e.key == Key.DirectionDown && hits.isNotEmpty() -> {
                        runCatching { first.requestFocus() }
                        false
                    }
                    char.isLetterOrDigit() || char == ' ' -> {
                        query += char
                        runCatching { keys.requestFocus() }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 150.dp).width(1000.dp).clip(RoundedCornerShape(Metrics.MenuCorner)).background(Color(0xFA1C1C1E)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Label(query, Type.RowTitle, modifier = Modifier.padding(start = 20.dp).testTag("tv-search-query"), maxLines = 1)
            if (hits.isEmpty()) Label(tr(Text.LiveNoResult), Type.Body, Palette.Secondary, Modifier.padding(start = 20.dp, bottom = 10.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(hits, key = { i, h -> "$i${h.channel.id}${h.program?.start}" }) { i, h ->
                    var has by remember { mutableStateOf(false) }
                    val fill by animateColorAsState(if (has) Palette.FocusFill else Color.Transparent)
                    val own = remember { FocusRequester() }
                    Column(
                        Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(30.dp)).background(fill)
                            .tvFocusable(onFocusChanged = { has = it }, onClick = { onPick(h) }, requester = if (i == 0) first else own)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Label(h.program?.title ?: TvCatalog.shortLabel(h.channel.name), Type.Meta, if (has) Palette.OnFocus else Palette.OnBackground, maxLines = 1)
                        val detail = h.program?.let { "${TvCatalog.shortLabel(h.channel.name)} · ${clock(it.start)} – ${clock(it.end)}" } ?: tr(Text.LiveChannelWord)
                        Label(detail, Type.Body, if (has) Palette.OnFocus.copy(alpha = 0.6f) else Palette.Secondary, maxLines = 1)
                    }
                }
            }
        }
    }
}
