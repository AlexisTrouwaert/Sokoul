package com.sokoul.pc.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.LiveLogic
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class GroupChannelRequest(val group: String? = null, val destination: String? = null)

@Composable
internal fun GroupChannelPicker(
    deps: AppDeps,
    groups: List<LiveLogic.Group>,
    request: GroupChannelRequest,
    onClose: () -> Unit,
    onSearch: (() -> Unit)?,
    onSaved: (String, Int) -> Unit,
    /** The selection went to the favourites: how many were added. */
    onFavorited: (Int) -> Unit = {},
) {
    val repo = deps.personalTv
    val scope = rememberCoroutineScope()
    val profileId = deps.profiles.current.id
    val playlistId by produceState<Long?>(null, profileId) { value = withContext(Dispatchers.IO) { deps.tv.playlist()?.id } }
    var groupId by remember(request) { mutableStateOf(request.group) }
    val group = groups.firstOrNull { it.id == groupId }
    var chosen by remember(groupId) { mutableStateOf(setOf<String>()) }
    var choosingDestination by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val channels by key(groupId, playlistId) {
        produceState<List<Channel>?>(null) {
            val id = groupId
            if (id != null) value = withContext(Dispatchers.IO) {
                val collection = TvPersonalization.collectionId(id)
                if (collection == null) deps.tv.channels(id) else repo.channels(collection)
            }
        }
    }
    val revision by repo.revision.collectAsState()
    val collections by produceState(emptyList<TvCollection>(), revision, playlistId) {
        value = withContext(Dispatchers.IO) { repo.state().collections }
    }
    val destination = collections.firstOrNull { it.id == request.destination }
    val first = remember { FocusRequester() }
    LaunchedEffect(groupId, channels != null, choosingDestination, naming) {
        if (!naming) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    fun back() {
        if (saving) return
        error = null
        when {
            naming -> naming = false
            choosingDestination -> choosingDestination = false
            groupId != null && request.group == null -> groupId = null
            else -> onClose()
        }
    }

    fun save(target: String?) {
        val selectedChannels = channels.orEmpty().filter { it.id in chosen }
        val playlist = playlistId ?: return
        if (saving || selectedChannels.isEmpty()) return
        saving = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (deps.profiles.current.id != profileId || deps.tv.playlist()?.id != playlist) return@withContext null
                if (target == null) {
                    runCatching { repo.create(name, selectedChannels) }.getOrNull()?.let { id ->
                        id to (repo.state().collections.firstOrNull { it.id == id }?.members?.size ?: 0)
                    }
                } else target to repo.addAll(target, selectedChannels)
            }
            saving = false
            if (result == null) {
                error = if (naming) L("Use a unique name from 1 to 40 characters.", "Utilisez un nom unique de 1 à 40 caractères.").text()
                    else L("No channels could be added.", "Aucune chaîne n’a pu être ajoutée.").text()
            } else if (result.second == 0) {
                error = L("No new channels to add.", "Aucune nouvelle chaîne à ajouter.").text()
            } else onSaved(result.first, result.second)
        }
    }

    TvPanel(
        when {
            naming -> L("New collection", "Nouvelle collection").text()
            choosingDestination -> L("Add to a collection", "Ajouter à une collection").text()
            group != null -> columnGroupName(group)
            else -> L("Add channels", "Ajouter des chaînes").text()
        },
        when {
            group == null -> destination?.name
            else -> L("${chosen.size} selected", "${chosen.size} sélectionnée(s)").text()
        },
        Modifier.testTag("tv-group-picker").onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { back(); true } else false
        },
        width = 1040.dp,
        height = if (Form.tabletPortrait || Form.isPortrait) 1340.dp else 880.dp,
        action = { PanelChip(L("Back", "Retour").text(), Modifier.testTag("tv-group-picker-back")) { back() } },
    ) {
        error?.let { TvPanelNotice(it, error = true) }
        when {
            naming -> TvTextKeyboard(name, { name = it }, { save(null) },
                Modifier.fillMaxWidth(), limit = 40, doneLabel = L("Save collection", "Enregistrer la collection").text())
            choosingDestination -> LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item("new") {
                    TvActionRow(L("Create a collection", "Créer une collection").text(), requester = first, modifier = Modifier.testTag("tv-group-picker-new")) {
                        name = group?.let(::columnGroupName).orEmpty().take(40)
                        naming = true
                    }
                }
                items(collections, key = { it.id }) { collection ->
                    TvActionRow(collection.name, modifier = Modifier.testTag("tv-group-picker-dest-${collection.id}")) { save(collection.id) }
                }
            }
            groupId == null -> LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onSearch != null) item("search") {
                    TvActionRow(L("Find a channel", "Rechercher une chaîne").text(), modifier = Modifier.testTag("tv-group-picker-search"), requester = first, onClick = onSearch)
                }
                val available = groups.filter { TvPersonalization.collectionId(it.id) != request.destination || request.destination == null }
                items(available, key = { it.id }) { item ->
                    TvActionRow(columnGroupName(item), modifier = Modifier.testTag("tv-group-picker-group-${item.id}")) { groupId = item.id }
                }
            }
            channels == null -> TvPanelNotice(L("Loading channels…", "Chargement des chaînes…").text())
            channels.orEmpty().isEmpty() -> TvPanelNotice(L("No channels in this group", "Aucune chaîne dans ce groupe").text())
            else -> {
                val available = channels.orEmpty()
                val allSelected = chosen.size == available.size
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelChip(if (allSelected) L("Clear selection", "Tout désélectionner").text() else L("Select all", "Tout sélectionner").text(),
                        Modifier.testTag("tv-group-picker-all"), requester = first) {
                        chosen = if (allSelected) emptySet() else available.mapTo(HashSet()) { it.id }
                    }
                    // Favourites in one press, without making a collection first.
                    if (chosen.isNotEmpty() && request.destination == null) {
                        PanelChip(L("Add to favourites", "Ajouter aux favoris").text(), Modifier.testTag("tv-group-picker-favorites")) {
                            if (!saving) {
                                saving = true
                                val ids = available.filter { it.id in chosen }.map { it.id }
                                scope.launch {
                                    val added = withContext(Dispatchers.IO) {
                                        val have = deps.tv.favorites()
                                        ids.count { it !in have && deps.tv.setFavorite(it, true) }
                                    }
                                    saving = false
                                    if (added == 0) error = L("Already in your favourites.", "Déjà dans vos favoris.").text() else onFavorited(added)
                                }
                            }
                        }
                    }
                    if (chosen.isNotEmpty()) {
                        PanelChip(if (request.destination == null) L("Add to a collection", "Ajouter à une collection").text() else L("Add selection", "Ajouter la sélection").text(),
                            Modifier.testTag("tv-group-picker-continue")) {
                            if (request.destination == null) choosingDestination = true else save(request.destination)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(available, key = { it.id }) { channel ->
                        val selected = channel.id in chosen
                        TvActionRow(TvCatalog.shortLabel(channel.name), selected = selected,
                            modifier = Modifier.testTag("tv-group-picker-channel-${channel.id}")) {
                            chosen = if (selected) chosen - channel.id else chosen + channel.id
                        }
                    }
                }
            }
        }
    }
}
