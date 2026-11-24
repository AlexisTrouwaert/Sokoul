package com.sokoul.pc.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.desktop.data.SpecialGroup
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.ui.TvActionRow
import com.sokoul.pc.ui.PanelChip
import com.sokoul.pc.ui.TvPanel
import com.sokoul.pc.ui.TvPanelNotice
import com.sokoul.pc.ui.TvPanelSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun zapScopeLabel(scope: TvZapScope): String = when (scope) {
    TvZapScope.All -> L("All channels", "Toutes les chaînes")
    TvZapScope.CurrentGroup -> L("Current group", "Groupe actuel")
    TvZapScope.Favorites -> L("Favorites only", "Favoris uniquement")
    TvZapScope.ActiveCollection -> L("Active collection", "Collection active")
}.text()

private const val DISCOVER_PAGE = 20

/**
 * "What's on": what airs now on the provider's channels in their own order (the big channels first, as in the guide),
 * tonight, later, on favorites and for reminders. Only the guide data actually stored is shown; OK opens it in the guide.
 */
@Composable
internal fun TvDiscoveryPanel(deps: AppDeps, onClose: () -> Unit, onGuide: (String, Channel?, Program?) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("now") }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val data by produceState(TvCatalog.SearchPage(emptyList(), false), tab, page, deps.reminders.items) {
        loading = true
        value = TvCatalog.SearchPage(emptyList(), false)
        value = try { withContext(Dispatchers.IO) {
            val tv = deps.tv
            val now = System.currentTimeMillis()
            when (tab) {
                "now", "favorites" -> {
                    // Channels in the guide's own order: the provider's groups as listed, or the favorites.
                    val pool = if (tab == "favorites") tv.channels(SpecialGroup.FAVORITES) else tv.groups().asSequence()
                        .filter { it.id != SpecialGroup.FAVORITES && it.id != TvCatalog.RECENT && it.id != SpecialGroup.ALL }
                        .flatMap { tv.channels(it.id).asSequence() }
                        .distinctBy { it.nameKey to TvCatalog.region(it.name) }
                        .take(DISCOVER_PAGE * (page + 1) + 1).toList()
                    val slice = pool.drop(page * DISCOVER_PAGE).take(DISCOVER_PAGE)
                    val schedules = tv.schedules(slice, now)
                    TvCatalog.SearchPage(slice.mapNotNull { c ->
                        schedules[c.id].orEmpty().firstOrNull { it.start <= now && it.end > now }?.let { TvCatalog.Hit(c, it) }
                    }, pool.size > (page + 1) * DISCOVER_PAGE)
                }
                "reminders" -> TvCatalog.SearchPage(deps.reminders.upcoming(now).mapNotNull { reminder ->
                    val c = tv.channelById(reminder.channelId)?.takeIf { tv.organization.canAccess(it) } ?: return@mapNotNull null
                    tv.schedules(listOf(c), now)[c.id]?.firstOrNull { it.start == reminder.start }?.let { TvCatalog.Hit(c, it) }
                }, false)
                else -> {
                    val from = if (tab == "tonight") maxOf(now, GuideLogic.tonight(now)) else now
                    val until = if (tab == "tonight") GuideLogic.tonight(now) + 4 * 3_600_000L else now + 72 * 3_600_000L
                    val found = tv.searchTv("", true, page, from, until)
                    found.copy(hits = found.hits.filter { (it.program?.start ?: 0) > now })
                }
            }
        } } finally { loading = false }
    }
    TvPanel(L("On air now", "À l’antenne").text(), L("From your guide, in your channels' order", "D'après votre guide, dans l'ordre de vos chaînes").text(),
        width = TvPanelSize.Wide.width, height = TvPanelSize.Wide.height, modifier = Modifier.testTag("tv-discovery"),
        action = { com.sokoul.pc.ui.PanelChip(L("Back", "Retour").text(), onClick = onClose) }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("now" to L("On now", "En cours"), "tonight" to L("Tonight", "Ce soir"), "upcoming" to L("Later", "À venir"),
                "favorites" to L("Favorites", "Favoris"), "reminders" to L("Reminders", "Rappels")).forEach { (id, label) ->
                PanelChip(label.text(), selected = id == tab, modifier = Modifier.testTag("tv-discovery-$id"),
                    requester = if (id == "now") first else remember { FocusRequester() }) { tab = id; page = 0 }
            }
        }
        if (loading) TvPanelNotice(L("Loading your guide…", "Chargement du guide…").text())
        else if (data.hits.isEmpty()) TvPanelNotice(L("Nothing in your guide for this period. Try another tab.", "Rien dans votre guide pour cette période. Essayez un autre onglet.").text())
        LazyColumn(Modifier.weight(1f).testTag("tv-discovery-results"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(data.hits, key = { _, hit -> "${hit.channel.id}:${hit.program?.start}" }) { _, hit ->
                PersonalChannelRow(hit.channel, hit.program) { onGuide(hit.channel.categoryId, hit.channel, hit.program) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page > 0) TvActionRow(L("Previous", "Précédent").text(), modifier = Modifier.weight(1f)) { page-- }
                    if (data.hasMore) TvActionRow(L("More", "Suite").text(), modifier = Modifier.weight(1f)) { page++ }
                }
            }
        }
    }
}
