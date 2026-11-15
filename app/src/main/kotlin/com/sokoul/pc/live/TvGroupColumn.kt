package com.sokoul.pc.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.data.LiveLogic
import com.sokoul.desktop.data.SpecialGroup
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.tvFocusable
import kotlinx.coroutines.delay

/** One line of the TV group column: a section title, a shortcut to personal content, or a guide group. */
private sealed interface ColumnEntry {
    val key: String
    data class Header(val text: String) : ColumnEntry { override val key get() = "header:$text" }
    data class Shortcut(val id: String, val label: String, val detail: String? = null) : ColumnEntry { override val key get() = "shortcut:$id" }
    data class Group(val group: LiveLogic.Group, val label: String) : ColumnEntry { override val key get() = "group:${group.id}" }
}

/** Width of the column on TV: long provider group names stay on one line at 31 pt. */
internal val TV_COLUMN_WIDTH = 620.dp
/** Inside the focus pill, from its edge to the text; titles and section headers use the same inset. */
private val TEXT_INSET = 24.dp

/**
 * Everything the guide can show, in one sidebar pulled out with Left, drawn like the Apple TV app's sidebar: one text
 * edge, large type, a lifted pill on focus, a check on the group shown. Personal entries first (what's on, reminders,
 * recordings, favorites, collections, recent channels), then the provider's groups. Typing narrows the list; Menu on a
 * collection manages it; Up at the top hands over to the navigation bar; Right or Back return to the guide.
 */
