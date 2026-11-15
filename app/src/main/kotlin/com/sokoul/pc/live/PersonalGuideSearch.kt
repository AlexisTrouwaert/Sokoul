package com.sokoul.pc.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.pc.data.L
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun PersonalGuideSearch(tv: TvCatalog, initial: String, onPick: (TvCatalog.Hit) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf(initial) }
    var programs by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var keyboard by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val result by produceState(TvCatalog.SearchPage(emptyList(), false), query, programs, page) {
        loading = true; error = false
        value = TvCatalog.SearchPage(emptyList(), false)
        delay(250)
        try {
            val loaded = withContext(Dispatchers.IO) { tv.searchTv(query, programs, page) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            value = loaded
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { error = true }
        finally { loading = false }
    }
    val first = remember { FocusRequester() }
    val edit = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(keyboard, result, page) {
        if (!keyboard) { list.scrollToItem(0); withFrameNanos { }; runCatching { if (result.hits.isNotEmpty()) first.requestFocus() else edit.requestFocus() } }
    }
    Box(Modifier.fillMaxSize().background(FormulerGuideStyle.scrim)
        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else false }
        .testTag("tv-search"), contentAlignment = Alignment.Center) {
        TvPanel(L("Find something to watch", "Trouvez votre programme").text(),
            L("Channels by name or number · Programmes from your guide", "Chaînes par nom ou numéro · Programmes de votre guide").text(), width = TvPanelSize.Wide.width, height = TvPanelSize.Wide.height,
            footer = L("D-pad to type and browse · Back returns to your selection", "Flèches pour saisir et parcourir · Retour retrouve votre sélection").text()) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (keyboard) TvTextKeyboard(query, { query = it; page = 0 }, { keyboard = false })
                    else TvActionRow(query.ifBlank { L("Enter a name or number", "Saisir un nom ou numéro").text() },
                        L("Edit search", "Modifier la recherche").text(), requester = edit, modifier = Modifier.testTag("tv-search-edit")) { keyboard = true }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvActionRow(L("Channels", "Chaînes").text(), selected = !programs, modifier = Modifier.weight(1f)) { programs = false; page = 0; keyboard = false }
                        TvActionRow(L("Programmes", "Programmes").text(), selected = programs, modifier = Modifier.weight(1f)) { programs = true; page = 0; keyboard = false }
                    }
                }
                Column(Modifier.weight(1f)) {
                    TvSectionLabel(if (loading) L("Searching…", "Recherche…").text() else L("Page ${page + 1}", "Page ${page + 1}").text())
                    if (!loading && result.hits.isEmpty()) TvPanelNotice(when {
                        error -> L("Search could not be loaded. Edit your query to retry.", "La recherche a échoué. Modifiez la saisie pour réessayer.").text()
                        query.isBlank() -> L("Choose Channels and enter a name or number, or explore Programmes.", "Choisissez Chaînes et saisissez un nom ou numéro, ou explorez Programmes.").text()
                        else -> L("No accessible result. Try a shorter name or switch to Programmes.", "Aucun résultat accessible. Essayez un nom plus court ou passez aux Programmes.").text()
                    })
                    LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(result.hits, key = { _, hit -> "${hit.channel.playlistId}:${hit.channel.id}:${hit.program?.start}" }) { i, hit ->
                            PersonalChannelRow(hit.channel, hit.program, if (i == 0) first else remember { FocusRequester() }) { onPick(hit) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (page > 0) TvActionRow(L("Previous", "Précédent").text(), modifier = Modifier.weight(1f)) { page--; keyboard = false }
                        if (result.hasMore) TvActionRow(L("Next", "Suivant").text(), modifier = Modifier.weight(1f).testTag("tv-search-next")) { page++; keyboard = false }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersonalChannelRow(channel: com.sokoul.desktop.data.Channel, program: com.sokoul.desktop.data.Program? = null,
    requester: FocusRequester = remember { FocusRequester() }, onClick: () -> Unit) {
    // The logo sits inside the focus pill: the list keeps the panel's single edge and the whole line lifts on focus.
    TvActionRow(program?.title ?: TvCatalog.shortLabel(channel.name),
        program?.let { "${TvCatalog.shortLabel(channel.name)} · ${clock(it.start)} – ${clock(it.end)}" }
            ?: "${channel.number} · ${TvCatalog.region(channel.name).uppercase()}",
        requester = requester, modifier = Modifier.fillMaxWidth(), onClick = onClick,
        leading = { ChannelLogo(channel.name, channel.logo, Modifier.size(80.dp, 48.dp), guideId = channel.epgKey) })
}
