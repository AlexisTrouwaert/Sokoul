package com.sokoul.pc.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.data.net.TmdbPersonInfo
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.PersonCredits
import com.sokoul.pc.data.kind
import com.sokoul.pc.theme.CardMetrics
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.heavyBlur
import com.sokoul.pc.ui.BackButton
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.FocusSource
import com.sokoul.pc.ui.PosterCard

/** An actor's page: their portrait and name over the ambiance of their best known title, then everything they appear in. */
@Composable
fun PersonScreen(
    deps: AppDeps,
    personId: Int,
    name: String,
    photo: String?,
    onClose: () -> Unit,
    onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit,
) {
    val person by produceState<TmdbPersonInfo?>(null, personId) { value = deps.catalog.person(personId) }
    val works = remember(person) { person?.let(PersonCredits::of).orEmpty() }
    val root = remember { FocusRequester() }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }
    LaunchedEffect(works.isNotEmpty()) {
        if (works.isNotEmpty()) {
            withFrameNanos { }
            FocusSource.lastWasMouse = false
            runCatching { first.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .testTag("person")
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .focusRequester(root)
            .focusable(),
    ) {
        Box(Modifier.fillMaxWidth().height(760.dp)) {
            Art(works.firstOrNull { it.backdrop != null }?.backdrop, Modifier.fillMaxSize().heavyBlur(60.dp), placeholder = Palette.Background)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x80000000), 1f to Palette.Background)))
        }
        LazyVerticalGrid(
            GridCells.Fixed(COLUMNS),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Metrics.PageMargin, end = Metrics.PageMargin, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(CardMetrics.Gap - 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardMetrics.Gap),
        ) {
            item(key = "head", span = { GridItemSpan(COLUMNS) }) {
                Column(Modifier.fillMaxWidth().padding(top = 70.dp, bottom = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Art(person?.photo ?: photo, Modifier.size(250.dp).clip(CircleShape))
                    Spacer(Modifier.height(24.dp))
                    BasicText(person?.name ?: name, Modifier.testTag("person-name"), style = Type.RowTitle.copy(fontSize = 54.sp, color = Palette.OnBackground))
                }
            }
            items(works.size, key = { works[it].kind() + works[it].id }) { i ->
                PosterCard(
                    deps.catalog, works[i], null, captionAlways = false, rank = null,
                    onClick = { onOpenTitle(works, i, it) },
                    tag = "person-work-$i",
                    requester = if (i == 0) first else remember { FocusRequester() },
                )
            }
        }
        BackButton(onClick = onClose, modifier = Modifier.padding(start = 40.dp, top = 44.dp).testTag("person-back"))
    }
}

private const val COLUMNS = 6
