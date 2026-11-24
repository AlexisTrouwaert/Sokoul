package com.sokoul.pc.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PersonalCollections(deps: AppDeps, onGuide: (String, Channel?, Program?) -> Unit, onWatch: (Channel) -> Unit, onAddChannels: (String) -> Unit,
    addReturn: Int = 0,
    /** Opened straight on one collection (or on naming a new one) from the group column; Back past it closes. */
    initial: String? = null, startNew: Boolean = false, onExit: (() -> Unit)? = null) {
    val repo = deps.personalTv
    val add = remember { FocusRequester() }
    // The channel search closed (picked or cancelled): give the remote back to the button that opened it.
    LaunchedEffect(addReturn) {
        // requestFocus() reports nothing here, and My TV only becomes focusable again once the search has left.
        if (addReturn > 0) repeat(4) { attempt ->
            if (attempt == 0) androidx.compose.runtime.withFrameNanos { } else kotlinx.coroutines.delay(80)
            runCatching { add.requestFocus() }
        }
    }
    val revision by repo.revision.collectAsState()
    var chosen by rememberSaveable { mutableStateOf(initial) }
    var mode by remember { mutableStateOf(if (startNew) "new" else null) }
    var text by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var member by remember { mutableStateOf<TvPersonalization.Member?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val state by produceState(TvPersonalState(), revision) { value = withContext(Dispatchers.IO) { repo.state() } }
    val collection = state.collections.firstOrNull { it.id == chosen }
    val members by produceState(emptyList<TvPersonalization.Member>(), chosen, revision) {
        value = chosen?.let { withContext(Dispatchers.IO) { repo.members(it) } }.orEmpty()
    }
    fun back(): Boolean = when {
        mode == "new" && chosen == null && onExit != null -> { onExit(); true }
        mode != null -> { mode = null; feedback = null; true }
        member != null -> { member = null; true }
        chosen != null && onExit != null -> { onExit(); true }
        chosen != null -> { chosen = null; true }
        onExit != null -> { onExit(); true }
        else -> false
    }
    fun mutate(action: () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; feedback = null }
            catch (_: IllegalArgumentException) { feedback = L("Use a unique name from 1 to 40 characters.", "Utilisez un nom unique de 1 à 40 caractères.").text() }
        }
    }
    LaunchedEffect(mode, chosen, member) {
        // Opened from the group column, which has just released focus: one early request can be dropped.
        if (mode !in listOf("new", "rename", "add", "reorder")) repeat(5) { attempt ->
            if (attempt == 0) withFrameNanos { } else kotlinx.coroutines.delay(80)
            runCatching { first.requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        e.type == KeyEventType.KeyDown && e.key == Key.Escape && back()
    }.testTag("tv-collections"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        feedback?.let { TvPanelNotice(it, error = true) }
        when {
            mode == "new" || mode == "rename" -> {
                TvSectionLabel(if (mode == "new") L("Name your collection", "Nommez votre collection").text() else L("Rename collection", "Renommer la collection").text())
                TvTextKeyboard(text, { text = it }, {
                    val editing = mode
                    scope.launch {
                        try {
                            val id = withContext(Dispatchers.IO) {
                                if (editing == "new") repo.create(text) else { repo.rename(chosen!!, text); chosen!! }
                            }
                            chosen = id; mode = null; feedback = null
                        } catch (_: IllegalArgumentException) { feedback = L("This name is empty, too long or already used.", "Ce nom est vide, trop long ou déjà utilisé.").text() }
                    }
                }, Modifier.width(680.dp), limit = 40, doneLabel = L("Save collection", "Enregistrer la collection").text())
            }
            mode == "delete" && collection != null -> {
                TvSectionLabel(L("Delete “${collection.name}”?", "Supprimer « ${collection.name} » ?").text())
                TvPanelNotice(L("Only this collection is removed. Channels and favorites are kept.", "Seule cette collection est supprimée. Les chaînes et les favoris sont conservés.").text())
                TvActionRow(L("Keep collection", "Conserver la collection").text(), requester = first) { mode = null }
                TvActionRow(L("Delete collection", "Supprimer la collection").text(), destructive = true, modifier = Modifier.testTag("tv-collection-delete-confirm")) {
                    mutate { repo.delete(collection.id) }; chosen = null; mode = null
                    onExit?.invoke()
                }
            }
            mode == "reorder" && collection != null && member != null -> {
                CollectionReorder(collection, member!!.reference.id, onSave = { ids ->
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { repo.reorder(collection.id, ids) }
                        if (saved) { mode = null; member = null } else feedback = L("The collection changed. Cancel and try again.", "La collection a changé. Annulez et réessayez.").text()
                    }
                }, onCancel = { mode = null })
            }
            member != null && collection != null -> {
                val selected = members.firstOrNull { it.reference.id == member!!.reference.id }
                TvSectionLabel(selected?.channel?.name ?: member!!.reference.label)
                selected?.channel?.let { channel ->
                    TvActionRow(L("Watch channel", "Regarder la chaîne").text(), requester = first) { onWatch(channel) }
                    TvActionRow(L("Open in guide", "Ouvrir dans le guide").text()) { onGuide(TvPersonalization.group(collection.id), channel, null) }
                }
                TvActionRow(L("Reorder channel", "Déplacer la chaîne").text(), requester = if (selected?.channel == null) first else remember { FocusRequester() }) { mode = "reorder" }
                TvActionRow(L("Remove from collection", "Retirer de la collection").text(), destructive = true) {
                    val memberId = member!!.reference.id
                    mutate { repo.remove(collection.id, memberId) }; member = null
                }
                TvActionRow(L("Back to collection", "Retour à la collection").text()) { member = null }
            }
            collection != null && !Form.tabletPortrait && !Form.isPortrait -> {
                // Apple TV detail layout: actions down the left, the collection's channels on the right.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TvActionRow(L("Show in guide", "Afficher dans le guide").text(), requester = first) { onGuide(TvPersonalization.group(collection.id), members.firstOrNull()?.channel, null) }
                        TvActionRow(L("Add channels", "Ajouter des chaînes").text(), requester = add, modifier = Modifier.testTag("tv-collection-add")) { onAddChannels(collection.id) }
                        TvActionRow(L("Use for zapping", "Utiliser pour zapper").text(), selected = state.activeCollectionId == collection.id) { mutate { repo.activate(collection.id) } }
                        TvActionRow(L("Rename", "Renommer").text()) { text = collection.name; mode = "rename" }
                        TvActionRow(L("Delete", "Supprimer").text(), destructive = true) { mode = "delete" }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TvSectionLabel(if (members.size == 1) L("1 channel", "1 chaîne").text() else L("${members.size} channels", "${members.size} chaînes").text())
                        if (members.isEmpty()) TvPanelNotice(L("Add channels from any group. Your provider's groups stay as they are.", "Ajoutez des chaînes de n'importe quel groupe. Les groupes du fournisseur restent inchangés.").text())
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(members, key = { _, it -> it.reference.id }) { _, item ->
                                TvActionRow(item.channel?.let { TvCatalog.shortLabel(it.name) } ?: item.reference.label,
                                    if (item.channel == null) L("Unavailable", "Indisponible").text() else null,
                                    modifier = Modifier.testTag("tv-collection-member-${item.reference.id}")) { member = item }
                            }
                        }
                    }
                }
            }
            collection != null -> {
                TvSectionLabel(collection.name)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvActionRow(L("Guide", "Guide").text(), requester = first, modifier = Modifier.weight(1f)) { onGuide(TvPersonalization.group(collection.id), members.firstOrNull()?.channel, null) }
                    TvActionRow(L("Add channels", "Ajouter des chaînes").text(), requester = add, modifier = Modifier.weight(1.5f).testTag("tv-collection-add")) { onAddChannels(collection.id) }
                    TvActionRow(L("Use for zapping", "Activer pour zapper").text(), selected = state.activeCollectionId == collection.id, modifier = Modifier.weight(2f)) { mutate { repo.activate(collection.id) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvActionRow(L("Rename", "Renommer").text(), modifier = Modifier.weight(1f)) { text = collection.name; mode = "rename" }
                    TvActionRow(L("Delete", "Supprimer").text(), destructive = true, modifier = Modifier.weight(1f)) { mode = "delete" }
                }
                if (members.isEmpty()) TvPanelNotice(L("Add channels from any provider group. Your provider categories remain unchanged.", "Ajoutez des chaînes de plusieurs groupes. Les catégories du fournisseur restent indépendantes.").text())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(members, key = { _, it -> it.reference.id }) { i, item ->
                        TvActionRow("${i + 1} · ${item.channel?.let { TvCatalog.shortLabel(it.name) } ?: item.reference.label}",
                            if (item.channel == null) L("Unavailable · manage or remove this channel", "Indisponible · gérer ou retirer cette chaîne").text() else null,
                            modifier = Modifier.testTag("tv-collection-member-${item.reference.id}")) { member = item }
                    }
                }
            }
            else -> {
                TvSectionLabel(L("Made for you", "À votre image").text())
                TvActionRow(L("Create a collection", "Créer une collection").text(), requester = first, modifier = Modifier.testTag("tv-collection-create")) { text = ""; mode = "new" }
                if (state.collections.isEmpty()) TvPanelNotice(L("Bring your channels together across provider groups: sport, family, an evening selection…", "Rassemblez vos chaînes de plusieurs groupes : sport, famille, sélection du soir…").text())
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(state.collections, key = { _, it -> it.id }) { _, item ->
                        TvActionRow(item.name, L("${item.members.size} channels", "${item.members.size} chaînes").text(), selected = state.activeCollectionId == item.id) { chosen = item.id }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionReorder(collection: TvCollection, selectedId: String, onSave: (List<String>) -> Unit, onCancel: () -> Unit) {
    var order by remember(collection.id) { mutableStateOf(collection.members.map { it.id }) }
    val keys = remember { FocusRequester() }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val selected = order.indexOf(selectedId)
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { keys.requestFocus() } }
    LaunchedEffect(selected) { list.animateScrollToItem(selected.coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().focusRequester(keys).onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent true
        when (event.key) {
            Key.DirectionUp, Key.DirectionDown -> {
                val target = (selected + if (event.key == Key.DirectionUp) -1 else 1).coerceIn(0, order.lastIndex)
                order = order.toMutableList().apply { add(target, removeAt(selected)) }
                true
            }
            Key.Enter, Key.NumPadEnter -> { onSave(order); true }
            Key.Escape -> { onCancel(); true }
            else -> true
        }
    }.focusable().testTag("tv-collection-reorder")) {
        TvSectionLabel(L("Move with ↑ ↓ · OK saves · Back cancels", "Déplacer avec ↑ ↓ · OK enregistre · Retour annule").text())
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            itemsIndexed(order, key = { _, it -> it }) { index, id ->
                val item = collection.members.first { it.id == id }
                TvPanelNotice("${if (id == selectedId) "▸" else " "} ${index + 1} · ${item.label}")
            }
        }
    }
}

