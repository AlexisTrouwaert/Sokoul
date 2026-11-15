package com.sokoul.pc.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.RecordingStatus
import com.sokoul.desktop.data.RecordingRepeat
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun TvWorkspacePanel(modifier: Modifier = Modifier, onSelect: (String) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; runCatching { first.requestFocus() } }
    TvPanel(L("My TV", "Mon espace TV").text(), L("Your live service, recordings and personal channel layout", "Votre service TV, vos enregistrements et vos chaînes personnalisées").text(),
        modifier = modifier.testTag("tv-workspace"), width = 760.dp) {
        listOf(
            Triple("movies", L("Provider movies", "Films du fournisseur").text(), L("Browse the IPTV catalogue and resume playback", "Parcourir le catalogue IPTV et reprendre la lecture").text()),
            Triple("series", L("Provider series", "Séries du fournisseur").text(), L("Seasons and episodes from the source", "Saisons et épisodes de la source").text()),
            Triple("recordings", L("Recordings", "Enregistrements").text(), L("Scheduled, active and saved programmes", "Programmes planifiés, en cours et enregistrés").text()),
            Triple("account", L("Subscription and guide", "Abonnement et guide").text(), L("Account status, formats and guide time offset", "État du compte, formats et décalage du guide").text()),
            Triple("organize", L("Organize channels", "Organiser les chaînes").text(), L("Hide, protect and reorder groups", "Masquer, protéger et réordonner les groupes").text()),
        ).forEachIndexed { index, (route, title, subtitle) ->
            TvActionRow(title, subtitle, requester = if (index == 0) first else remember(route) { FocusRequester() }) { onSelect(route) }
        }
        TvPanelNotice(L("Back returns to the guide. Your provider credentials stay in the existing protected source profile.", "Retour ouvre le guide. Les identifiants restent dans le profil de source protégé existant.").text())
    }
}

