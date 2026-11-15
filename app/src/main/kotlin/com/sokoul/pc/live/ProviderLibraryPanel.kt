package com.sokoul.pc.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.*
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The provider catalogue stays independent from TMDB matching and loads only one local page at a time. */
@Composable
fun ProviderLibraryPanel(
    deps: AppDeps,
    kind: ProviderKind,
    modifier: Modifier = Modifier,
    onPlay: (key: String, stream: StreamRequest, title: String, poster: String?, mayOpen: () -> Boolean) -> Unit,
    onDownload: (StreamRequest, String, String) -> Unit,
    onClose: () -> Unit,
) {
    val repository = remember(deps.db) { ProviderCatalog(deps.db) }
    val scope = rememberCoroutineScope()
    var categories by remember(kind) { mutableStateOf(emptyList<ProviderCategory>()) }
    var category by remember(kind) { mutableStateOf<String?>(null) }
    var query by remember(kind) { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var offset by remember(kind) { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var page by remember(kind) { mutableStateOf(ProviderPage(emptyList(), 0, 0, false)) }
    var selected by remember(kind) { mutableStateOf<ProviderItem?>(null) }
    var episodes by remember { mutableStateOf(emptyList<ProviderEpisode>()) }
    var episodesOpen by remember { mutableStateOf(false) }
    var selectedEpisode by remember { mutableStateOf<ProviderEpisode?>(null) }
    var lastEpisodeId by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var categoryError by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var playlistId by remember { mutableLongStateOf(0) }
    var lastItemId by remember(kind) { mutableStateOf<String?>(null) }
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var categoriesReady by remember(kind) { mutableStateOf(false) }
    val organization = remember(deps.db) { TvOrganization(deps.db, { selectedPlaylist }, { deps.profiles.current.kids }) }
    val organizationKind = if (kind == ProviderKind.Movies) "movie" else "series"
    val allowedCategoryIds = remember(categories, organizationKind, deps.profiles.current.id, revision) {
        organization.allowedCategoryIds(categories.map { it.id to it.title }, organizationKind)
    }
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val detailState = rememberLazyListState()
    val title = if (kind == ProviderKind.Movies) L("Provider movies", "Films du fournisseur").text() else L("Provider series", "Séries du fournisseur").text()

    LaunchedEffect(kind, revision) {
        categoriesReady = false
        categoryError = null
        try {
            val result = withContext(Dispatchers.IO) {
                repository.playlist() to repository.categories(kind, refresh = revision > 0)
            }
            selectedPlaylist = result.first
            playlistId = result.first?.id ?: 0
            categories = result.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { categoryError = L("Categories could not be refreshed. Stored titles remain available.", "Les catégories n’ont pas pu être actualisées. Les titres enregistrés restent disponibles.").text() }
        finally { categoriesReady = true }
    }
    LaunchedEffect(kind, category, query, offset, revision, searching, categoriesReady, playlistId) {
        if (searching) return@LaunchedEffect
        loading = true
        error = null
        val startedAt = System.nanoTime()
        com.sokoul.pc.Platform.guideDiagnostic("provider panel load started kind=${kind.name} category=${category != null} offset=$offset")
        try {
            delay(180)
            if (!categoriesReady || playlistId <= 0) {
                page = ProviderPage(emptyList(), 0, 0, false)
                return@LaunchedEffect
            }
            lastItemId = null
            page = withContext(Dispatchers.IO) { repository.page(playlistId, kind, category, query, offset, 36, exactCount = false) }
            listState.scrollToItem(0)
            com.sokoul.pc.Platform.guideDiagnostic("provider panel load finished kind=${kind.name} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            com.sokoul.pc.Platform.guideDiagnostic("provider panel load failed kind=${kind.name} error=${failure.javaClass.simpleName}")
            error = L("Unable to load this page. Try refreshing.", "Impossible de charger cette page. Réessayez avec Actualiser.").text()
        }
        finally { loading = false }
    }
    LaunchedEffect(deps.profiles.current.id) { selected = null; episodesOpen = false; query = ""; offset = 0 }
    LaunchedEffect(selected?.id) { detailState.scrollToItem(0) }
    LaunchedEffect(selectedEpisode?.id, episodesOpen) {
        if (episodesOpen && selectedEpisode == null && lastEpisodeId != null) {
            val index = episodes.filter { it.season == season }.indexOfFirst { it.id == lastEpisodeId }
            if (index >= 0) detailState.scrollToItem(2 + episodes.map { it.season }.distinct().size + index)
        }
    }
    LaunchedEffect(selected?.id, selectedEpisode?.id, episodesOpen, searching, loading, categoriesReady) {
        if (!searching && !loading) { withFrameNanos { }; withFrameNanos { }; runCatching { first.requestFocus() } }
    }

    fun mayOpen(item: ProviderItem): Boolean {
        if (deps.profiles.current.kids && TvOrganization.adultName(item.title)) return false
        val entryCategory = categories.firstOrNull { it.id == item.categoryId }
        if (deps.profiles.current.kids && (entryCategory == null || entryCategory.title == entryCategory.id)) return false
        return item.categoryId in allowedCategoryIds
    }

    fun mayOpenNow(item: ProviderItem): Boolean {
        val entryCategory = categories.firstOrNull { it.id == item.categoryId }
        return mayOpen(item) && organization.canAccessCategory(item.categoryId.orEmpty(), entryCategory?.title, organizationKind)
    }
    val visibleItems = page.items.filter(::mayOpen)

    fun launchStream(item: ProviderItem, episode: ProviderEpisode? = null, download: Boolean = false) {
        if (busy) return
        if (!mayOpenNow(item)) { error = L("This category is restricted for this profile.", "Cette catégorie est restreinte pour ce profil.").text(); return }
        busy = true
        error = null
        scope.launch {
            try {
                val request = withContext(Dispatchers.IO) { if (episode == null) repository.movieStream(item) else repository.episodeStream(episode) }
                if (request == null) error = L("This provider entry has no playable stream.", "Cette entrée ne fournit pas de flux lisible.").text()
                else {
                    val entryId = episode?.id ?: item.id
                    val key = "provider:$playlistId:${kind.name}:$entryId"
                    val label = episode?.let { "${item.title} · S${it.season} E${it.episode} · ${it.title}" } ?: item.title
                    if (!mayOpenNow(item)) error = L("This category is restricted for this profile.", "Cette catégorie est restreinte pour ce profil.").text()
                    else if (download) {
                        onDownload(request, key, label)
                        feedback = L("Added to downloads. Open Recordings to follow progress.", "Ajouté aux téléchargements. Ouvrez Enregistrements pour suivre la progression.").text()
                    } else onPlay(key, request, label, episode?.poster ?: item.poster) { mayOpenNow(item) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = L("The provider stream could not be opened. Try again.", "Le flux du fournisseur n’a pas pu être ouvert. Réessayez.").text() }
            finally { busy = false }
        }
    }

    TvPanel(title, subtitle = L("Your IPTV catalogue, with its own categories", "Votre catalogue IPTV et ses catégories").text(),
        modifier = modifier.testTag("provider-library").onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                when {
                    searching -> searching = false
                    episodesOpen && selectedEpisode != null -> selectedEpisode = null
                    episodesOpen -> episodesOpen = false
                    selected != null -> selected = null
                    else -> onClose()
                }
                true
            } else if (event.type == KeyEventType.KeyDown && searching && event.key == Key.Backspace) {
                query = query.dropLast(1); offset = 0; true
            } else if (event.type == KeyEventType.KeyDown && searching && event.utf16CodePoint.toChar().let { it.isLetterOrDigit() || it == ' ' }) {
                query += event.utf16CodePoint.toChar(); offset = 0; true
            } else false
        }, width = 1120.dp, height = 820.dp,
        footer = if (selected != null || searching) L("Back to the catalogue", "Retour au catalogue").text() else L("Choose a category, then a title · Back to close", "Choisissez une catégorie, puis un titre · Retour pour fermer").text(),
    ) {
        if (searching) {
            ProviderSearchKeyboard(query, onChange = { query = it; offset = 0 }, onDone = { searching = false })
        } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { TvSectionLabel(L("Categories", "Catégories").text()) }
                item { TvActionRow(L("All titles", "Tous les titres").text(), selected = category == null, modifier = Modifier.testTag("provider-category-all")) { category = null; offset = 0; selected = null; episodesOpen = false } }
                itemsIndexed(categories.filter { it.id in allowedCategoryIds }, key = { _, it -> it.id }) { _, entry ->
                    TvActionRow(entry.title, L("${entry.count} entries", "${entry.count} entrées").text(), selected = category == entry.id,
                        modifier = Modifier.testTag("provider-category-${entry.id}")) {
                        category = entry.id; offset = 0; selected = null; episodesOpen = false
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvActionRow(if (query.isBlank()) L("Search", "Rechercher").text() else "“$query”", requester = if (visibleItems.isEmpty() && selected == null) first else remember { FocusRequester() }, modifier = Modifier.weight(1f).testTag("provider-search")) { selected = null; selectedEpisode = null; episodesOpen = false; searching = true }
                    TvActionRow(L("Refresh", "Actualiser").text(), modifier = Modifier.weight(1f).testTag("provider-refresh")) { revision++ }
                    if (query.isNotEmpty()) TvActionRow(L("Clear", "Effacer").text(), modifier = Modifier.weight(1f)) { query = ""; offset = 0 }
                }
                categoryError?.let { TvPanelNotice(it, error = true) }
                error?.let { TvPanelNotice(it, error = true) }
                feedback?.let { TvPanelNotice(it) }
                if (busy) TvPanelNotice(L("Opening…", "Ouverture…").text())
                val entry = selected?.takeIf(::mayOpen)
                if (entry != null) {
                    LazyColumn(Modifier.weight(1f), state = detailState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Art(entry.poster, Modifier.width(112.dp).height(168.dp).clip(RoundedCornerShape(16.dp)))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Label(entry.title, Type.RowTitle, maxLines = 3)
                                    Label(listOfNotNull(entry.year?.toString(), entry.rating?.let { "★ $it" }).joinToString(" · "), Type.Body, Palette.BarText, maxLines = 1)
                                    entry.description?.takeIf { it.isNotBlank() }?.let { Label(it, Type.Body, Palette.BarText, maxLines = 4) }
                                }
                            }
                        }
                        if (kind == ProviderKind.Movies) {
                            item { TvActionRow(L("Play", "Lecture").text(), requester = first, modifier = Modifier.testTag("provider-play")) { launchStream(entry) } }
                            item { TvActionRow(L("Save offline", "Enregistrer hors ligne").text(), L("Uses the provider connection and recording storage", "Utilise la connexion du fournisseur et le stockage des enregistrements").text(), modifier = Modifier.testTag("provider-download")) { launchStream(entry, download = true) } }
                        } else if (!episodesOpen) {
                            item {
                                TvActionRow(L("Seasons and episodes", "Saisons et épisodes").text(), requester = first, modifier = Modifier.testTag("provider-episodes")) {
                                    if (!busy) {
                                        busy = true
                                        scope.launch {
                                            try { episodes = repository.episodes(entry); season = episodes.firstOrNull()?.season; selectedEpisode = null; lastEpisodeId = null; episodesOpen = true }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { error = L("Episodes could not be loaded.", "Les épisodes n’ont pas pu être chargés.").text() }
                                            finally { busy = false }
                                        }
                                    }
                                }
                            }
                        } else if (selectedEpisode != null) {
                            val chosen = requireNotNull(selectedEpisode)
                            item { TvSectionLabel("S${chosen.season} · E${chosen.episode} · ${chosen.title}") }
                            item { TvActionRow(L("Play episode", "Lire l’épisode").text(), requester = first, modifier = Modifier.testTag("provider-episode-play")) { launchStream(entry, chosen) } }
                            item { TvActionRow(L("Save episode offline", "Enregistrer l’épisode hors ligne").text(), modifier = Modifier.testTag("provider-episode-download")) { launchStream(entry, chosen, download = true) } }
                        } else {
                            val seasons = episodes.map { it.season }.distinct()
                            item { TvSectionLabel(L("Choose a season", "Choisir une saison").text()) }
                            itemsIndexed(seasons, key = { _, it -> "season-$it" }) { index, value ->
                                TvActionRow(L("Season $value", "Saison $value").text(), selected = season == value, requester = if (index == 0 && lastEpisodeId == null) first else remember { FocusRequester() }) { season = value; lastEpisodeId = null }
                            }
                            if (episodes.isEmpty()) {
                                item { TvPanelNotice(L("No episodes were returned by this provider.", "Le fournisseur n’a renvoyé aucun épisode.").text()) }
                                item { TvActionRow(L("Back to series", "Retour à la série").text(), requester = first, modifier = Modifier.testTag("provider-empty-episodes-back")) { episodesOpen = false } }
                            }
                            itemsIndexed(episodes.filter { it.season == season }, key = { _, it -> "episode-${it.id}" }) { _, episode ->
                                TvActionRow("E${episode.episode} · ${episode.title}", episode.durationMs?.let { "${it / 60_000} min" }, requester = if (episode.id == lastEpisodeId) first else remember { FocusRequester() }, modifier = Modifier.testTag("provider-episode-${episode.id}")) { lastEpisodeId = episode.id; selectedEpisode = episode }
                            }
                        }
                    }
                } else if (loading || (deps.profiles.current.kids && !categoriesReady)) {
                    TvPanelNotice(L("Loading titles…", "Chargement des titres…").text())
                } else {
                    TvSectionLabel(if (page.total < 0) L("Showing ${page.items.size}+ titles", "${page.items.size}+ titres affichés").text() else L("${page.total} entries", "${page.total} entrées").text())
                    LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (visibleItems.isEmpty()) item { TvPanelNotice(L("No accessible titles match. Try another category or page.", "Aucun titre accessible trouvé. Essayez une autre catégorie ou une autre page.").text()) }
                        itemsIndexed(visibleItems, key = { _, it -> it.id }) { index, entry ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Art(entry.poster, Modifier.padding(top = 8.dp).width(56.dp).height(84.dp).clip(RoundedCornerShape(10.dp)))
                                TvActionRow(entry.title, listOfNotNull(entry.year?.toString(), entry.rating?.let { "★ $it" }).joinToString(" · "),
                                    requester = if (entry.id == lastItemId || (lastItemId == null && index == 0)) first else remember { FocusRequester() }, modifier = Modifier.weight(1f).testTag("provider-item-${entry.id}")) {
                                    lastItemId = entry.id; selected = entry; selectedEpisode = null; episodes = emptyList(); episodesOpen = false; feedback = null; error = null
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (offset > 0) TvActionRow(L("Previous page", "Page précédente").text(), modifier = Modifier.weight(1f)) { offset = (offset - 36).coerceAtLeast(0) }
                        else Label(L("First page", "Première page").text(), Type.Body, Palette.BarText, Modifier.weight(1f).padding(16.dp), maxLines = 1)
                        if (page.hasMore) TvActionRow(L("Next page", "Page suivante").text(), modifier = Modifier.weight(1f).testTag("provider-next-page")) { offset += 36 }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderSearchKeyboard(query: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    if (com.sokoul.pc.Platform.IS_TV) {
        TvTextKeyboard(query, onChange, onDone)
        return
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvSectionLabel(L("Search titles", "Rechercher des titres").text())
        Label(query.ifBlank { L("Enter a title", "Saisissez un titre").text() }, Type.RowTitle, modifier = Modifier.padding(16.dp), maxLines = 2)
        val keys = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toList()
        keys.chunked(9).forEachIndexed { row, letters ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                letters.forEachIndexed { index, letter -> TvActionRow(letter.toString(), requester = if (row == 0 && index == 0) first else remember { FocusRequester() }, modifier = Modifier.weight(1f)) { onChange(query + letter.lowercaseChar()) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvActionRow(L("Space", "Espace").text(), modifier = Modifier.weight(1f)) { onChange(query + " ") }
            TvActionRow(L("Delete", "Supprimer").text(), modifier = Modifier.weight(1f)) { onChange(query.dropLast(1)) }
            TvActionRow(L("Show results", "Afficher les résultats").text(), modifier = Modifier.weight(2f).testTag("provider-search-done")) { onDone() }
        }
    }
}
