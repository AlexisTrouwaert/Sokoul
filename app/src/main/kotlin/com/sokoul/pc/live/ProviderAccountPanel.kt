package com.sokoul.pc.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.*
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class ProviderHealth(val channels: Int = 0, val programmes: Int = 0, val movies: Int = 0, val series: Int = 0, val guideUpdatedAt: Long = 0)

/** Safe provider metadata and explicit source preferences, without exposing server/account credentials. */
@Composable
fun ProviderAccountPanel(deps: AppDeps, modifier: Modifier = Modifier, onChanged: () -> Unit, onClose: () -> Unit) {
    val repository = remember(deps.db) { ProviderCatalog(deps.db) }
    val synchronizer = remember(deps.db) { DesktopSync(deps.db) }
    val scope = rememberCoroutineScope()
    var playlist by remember { mutableStateOf<Playlist?>(null) }
    var snapshot by remember { mutableStateOf<ProviderAccountSnapshot?>(null) }
    var health by remember { mutableStateOf(ProviderHealth()) }
    var busy by remember { mutableStateOf(false) }
    var initialLoading by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf("auto") }
    var offset by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val organization = remember(deps.db) { TvOrganization(deps.db, { playlist }, { deps.profiles.current.kids }) }
    val canManage = playlist != null && organization.canManage()
    val date = remember { DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()) }

    fun readHealth(p: Playlist?): ProviderHealth = p?.let {
        ProviderHealth(deps.db.channelCount(it.id), deps.db.programCount(it.id), deps.db.movieCount(it.id), deps.db.seriesCount(it.id),
            deps.db.setting("provider_epg_synced_${it.id}")?.toLongOrNull() ?: 0)
    } ?: ProviderHealth()

    LaunchedEffect(Unit) {
        try {
            val data = withContext(Dispatchers.IO) {
                val p = repository.playlist()
                Triple(p, repository.account(), readHealth(p))
            }
            playlist = data.first
            snapshot = data.second
            health = data.third
            format = repository.outputFormat()
            offset = repository.guideOffsetMinutes()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { feedback = L("Provider information is unavailable. You can retry below.", "Les informations du fournisseur sont indisponibles. Vous pouvez réessayer ci-dessous.").text(); failed = true }
        finally { initialLoading = false }
    }
    LaunchedEffect(initialLoading) {
        if (!initialLoading) { withFrameNanos { }; withFrameNanos { }; runCatching { first.requestFocus() } }
    }

    fun changeSource(action: suspend () -> Unit) {
        if (busy || playlist == null || !organization.canManage()) return
        busy = true; failed = false; feedback = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                format = repository.outputFormat()
                offset = repository.guideOffsetMinutes()
                health = withContext(Dispatchers.IO) { readHealth(playlist) }
                onChanged()
                feedback = L("Source preferences updated.", "Préférences de la source mises à jour.").text()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; feedback = L("The change could not be saved. Try again.", "La modification n’a pas pu être enregistrée. Réessayez.").text() }
            finally { busy = false }
        }
    }

    TvPanel(L("Subscription and guide", "Abonnement et guide").text(), L("Source status and playback preferences", "État de la source et préférences de lecture").text(),
        modifier = modifier.testTag("provider-account").onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { onClose(); true } else false
        }, width = TvPanelSize.Standard.width, height = TvPanelSize.Standard.height,
    ) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { TvSectionLabel(L("Subscription", "Abonnement").text()) }
            if (initialLoading) item { TvPanelNotice(L("Loading provider information…", "Chargement des informations du fournisseur…").text()) }
            item {
                val account = snapshot
                val state = when {
                    account == null && playlist?.type == PlaylistType.M3U -> L("M3U source", "Source M3U").text()
                    account == null || account.checkedAt == 0L -> L("Not available", "Indisponible").text()
                    account.active -> L("Active", "Actif").text()
                    else -> account.status ?: L("Inactive", "Inactif").text()
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Label(state, Type.RowTitle, maxLines = 1)
                    account?.expiresAt?.let { Label(L("Expires ${date.format(Instant.ofEpochMilli(it))}", "Échéance : ${date.format(Instant.ofEpochMilli(it))}").text(), Type.Body, Palette.BarText, maxLines = 2) }
                    // Said the way a viewer counts: screens watching at once, not "connection(s)".
                    account?.maxConnections?.let { n ->
                        Label((if (n == 1) L("1 screen at a time", "1 écran à la fois") else L("$n screens at a time", "$n écrans à la fois")).text(), Type.Body, Palette.BarText, maxLines = 2)
                    }
                    account?.activeConnections?.let { n ->
                        Label((if (n == 0) L("No screen watching right now", "Aucun écran en cours de lecture")
                            else if (n == 1) L("1 screen watching right now", "1 écran en cours de lecture")
                            else L("$n screens watching right now", "$n écrans en cours de lecture")).text(), Type.Body, Palette.BarText, maxLines = 2)
                    }
                    account?.timezone?.let { Label(L("Guide times are given in $it", "Horaires du guide fournis en $it").text(), Type.Body, Palette.BarText, maxLines = 1) }
                    if (account?.formats?.isNotEmpty() == true) {
                        val names = account.formats.map { if (it.equals("m3u8", true)) "HLS" else it.uppercase() }.distinct().joinToString(", ")
                        Label(L("Stream formats: $names", "Formats de flux : $names").text(), Type.Body, Palette.BarText, maxLines = 2)
                    }
                }
            }
            if (snapshot?.stale == true) item { TvPanelNotice(L("Showing saved information; the provider could not be reached. Its connection counter may be delayed.", "Informations enregistrées : le fournisseur n’a pas pu être joint. Son compteur de connexions peut être retardé.").text()) }
            item {
                TvActionRow(L("Refresh subscription", "Actualiser l’abonnement").text(), requester = first, modifier = Modifier.testTag("provider-account-refresh")) {
                    if (!busy) {
                        busy = true; feedback = null; failed = false
                        scope.launch {
                            try { snapshot = repository.account(refresh = true); health = withContext(Dispatchers.IO) { readHealth(playlist) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { feedback = L("Provider refresh failed. Try again.", "L’actualisation du fournisseur a échoué. Réessayez.").text(); failed = true }
                            finally { busy = false }
                        }
                    }
                }
            }
            item { TvSectionLabel(L("Source health", "État de la source").text()) }
            item {
                // Grouped digits: "28 402 chaînes" reads at a glance from the sofa, "28402" does not.
                val n = java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault())
                TvPanelNotice(L("${n.format(health.channels)} channels · ${n.format(health.programmes)} programmes in the guide\n${n.format(health.movies)} films · ${n.format(health.series)} series",
                    "${n.format(health.channels)} chaînes · ${n.format(health.programmes)} programmes au guide\n${n.format(health.movies)} films · ${n.format(health.series)} séries").text())
            }
            if (health.guideUpdatedAt > 0) item { TvPanelNotice(L("Guide updated ${date.format(Instant.ofEpochMilli(health.guideUpdatedAt))}", "Guide actualisé le ${date.format(Instant.ofEpochMilli(health.guideUpdatedAt))}").text()) }
            if (busy) item { TvPanelNotice(L("Updating…", "Mise à jour…").text()) }
            feedback?.let { item { TvPanelNotice(it, error = failed) } }
            if (!canManage) {
                item { TvPanelNotice(if (deps.profiles.current.kids) L("An adult profile manages source settings.", "Un profil adulte gère les réglages de la source.").text() else L("Unlock IPTV controls in Organization to change source settings.", "Déverrouillez les contrôles IPTV dans Organisation pour modifier la source.").text()) }
            } else {
                item { TvSectionLabel(L("Live stream format", "Format du direct").text()) }
                listOf("auto", "ts", "m3u8").forEach { value ->
                    item {
                        val label = when (value) { "auto" -> L("Automatic", "Automatique").text(); "m3u8" -> "HLS"; else -> "MPEG-TS" }
                        TvActionRow(label, selected = format == value, modifier = Modifier.testTag("provider-format-$value")) { changeSource { repository.setOutputFormat(value) } }
                    }
                }
                item { TvPanelNotice(L("Applied the next time a channel is opened. Local live recording currently requires MPEG-TS.", "Appliqué à la prochaine ouverture d’une chaîne. L’enregistrement local du direct nécessite actuellement MPEG-TS.").text()) }
                item { TvSectionLabel(L("Guide adjustment", "Ajustement du guide").text()) }
                item { TvPanelNotice(L("Current adjustment: ${if (offset > 0) "+" else ""}$offset min. A change shifts the stored guide immediately.", "Décalage actuel : ${if (offset > 0) "+" else ""}$offset min. La modification décale immédiatement le guide enregistré.").text()) }
                item { TvActionRow(L("30 minutes earlier", "30 minutes plus tôt").text(), modifier = Modifier.testTag("provider-offset-minus")) { changeSource { repository.setGuideOffsetMinutes((offset - 30).coerceAtLeast(-720)) } } }
                item { TvActionRow(L("30 minutes later", "30 minutes plus tard").text(), modifier = Modifier.testTag("provider-offset-plus")) { changeSource { repository.setGuideOffsetMinutes((offset + 30).coerceAtMost(720)) } } }
                item { TvActionRow(L("Reset guide adjustment", "Réinitialiser le décalage").text(), modifier = Modifier.testTag("provider-offset-reset")) { changeSource { repository.setGuideOffsetMinutes(0) } } }
                item {
                    TvActionRow(L("Refresh TV guide", "Actualiser le guide TV").text(), modifier = Modifier.testTag("provider-guide-refresh")) {
                        val p = playlist
                        if (!busy && p != null && organization.canManage()) {
                            busy = true; feedback = null; failed = false
                            scope.launch {
                                try {
                                    val count = synchronizer.syncGuide(p)
                                    health = withContext(Dispatchers.IO) { readHealth(p) }
                                    onChanged()
                                    failed = count == 0
                                    feedback = if (count > 0) L("$count programmes imported.", "$count programmes importés.").text()
                                        else L("No new programmes were received. The stored guide was preserved.", "Aucun nouveau programme reçu. Le guide enregistré a été conservé.").text()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failed = true; feedback = L("Guide refresh failed. The stored guide is preserved.", "L’actualisation du guide a échoué. Le guide enregistré est conservé.").text() }
                                finally { busy = false }
                            }
                        }
                    }
                }
            }
        }
    }
}