@Composable
internal fun TvGroupColumn(
    groups: List<LiveLogic.Group>,
    selected: String?,
    reminders: Int,
    modifier: Modifier,
    onApply: (String) -> Unit,
    onReminders: () -> Unit,
    onDiscover: () -> Unit,
    onRecordings: () -> Unit,
    onNewCollection: () -> Unit,
    onManageCollection: (String) -> Unit,
    onExitUp: () -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val personal = remember(groups) {
        groups.filter { it.id == SpecialGroup.FAVORITES } +
            groups.filter { TvPersonalization.collectionId(it.id) != null } +
            groups.filter { it.id == TvCatalog.RECENT }
    }
    val provider = remember(groups) { groups.filter { it !in personal } }
    val entries: List<ColumnEntry> = remember(personal, provider, query, reminders) {
        if (query.isNotBlank()) {
            (personal + provider).filter { GuideLogic.matches(columnGroupName(it), query) }.map<LiveLogic.Group, ColumnEntry>(::groupEntry)
        } else buildList<ColumnEntry> {
            add(ColumnEntry.Header(L("My space", "Mon espace").text()))
            add(ColumnEntry.Shortcut("discover", L("What's on", "En ce moment").text()))
            add(ColumnEntry.Shortcut("reminders", L("Reminders", "Rappels").text(), if (reminders > 0) reminders.toString() else null))
            add(ColumnEntry.Shortcut("recordings", L("Recordings", "Enregistrements").text()))
            add(ColumnEntry.Header(L("My channels", "Mes chaînes").text()))
            personal.forEach { add(groupEntry(it)) }
            add(ColumnEntry.Shortcut("new-collection", L("New collection…", "Nouvelle collection…").text()))
            if (provider.isNotEmpty()) add(ColumnEntry.Header(L("Provider", "Fournisseur").text()))
            provider.forEach { add(groupEntry(it)) }
        }
    }
    val focusable = remember(entries) { entries.withIndex().filter { it.value !is ColumnEntry.Header }.map { it.index } }
    val startAt = remember(entries) {
        entries.indexOfFirst { it is ColumnEntry.Group && it.group.id == selected }.takeIf { it >= 0 } ?: focusable.firstOrNull() ?: 0
    }
    var focusedIndex by remember { mutableIntStateOf(startAt) }
    val state = rememberLazyListState()
    val first = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    LaunchedEffect(entries) {
        // Personal entries sit near the top: show the column from its start so "My space" stays in view.
        state.scrollToItem(if (startAt < 12) 0 else startAt - 1)
        delay(16)
        runCatching { if (focusable.isEmpty()) emptyFocus.requestFocus() else first.requestFocus() }
    }
    Column(
        // The pill starts TEXT_INSET before the page margin so every label sits exactly on the guide's text edge.
        modifier.background(FormulerGuideStyle.sheet).padding(start = Metrics.PageMargin - TEXT_INSET, end = 32.dp, top = 64.dp, bottom = 40.dp)
            .testTag("tv-drawer")
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val char = e.utf16CodePoint.toChar()
                when {
                    e.key == Key.Escape && query.isNotEmpty() -> { query = ""; true }
                    e.key == Key.Escape || e.key == Key.DirectionRight -> { onClose(); true }
                    // At the top the column hands the remote to the navigation bar instead of trapping it.
                    e.key == Key.DirectionUp && focusable.isNotEmpty() && focusedIndex == focusable.first() -> { onExitUp(); true }
                    e.key == Key.Menu -> {
                        val collection = (entries.getOrNull(focusedIndex) as? ColumnEntry.Group)?.group?.id?.let(TvPersonalization::collectionId)
                        if (collection != null) { onManageCollection(collection); true } else false
                    }
                    e.key == Key.Backspace && query.isNotEmpty() -> { query = query.dropLast(1); true }
                    char.isLetterOrDigit() || (char == ' ' && query.isNotEmpty()) -> { query += char; true }
                    else -> false
                }
            }
            .focusRequester(emptyFocus).focusable(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The title shares the options' text edge; a typed filter replaces it so the search is visible.
        Label(query.ifEmpty { L("Groups", "Groupes").text() }, com.sokoul.pc.theme.PanelType.Title.copy(fontSize = 48.sp), Palette.OnBackground,
            Modifier.padding(start = TEXT_INSET, bottom = 12.dp).testTag("tv-drawer-title"), maxLines = 1)
        if (focusable.isEmpty()) Label(L("No matching group. Back clears the search.", "Aucun groupe trouvé. Retour efface la recherche.").text(),
            Type.Body.copy(fontSize = 25.sp), FormulerGuideStyle.secondary, Modifier.padding(start = TEXT_INSET), maxLines = 2)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { i, entry ->
                when (entry) {
                    is ColumnEntry.Header -> Label(entry.text, Type.Body.copy(fontSize = 25.sp, fontWeight = FontWeight.SemiBold), FormulerGuideStyle.muted,
                        Modifier.padding(start = TEXT_INSET, top = if (i == 0) 0.dp else 28.dp, bottom = 8.dp), maxLines = 1)
                    is ColumnEntry.Shortcut -> ColumnRow(entry.label, entry.detail, current = false, collection = false,
                        requester = if (i == startAt) first else null, tag = "tv-column-${entry.id}", onFocus = { focusedIndex = i }) {
                        when (entry.id) {
                            "discover" -> onDiscover()
                            "reminders" -> onReminders()
                            "recordings" -> onRecordings()
                            else -> onNewCollection()
                        }
                    }
                    is ColumnEntry.Group -> ColumnRow(entry.label, null, current = entry.group.id == selected,
                        collection = TvPersonalization.collectionId(entry.group.id) != null,
                        requester = if (i == startAt) first else null, tag = "tv-group-${entry.group.id}", onFocus = { focusedIndex = i }) {
                        onApply(entry.group.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnRow(label: String, detail: String?, current: Boolean, collection: Boolean, requester: FocusRequester?, tag: String,
    onFocus: () -> Unit, onClick: () -> Unit) {
    var has by remember { mutableStateOf(false) }
    val own = remember { FocusRequester() }
    val fill by animateColorAsState(if (has) Palette.FocusFill else Color.Transparent, tween(FormulerGuideStyle.focusMs))
    val scale by animateFloatAsState(if (has) 1.03f else 1f, tween(FormulerGuideStyle.focusMs), label = "column-focus")
    val ink = if (has) Palette.OnFocus else Palette.OnBackground
    val quiet = if (has) Palette.OnFocus.copy(alpha = 0.7f) else FormulerGuideStyle.secondary
    Row(
        Modifier.fillMaxWidth().height(72.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(com.sokoul.pc.theme.Corners.Row)).background(fill).testTag(tag)
            .tvFocusable(onFocusChanged = { has = it; if (it) onFocus() }, onClick = onClick, requester = requester ?: own)
            .padding(horizontal = TEXT_INSET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Label(label, Type.Meta.copy(fontSize = 31.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium), ink, Modifier.weight(1f), maxLines = 1)
        when {
            // Managing a collection is one key away; said only where it applies, on the focused line.
            collection && has -> Label(L("Menu · edit", "Menu · gérer").text(), Type.Body.copy(fontSize = 25.sp), quiet, maxLines = 1)
            detail != null -> Label(detail, Type.Body.copy(fontSize = 25.sp, fontWeight = FontWeight.SemiBold), quiet, maxLines = 1)
        }
        if (current) Icon(Icons.Filled.Check, null, Modifier.size(28.dp), tint = ink)
    }
}

private fun groupEntry(g: LiveLogic.Group): ColumnEntry.Group = ColumnEntry.Group(g, columnGroupName(g))

/** Provider groups often come in capitals ("FRANCE GENERALE"): shown in title case so the column reads calmly. */
internal fun columnGroupName(g: LiveLogic.Group): String = when (g.id) {
    SpecialGroup.FAVORITES -> L("Favorites", "Favoris").text()
    SpecialGroup.ALL -> L("All channels", "Toutes les chaînes").text()
    TvCatalog.RECENT -> L("Recent channels", "Chaînes récentes").text()
    else -> TvCatalog.label(g.name).let { name ->
        if (name.any { it.isLowerCase() }) name
        else name.lowercase().split(' ').joinToString(" ") { word ->
            if (word.length <= 3 && word.all { it.isLetter() } && word in setOf("tv", "hd", "uhd", "fhd", "4k")) word.uppercase()
            else word.replaceFirstChar { it.titlecase() }
        }
    }
}
