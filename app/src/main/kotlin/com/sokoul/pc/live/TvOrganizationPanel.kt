package com.sokoul.pc.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.Category
import com.sokoul.desktop.data.ProviderCatalog
import com.sokoul.desktop.data.ProviderKind
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos

/** Local IPTV groups, channel order, visibility and PIN protected categories. */
@Composable
fun TvOrganizationPanel(deps: AppDeps, modifier: Modifier = Modifier, onChanged: () -> Unit, onClose: () -> Unit) {
    val organization = deps.tv.organization
    val repository = remember(deps.db) { ProviderCatalog(deps.db) }
    var categoryKind by remember { mutableStateOf("live") }
    var revision by remember { mutableIntStateOf(0) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinSetup by remember { mutableStateOf(!organization.hasPin()) }
    var message by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf(emptyList<com.sokoul.desktop.data.Category>()) }
    var channels by remember { mutableStateOf(emptyList<com.sokoul.desktop.data.Channel>()) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var channelOffset by remember { mutableIntStateOf(0) }
    var channelCount by remember { mutableIntStateOf(0) }
    val channelState = rememberLazyListState()
    val first = remember { FocusRequester() }
    val selected = selectedGroup?.let { id -> groups.firstOrNull { it.id == id } }
    val canManage = organization.canManage()

    LaunchedEffect(revision, selectedGroup, channelOffset, categoryKind, deps.profiles.current.id) {
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                if (categoryKind == "live") organization.allGroups()
                else {
                    val source = repository.playlist()
                    val kind = if (categoryKind == "movie") ProviderKind.Movies else ProviderKind.Series
                    if (source == null) emptyList() else repository.categories(kind).mapIndexed { index, category ->
                        val key = "$categoryKind:${category.id}"
                        Category(source.id, category.id, category.title, index,
                            hidden = organization.isGroupHidden(key), locked = organization.isGroupLocked(key))
                    }
                }
            }
            groups = loaded
            if (selectedGroup != null && loaded.none { it.id == selectedGroup }) selectedGroup = null
            val id = selectedGroup
            val page = withContext(Dispatchers.IO) {
                if (id == null || categoryKind != "live") 0 to emptyList<com.sokoul.desktop.data.Channel>()
                else organization.channelCount(id) to organization.channels(id, channelOffset, TvOrganization.PAGE_SIZE)
            }
            channelCount = page.first
            channels = page.second
            if (channelOffset > 0 && channels.isEmpty()) channelOffset = (channelOffset - TvOrganization.PAGE_SIZE).coerceAtLeast(0)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = L("Channels could not be loaded. Try refreshing.", "Les chaînes n’ont pas pu être chargées. Réessayez avec Actualiser.").text() }
        finally { loading = false }
    }
    LaunchedEffect(canManage, selectedGroup, pinSetup, channelOffset) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    LaunchedEffect(selectedGroup, channelOffset) {
        channelState.scrollToItem(0)
    }

    fun submitPin(setup: Boolean) {
        if (busy) return
        val submitted = pin
        pin = ""
        busy = true
        message = null
        scope.launch {
            try {
                message = withContext(Dispatchers.Default) {
                    if (setup) {
                        if (organization.setPin(submitted)) L("PIN saved.", "Code enregistré.").text()
                        else L("Use 4–8 digits and unlock before changing an existing PIN.", "Utilisez 4 à 8 chiffres et déverrouillez avant de changer le code.").text()
                    } else when (organization.unlockPin(submitted)) {
                        TvOrganization.UnlockResult.Unlocked -> L("Unlocked for 15 minutes.", "Déverrouillé pendant 15 minutes.").text()
                        TvOrganization.UnlockResult.LockedOut -> L("Too many attempts. Try again in one minute.", "Trop de tentatives. Réessayez dans une minute.").text()
                        else -> L("That PIN was not accepted.", "Ce code n’a pas été accepté.").text()
                    }
                }
                if (setup && organization.hasPin()) pinSetup = false
                revision++
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = L("PIN could not be saved or checked. Try again.", "Impossible d’enregistrer ou de vérifier le code. Réessayez.").text() }
            finally { busy = false }
        }
    }

    TvPanel(
        L("Organize your TV", "Organiser la TV").text(),
        L("Private channel order, visibility and parental controls", "Ordre privé, visibilité et contrôle parental").text(),
        modifier = modifier.testTag("tv-organization").onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                when {
                    selectedGroup != null -> { selectedGroup = null; channelOffset = 0 }
                    pinSetup && canManage -> { pinSetup = false; pin = "" }
                    else -> onClose()
                }
                true
            } else false
        },
        width = TvPanelSize.Standard.width, height = TvPanelSize.Standard.height,
    ) {
        message?.let { TvPanelNotice(it) }
        if (busy) TvPanelNotice(L("Checking PIN…", "Vérification du code…").text())
        if (deps.profiles.current.kids) {
            TvPanelNotice(L("These controls are unavailable on a kids profile.", "Ces réglages sont indisponibles sur un profil enfant.").text(), error = true)
            TvActionRow(L("Back to My TV", "Retour à mon espace TV").text(), requester = first, onClick = onClose)
        } else if (organization.hasPin() && !canManage) {
            TvPanelNotice(L("Enter your adult PIN to manage protected groups.", "Saisissez votre code adulte pour gérer les groupes protégés.").text())
            if (pin.isNotEmpty()) TvPanelNotice("•".repeat(pin.length))
            Keypad(onAppend = { if (!busy && pin.length < 8) pin += it }, onDelete = { pin = pin.dropLast(1) }, onSubmit = { submitPin(false) }, first = first)
        } else if (!organization.canManage()) {
            TvPanelNotice(L("These controls are unavailable on a kids profile.", "Ces réglages sont indisponibles sur un profil enfant.").text(), error = true)
        } else if (selected == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(L("Set adult PIN", "Définir un code adulte").text(), { pinSetup = true; pin = "" }, requester = if (!pinSetup) first else remember { FocusRequester() })
                if (organization.hasPin()) PillButton(L("Lock now", "Verrouiller").text(), { organization.lockSession(); revision++ })
            }
            if (pinSetup) {
                TvPanelNotice(L("Choose a private 4–8 digit PIN to protect your TV groups.", "Choisissez un code privé de 4 à 8 chiffres pour protéger vos groupes TV.").text())
                TvPanelNotice(if (pin.isEmpty()) L("PIN", "Code").text() else "•".repeat(pin.length))
                Keypad(onAppend = { if (!busy && pin.length < 8) pin += it }, onDelete = { pin = pin.dropLast(1) }, onSubmit = { submitPin(true) }, first = first)
            }
            if (!pinSetup) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("live" to L("Live TV", "Direct").text(), "movie" to L("Movies", "Films").text(), "series" to L("Series", "Séries").text()).forEach { (kind, label) ->
                        TvActionRow(label, modifier = Modifier.weight(1f), selected = categoryKind == kind) { categoryKind = kind; selectedGroup = null; channelOffset = 0; message = null }
                    }
                }
                TvSectionLabel(L("Groups", "Groupes").text())
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (loading) item { TvPanelNotice(L("Loading groups…", "Chargement des groupes…").text()) }
                    if (!loading && groups.isEmpty()) item { TvPanelNotice(L("No groups are available. Add or refresh your IPTV source in Settings.", "Aucun groupe disponible. Ajoutez ou actualisez votre source IPTV dans les réglages.").text()) }
                    item { TvActionRow(L("Refresh", "Actualiser").text()) { revision++ } }
                    items(groups, key = { it.id }) { group ->
                        TvActionRow(
                            title = group.name,
                            subtitle = listOfNotNull(
                                if (group.hidden) L("Hidden", "Masqué").text() else null,
                                if (group.locked) L("PIN protected", "Protégé par code").text() else null,
                            ).joinToString(" · ").ifBlank { L("Open group controls", "Ouvrir les réglages du groupe").text() },
                            trailing = L("Select", "Choisir").text(),
                            requester = remember(group.id) { FocusRequester() },
                            onClick = { selectedGroup = group.id; channelOffset = 0; message = null },
                        )
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(L("All groups", "Tous les groupes").text(), { selectedGroup = null }, requester = first)
                PillButton(if (selected.hidden) L("Show group", "Afficher le groupe").text() else L("Hide group", "Masquer le groupe").text(), {
                    organization.setCategoryHidden(selected.id, !selected.hidden, categoryKind); revision++; onChanged()
                })
                if (organization.hasPin()) PillButton(if (selected.locked) L("Remove protection", "Retirer le code").text() else L("Protect with PIN", "Protéger par code").text(), {
                    organization.setCategoryLocked(selected.id, !selected.locked, categoryKind); revision++; onChanged()
                })
            }
            if (categoryKind != "live") {
                TvPanelNotice(selected.name)
                TvPanelNotice(L("Hidden categories are removed from browsing and playback. Protected categories require an adult PIN session.", "Les catégories masquées sont retirées du catalogue et de la lecture. Les catégories protégées nécessitent une session adulte déverrouillée.").text())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(L("Move group up", "Monter le groupe").text(), { organization.moveGroup(selected.id, -1); revision++; onChanged() })
                    PillButton(L("Move group down", "Descendre le groupe").text(), { organization.moveGroup(selected.id, 1); revision++; onChanged() })
                }
                TvPanelNotice("${selected.name} · $channelCount ${L("channels", "chaînes").text()}")
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = channelState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (loading) item { TvPanelNotice(L("Loading channels…", "Chargement des chaînes…").text()) }
                    if (!loading && channels.isEmpty()) item { TvPanelNotice(L("No channels in this group.", "Aucune chaîne dans ce groupe.").text()) }
                    items(channels, key = { it.id }) { channel ->
                        val hidden = organization.isChannelHidden(channel.id)
                        val favorite = organization.isFavorite(channel.id)
                        Column(Modifier.fillMaxWidth()) {
                            TvActionRow(
                                title = channel.name,
                                subtitle = if (hidden) L("Hidden from guide", "Masquée du guide").text() else "#${channel.number}",
                                trailing = if (hidden) L("Show", "Afficher").text() else L("Hide", "Masquer").text(),
                                modifier = Modifier.testTag("organize-channel-${channel.id}"),
                                onClick = { organization.setChannelHidden(channel.id, !hidden); revision++; onChanged() },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PillButton(
                                    if (favorite) L("Unfavorite", "Retirer des favoris").text() else L("Favorite", "Ajouter aux favoris").text(),
                                    { organization.toggleFavorite(channel.id); revision++; onChanged() },
                                )
                                PillButton(L("Move up", "Monter").text(), {
                                    organization.moveChannel(channel.id, -1); revision++; onChanged()
                                })
                                PillButton(L("Move down", "Descendre").text(), {
                                    organization.moveChannel(channel.id, 1); revision++; onChanged()
                                })
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (channelOffset > 0) PillButton(L("Previous page", "Page précédente").text(), { channelOffset = (channelOffset - TvOrganization.PAGE_SIZE).coerceAtLeast(0) })
                    if (channelOffset + channels.size < channelCount) PillButton(L("Next page", "Page suivante").text(), { channelOffset += TvOrganization.PAGE_SIZE })
                }
            }
        }
    }
}

@Composable
private fun Keypad(onAppend: (Char) -> Unit, onDelete: () -> Unit, onSubmit: () -> Unit, first: FocusRequester) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..2).forEach { col ->
                    val digit = ('0'.code + (row - 1) * 3 + col + 1).toChar()
                    PillButton(digit.toString(), { onAppend(digit) }, requester = if (row == 1 && col == 0) first else remember(digit) { FocusRequester() })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(L("Clear", "Effacer").text(), onDelete)
            PillButton("0", { onAppend('0') })
            PillButton(L("Continue", "Valider").text(), onSubmit)
        }
    }
}