@Composable
internal fun TvCollectionPicker(deps: AppDeps, channel: Channel, onClose: () -> Unit) {
    val repo = deps.personalTv
    val revision by repo.revision.collectAsState()
    val state by produceState(TvPersonalState(), revision) { value = withContext(Dispatchers.IO) { repo.state() } }
    val first = remember { FocusRequester() }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(creating) { if (!creating) { withFrameNanos { }; runCatching { first.requestFocus() } } }
    TvPanel(L("Add to collection", "Ajouter à une collection").text(), TvCatalog.shortLabel(channel.name), width = TvPanelSize.Standard.width, height = TvPanelSize.Standard.height,
        modifier = Modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && creating) { creating = false; true } else false }) {
        if (creating) {
            if (error) TvPanelNotice(L("Choose a unique name, 1–40 characters.", "Choisissez un nom unique de 1 à 40 caractères.").text(), error = true)
            TvTextKeyboard(name, { name = it }, {
                scope.launch {
                    try { withContext(Dispatchers.IO) { repo.add(repo.create(name), channel) }; onClose() }
                    catch (_: IllegalArgumentException) { error = true }
                }
            }, limit = 40, doneLabel = L("Create and add", "Créer et ajouter").text())
        } else {
            TvActionRow(L("New collection", "Nouvelle collection").text(), requester = first) { creating = true }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(state.collections, key = { _, it -> it.id }) { _, collection ->
                    TvActionRow(collection.name, selected = collection.members.any { it.channelId == channel.id }) {
                        scope.launch { withContext(Dispatchers.IO) { repo.add(collection.id, channel) }; onClose() }
                    }
                }
            }
        }
    }
}
