package com.sokoul.pc.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import com.sokoul.desktop.data.AudioPreference
import com.sokoul.desktop.data.BufferProfile
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.desktop.data.MaxQuality
import com.sokoul.desktop.data.MaxSize
import com.sokoul.desktop.data.PreferredOrigin
import com.sokoul.desktop.data.StartMode
import com.sokoul.desktop.data.SubtitlePreference
import com.sokoul.pc.playback.sources.gigabytes
import com.sokoul.pc.playback.ui.label
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.MyList
import com.sokoul.pc.i18n.Strings
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.theme.CardMetrics
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.PosterCard
import com.sokoul.pc.ui.tvFocusable

@Composable
fun LibraryScreen(catalog: com.sokoul.pc.data.CatalogApi, myList: MyList, onOpen: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Background).testTag("library")) {
        if (myList.items.isEmpty()) {
            // The empty list says what it is for and how to fill it, in the round "+" the title pages use.
            androidx.compose.foundation.layout.Column(
                Modifier.align(Alignment.Center).testTag("library-empty"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(Modifier.size(104.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.ButtonFill).border(1.dp, Palette.Hairline, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Add, null, Modifier.size(52.dp), tint = Palette.Secondary)
                }
                Label(tr(Text.LibraryEmpty), Type.RowTitle, Palette.OnBackground, maxLines = 2)
                Label(
                    com.sokoul.pc.data.L("Press + on a film or series page to keep it here.", "Appuyez sur + sur la fiche d'un film ou d'une série pour le garder ici.").text(),
                    Type.Meta.copy(fontSize = 22.sp), Palette.Secondary, maxLines = 2,
                )
            }
        } else {
            val snapshot = myList.items.toList()
            LazyVerticalGrid(
                GridCells.Adaptive(CardMetrics.PosterWidth),
                contentPadding = PaddingValues(start = Metrics.PageMargin, end = Metrics.PageMargin, top = 170.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(CardMetrics.Gap),
                verticalArrangement = Arrangement.spacedBy(CardMetrics.Gap),
            ) {
                items(snapshot.size, key = { snapshot[it].id }) { i ->
                    PosterCard(catalog, snapshot[i], null, captionAlways = false, rank = null, onClick = { onOpen(snapshot, i, it) }, tag = "library-$i")
                }
            }
        }
    }
}

internal fun startLabel(m: StartMode) = tr(
    when (m) {
        StartMode.ReadyFirst -> Text.PrefStartReady
        StartMode.WaitBest -> Text.PrefStartBest
        StartMode.Ask -> Text.PrefStartAsk
    },
)

internal fun audioLabel(a: AudioPreference) = when (a) {
    AudioPreference.System -> tr(Text.PrefSystem)
    AudioPreference.Vf -> "VF"
    AudioPreference.Multi -> "MULTI"
    AudioPreference.Vostfr -> "VOSTFR"
    AudioPreference.Vo -> "VO"
}

internal fun bufferLabel(b: BufferProfile) = tr(
    when (b) {
        BufferProfile.LOW -> Text.PrefBufferFast
        BufferProfile.NORMAL -> Text.PrefBufferNormal
        BufferProfile.HIGH -> Text.PrefBufferStable
    },
)

internal fun originLabel(o: PreferredOrigin) = when (o) {
    PreferredOrigin.Automatic -> tr(Text.PrefAutomatic)
    PreferredOrigin.Debrid -> tr(Text.SourceRealDebrid)
    PreferredOrigin.Iptv -> tr(Text.SourceIptv)
}

@Composable
internal fun <T> ChoiceRow(label: String, tag: String, choices: List<T>, initial: T, name: (T) -> String, onChange: (T) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    SettingsRow(label, name(value), false, remember { FocusRequester() }, {
        value = choices[(choices.indexOf(value) + 1) % choices.size]
        onChange(value)
    }, tag)
}

@Composable
internal fun SettingsRow(label: String, value: String?, chevron: Boolean, requester: FocusRequester, onClick: () -> Unit, tag: String? = null) {
    com.sokoul.pc.ui.PanelOption(
        label, if (tag != null) Modifier.testTag(tag) else Modifier, value = value, chevron = chevron,
        requester = requester, restFill = com.sokoul.pc.theme.Glass.RowRest, onClick = onClick,
    )
}