@Composable
internal fun TvRecordingsPanel(
    deps: AppDeps,
    onPlay: (String, StreamRequest, String, String?, () -> Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val recorder = deps.tv.recorder
    val playlist = deps.tv.playlist()
    val scope = rememberCoroutineScope()
    val version by recorder.version.collectAsState()
    val active by recorder.active.collectAsState()
    val progress by recorder.progress.collectAsState()
    var recordings by remember { mutableStateOf(emptyList<com.sokoul.desktop.data.Recording>()) }
    var storages by remember { mutableStateOf(emptyList<com.sokoul.desktop.data.RecordingStorage>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableLongStateOf(0L) }
    var exporting by remember { mutableLongStateOf(0L) }
    var copying by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val profile = deps.profiles.current
    val accessibleRecordings = remember(recordings, profile.id, profile.kids, version) {
        recordings.filter { canPlayRecording(deps.db, it, profile.kids) }
    }
    fun requireAccess(item: com.sokoul.desktop.data.Recording): Boolean {
        if (canPlayRecording(deps.db, item, deps.profiles.current.kids)) return true
        error = L("This recording is restricted for this profile.", "Cet enregistrement est restreint pour ce profil.").text()
        return false
    }

    fun playRecording(item: com.sokoul.desktop.data.Recording) {
        fun allowed() = canPlayRecording(deps.db, item, deps.profiles.current.kids)
        if (!requireAccess(item)) return
        val request = if (recorder.active.value?.id == item.id) {
            recorder.localStream() ?: run {
                error = L("This recording is not ready to play yet.", "Cet enregistrement n’est pas encore prêt à être lu.").text()
                return
            }
        } else {
            val file = item.path?.let(::File)?.takeIf { it.isFile && it.length() > 0 } ?: run {
                error = L("This recording is no longer available.", "Cet enregistrement n’est plus disponible.").text()
                return
            }
            StreamRequest(file.toURI().toString(), null, null, item.title, live = false)
        }
        onPlay("recording:${item.id}", request, item.title, item.channelLogo, ::allowed)
    }

    LaunchedEffect(version, playlist?.id) {
        val p = playlist ?: return@LaunchedEffect
        try {
            val data = withContext(Dispatchers.IO) { recorder.recordings(p.id).sortedByDescending { it.startAt } to recorder.storages() }
            recordings = data.first
            storages = data.second
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = L("Recordings could not be loaded. Reopen this panel to retry.", "Impossible de charger les enregistrements. Rouvrez ce panneau pour réessayer.").text() }
    }
    LaunchedEffect(active?.id, storages.isEmpty(), profile.id, profile.kids) {
        withFrameNanos { }; withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    TvPanel(L("Recordings", "Enregistrements").text(), L("A single provider connection is shared between playback and DVR", "Le direct et le DVR partagent une seule connexion fournisseur").text(),
        modifier = Modifier.testTag("tv-recordings"), width = TvPanelSize.Standard.width, height = TvPanelSize.Standard.height) {
        val current = active?.takeIf { canPlayRecording(deps.db, it, profile.kids) }
        if (current != null) {
            val bytes = progress?.takeIf { it.id == current.id }?.bytes ?: current.bytes
            TvPanelNotice("●  ${current.title}  ·  ${formatBytes(bytes)}  ·  ${L("ends", "fin").text()} ${clock(current.endAt)}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionRow(L("Watch from the beginning", "Regarder depuis le début").text(), L("The recording is still growing", "L’enregistrement est toujours en cours").text(), modifier = Modifier.weight(1f), requester = first) {
                    playRecording(current)
                }
                TvActionRow(L("Extend 15 minutes", "Prolonger de 15 min").text(), modifier = Modifier.weight(1f)) {
                    if (!requireAccess(current)) return@TvActionRow
                    scope.launch { val result = withContext(Dispatchers.IO) { recorder.extend(current, 15) }; error = (result as? com.sokoul.desktop.data.PcRecordingRules.Verdict.Refused)?.reason }
                }
                TvActionRow(L("Stop and save", "Arrêter et enregistrer").text(), destructive = true, modifier = Modifier.weight(1f)) {
                    if (!requireAccess(current)) return@TvActionRow
                    scope.launch { withContext(Dispatchers.IO) { recorder.stop(current) } }
                }
            }
        }
        TvSectionLabel(L("Storage", "Stockage").text())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            storages.forEachIndexed { index, storage ->
                TvActionRow(storage.label, L("${formatBytes(storage.availableBytes)} free", "${formatBytes(storage.availableBytes)} libres").text(), modifier = Modifier.weight(1f), selected = storage.id == storages.firstOrNull { it.directory.absolutePath == recorder.dir.absolutePath }?.id,
                    requester = if (com.sokoul.pc.Platform.IS_TV && current == null && index == 0) first else remember(storage.id) { FocusRequester() }) {
                    val result = recorder.selectStorage(storage.id)
                    error = (result as? com.sokoul.desktop.data.PcRecordingRules.Verdict.Refused)?.reason
                }
            }
            if (!com.sokoul.pc.Platform.IS_TV) TvActionRow(L("Choose folder…", "Choisir un dossier…").text(), modifier = Modifier.weight(1f), requester = if (current == null) first else remember { FocusRequester() }) {
                val folder = com.sokoul.pc.Platform.pickRecordingDirectory() ?: return@TvActionRow
                val result = recorder.selectStorage(folder.absolutePath)
                error = (result as? com.sokoul.desktop.data.PcRecordingRules.Verdict.Refused)?.reason
            }
            if (com.sokoul.pc.Platform.IS_TV && current == null && storages.isEmpty()) {
                TvActionRow(L("Back to My TV", "Retour à mon espace TV").text(), requester = first, onClick = onClose)
            }
        }
        error?.let { TvPanelNotice(it, error = true) }
        if (copying) TvPanelNotice(L("Exporting recording…", "Export de l’enregistrement…").text())
        TvSectionLabel(L("Library", "Bibliothèque").text())
        if (accessibleRecordings.isEmpty()) TvPanelNotice(L("No recordings yet. Schedule a programme from its guide entry.", "Aucun enregistrement. Planifiez une émission depuis son guide.").text())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(accessibleRecordings, key = { it.id }) { item ->
                val available = item.path?.let(::File)?.let { it.isFile && it.length() > 0 } == true
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TvActionRow(
                        title = item.title,
                        subtitle = "${item.channelName}  ·  ${clock(item.startAt)}  ·  ${formatBytes(item.path?.let(::File)?.length() ?: item.bytes)}",
                        modifier = Modifier.weight(1f),
                        trailing = when (item.status) {
                            RecordingStatus.SCHEDULED -> L("Scheduled", "Planifié").text()
                            RecordingStatus.RECORDING -> L("Recording", "En cours").text()
                            RecordingStatus.COMPLETED -> L("Ready", "Disponible").text()
                            RecordingStatus.FAILED -> L("Failed", "Échec").text()
                            RecordingStatus.CANCELLED -> L("Cancelled", "Annulé").text()
                        },
                        requester = remember(item.id) { FocusRequester() },
                        onClick = {
                            if (!requireAccess(item)) return@TvActionRow
                            if (available) {
                                playRecording(item)
                            } else if (item.status == RecordingStatus.SCHEDULED) {
                                scope.launch { withContext(Dispatchers.IO) { recorder.cancel(item) }; error = L("Schedule cancelled.", "Enregistrement planifié annulé.").text() }
                            } else selected = item.id
                        },
                    )
                    if (available && item.status != RecordingStatus.RECORDING) {
                        TvActionRow(L("Export", "Exporter").text(), modifier = Modifier.width(160.dp)) { if (requireAccess(item)) exporting = if (exporting == item.id) 0L else item.id }
                    }
                    if (item.status != RecordingStatus.RECORDING) {
                        TvActionRow(
                            title = if (selected == item.id) L("Confirm delete", "Confirmer la suppression").text() else L("Delete", "Supprimer").text(),
                            modifier = Modifier.width(200.dp),
                            destructive = true,
                            requester = remember(item.id) { FocusRequester() },
                        ) {
                            if (!requireAccess(item)) return@TvActionRow
                            if (selected == item.id) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { recorder.delete(item) }
                                    recordings = recordings.filterNot { it.id == item.id }
                                    selected = 0L
                                    error = L("Recording deleted.", "Enregistrement supprimé.").text()
                                }
                            } else selected = item.id
                        }
                    }
                }
                if (exporting == item.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TvPanelNotice(L("Choose an export destination. The original recording is kept.", "Choisissez une destination d’export. L’enregistrement original est conservé.").text())
                        storages.forEach { storage ->
                            TvActionRow(storage.label, L("${formatBytes(storage.availableBytes)} free", "${formatBytes(storage.availableBytes)} libres").text()) {
                                if (!requireAccess(item)) return@TvActionRow
                                if (!copying) {
                                    copying = true
                                    scope.launch {
                                        try {
                                            val saved = withContext(Dispatchers.IO) { recorder.export(item, storage.id) }
                                            error = if (saved != null) L("Recording exported.", "Enregistrement exporté.").text()
                                                else L("Export failed. Check available space, folder access and existing files.", "Échec de l’export. Vérifiez l’espace disponible, l’accès au dossier et les fichiers existants.").text()
                                            if (saved != null) exporting = 0L
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { error = L("Recording could not be exported. Try another destination.", "Impossible d’exporter l’enregistrement. Essayez une autre destination.").text() }
                                        finally { copying = false }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Recheck the original source policy even for a local file; downloads lack category provenance. */
internal fun canPlayRecording(db: com.sokoul.desktop.data.DesktopDb, item: com.sokoul.desktop.data.Recording, kids: Boolean): Boolean {
    if (kids && (TvOrganization.adultName(item.title) || TvOrganization.adultName(item.channelName))) return false
    if (item.channelId.startsWith("download:")) return !kids
    val playlist = db.playlists().firstOrNull { it.id == item.playlistId } ?: return !kids
    val channel = db.channelsByIds(playlist.id, listOf(item.channelId)).firstOrNull() ?: return !kids
    if (kids && db.categories(playlist.id).none { it.id == channel.categoryId }) return false
    return TvOrganization(db, { playlist }, { kids }).canAccess(channel)
}

private fun formatBytes(bytes: Long): String = if (bytes <= 0) "—" else when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}

@Composable
internal fun TvRecordSchedulePanel(
    deps: AppDeps,
    channel: Channel,
    programme: Program,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onScheduled: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val playlist = deps.tv.playlist()
    val recorder = deps.tv.recorder
    val currentProgramme = programme.start < System.currentTimeMillis() && programme.end > System.currentTimeMillis()
    fun schedule(repeat: RecordingRepeat) {
        if (busy) return
        if (!deps.tv.organization.canAccess(channel)) { feedback = L("This channel is restricted for this profile.", "Cette chaîne est restreinte pour ce profil.").text(); return }
        val p = playlist ?: run { feedback = L("No provider source is configured.", "Aucune source fournisseur n’est configurée.").text(); return }
        busy = true
        scope.launch {
            val verdict = withContext(Dispatchers.IO) { recorder.scheduleProgramme(p, channel, programme, repeat) }
            busy = false
            when (verdict) {
                is com.sokoul.desktop.data.PcRecordingRules.Verdict.Ok -> {
                    if (verdict.warning != null) feedback = L("Scheduled. This account allows one connection, so another live stream will be stopped.", "Planifié. Ce compte n’autorise qu’une connexion : un autre direct sera refusé.").text()
                    onScheduled()
                }
                is com.sokoul.desktop.data.PcRecordingRules.Verdict.Refused -> feedback = verdict.reason
            }
        }
    }
    TvPanel(
        L("Record programme", "Enregistrer l’émission").text(),
        "${programme.title} · ${TvCatalog.shortLabel(channel.name)} · ${clock(programme.start)}–${clock(programme.end)}",
        modifier = modifier.testTag("tv-record-schedule"),
        width = 760.dp,
    ) {
        TvPanelNotice(
            if (currentProgramme) L("Recording starts now and continues through the programme end, with a 5 minute safety margin. The provider’s Replay remains separate for the part already aired.", "La capture commence maintenant et continue jusqu’à la fin de l’émission, avec 5 minutes de marge. Le replay du fournisseur reste séparé pour la partie déjà diffusée.").text()
            else L("The complete programme is scheduled, with a 2 minute lead-in and a 5 minute safety margin.", "L’émission complète est planifiée, avec 2 minutes d’avance et 5 minutes de marge.").text(),
        )
        TvActionRow(L("Record once", "Enregistrer une fois").text(), L("Full guide duration", "Durée complète du guide").text(), requester = first) { schedule(RecordingRepeat.NONE) }
        TvActionRow(L("Every day", "Chaque jour").text(), L("Keep this channel and time", "Même chaîne et même horaire").text()) { schedule(RecordingRepeat.DAILY) }
        TvActionRow(L("Weekdays", "En semaine").text(), L("Monday to Friday", "Du lundi au vendredi").text()) { schedule(RecordingRepeat.WEEKDAYS) }
        TvActionRow(L("Every week", "Chaque semaine").text(), L("Same day and time", "Même jour et même horaire").text()) { schedule(RecordingRepeat.WEEKLY) }
        feedback?.let { TvPanelNotice(it, error = it.contains("not configured", true) || it.contains("refus", true)) }
        if (busy) TvPanelNotice(L("Saving schedule…", "Enregistrement de la planification…").text())
    }
}
