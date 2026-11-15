package com.sokoul.pc.playback.ui
import com.sokoul.pc.playback.session.PlaybackSession
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.PcRecordingRules
import com.sokoul.desktop.data.RecordingStatus
import com.sokoul.pc.data.L
import com.sokoul.pc.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Provider capture has one fixed header, a bounded scrolling library and persistent actions. */
@Composable
internal fun RecordingPanel(session: PlaybackSession, modifier: Modifier = Modifier) {
    val binding = session.live?.recording?.invoke()
    val scope = rememberCoroutineScope()
    val playlist = binding?.playlist
    val channel = binding?.channel
    val recorder = binding?.recorder ?: com.sokoul.desktop.data.DesktopRecorder.runtime
    val active by recorder?.active?.collectAsState() ?: remember { mutableStateOf(null) }
    val progress by recorder?.progress?.collectAsState() ?: remember { mutableStateOf(null) }
    val version by recorder?.version?.collectAsState() ?: remember { mutableStateOf(0) }
    var recordings by remember { mutableStateOf(emptyList<com.sokoul.desktop.data.Recording>()) }
    var storage by remember { mutableStateOf(emptyList<com.sokoul.desktop.data.RecordingStorage>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    fun requireAccess(item: com.sokoul.desktop.data.Recording): Boolean {
        if (binding?.canPlay?.invoke(item) == true) return true
        message = L("This recording is restricted for this profile.", "Cet enregistrement est restreint pour ce profil.").text()
        return false
    }
    val accessibleRecordings = recordings.filter { binding?.canPlay?.invoke(it) == true }
    val programme by produceState<com.sokoul.desktop.data.Program?>(null, session.live?.key) {
        value = withContext(Dispatchers.IO) { session.live?.program?.invoke()?.now }
    }
    LaunchedEffect(version, playlist?.id) {
        val p = playlist ?: return@LaunchedEffect
        val r = recorder ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { r.recordings(p.id).sortedByDescending { it.startAt } to r.storages() }
        recordings = loaded.first
        storage = loaded.second
    }
    LaunchedEffect(session.live?.key, active?.id, busy) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    fun scheduleCurrent() {
        val p = playlist ?: return
        val c = channel ?: return
        val guide = programme?.takeIf { it.end > System.currentTimeMillis() }
        if (!requireAccess(com.sokoul.desktop.data.Recording(0, p.id, c.id, c.name, c.logo, guide?.title ?: c.name, 0, 0, RecordingStatus.SCHEDULED))) return
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val verdict = withContext(Dispatchers.IO) {
                if (guide != null) recorder?.scheduleProgramme(p, c, guide)
                else recorder?.schedule(p, c, c.name, System.currentTimeMillis(), System.currentTimeMillis() + 30 * 60_000L)
            }
            busy = false
            message = when (verdict) {
                is PcRecordingRules.Verdict.Refused -> verdict.reason
                is PcRecordingRules.Verdict.Ok -> verdict.warning ?: L("Recording scheduled through the programme end.", "Enregistrement planifié jusqu’à la fin de l’émission.").text()
                else -> L("The recording could not be started.", "Impossible de démarrer l’enregistrement.").text()
            }
        }
    }

    TvPanel(
        L("Recordings", "Enregistrements").text(),
        L("Full programme capture, schedules and saved files", "Capture complète, planifications et fichiers enregistrés").text(),
        modifier = modifier.testTag("player-recordings"),
        width = 760.dp,
        height = 820.dp,
    ) {
        if (binding == null || recorder == null) {
            TvPanelNotice(L("This source does not expose a local recording stream.", "Cette source ne fournit pas de flux enregistrable localement.").text())
            return@TvPanel
        }
        val activeForSource = active?.takeIf { it.playlistId == playlist?.id && binding.canPlay(it) }
        activeForSource?.let { current ->
            val bytes = progress?.takeIf { it.id == current.id }?.bytes ?: current.bytes
            TvSectionLabel(L("Recording now", "En cours").text())
            TvPanelNotice("●  ${current.title}  ·  ${formatBytes(bytes)}  ·  ${clock(current.endAt)}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionRow(L("Extend 15 minutes", "Prolonger de 15 min").text(), modifier = Modifier.weight(1f), requester = first) {
                    if (!requireAccess(current)) return@TvActionRow
                    scope.launch { message = (withContext(Dispatchers.IO) { recorder.extend(current, 15) } as? PcRecordingRules.Verdict.Refused)?.reason ?: L("Recording extended.", "Enregistrement prolongé.").text() }
                }
                TvActionRow(L("Stop and save", "Arrêter et enregistrer").text(), destructive = true, modifier = Modifier.weight(1f)) {
                    if (!requireAccess(current)) return@TvActionRow
                    scope.launch { withContext(Dispatchers.IO) { recorder.stop(current) }; message = L("Recording saved.", "Enregistrement sauvegardé.").text() }
                }
            }
        } ?: run {
            val current = programme?.takeIf { it.end > System.currentTimeMillis() }
            current?.let {
                TvSectionLabel(L("On now", "En cours de diffusion").text())
                TvPanelNotice("${it.title}  ·  ${clock(it.start)}–${clock(it.end)}")
                TvActionRow(
                    L("Record this programme to its end", "Enregistrer cette émission jusqu’à sa fin").text(),
                    L("${channel?.name.orEmpty()}  ·  5 min safety margin", "${channel?.name.orEmpty()}  ·  marge de 5 min").text(),
                    requester = first,
                    onClick = ::scheduleCurrent,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120).forEachIndexed { i, minutes ->
                    TvActionRow(L("${minutes} min", "${minutes} min").text(), modifier = Modifier.weight(1f), requester = if (current == null && i == 0) first else remember(minutes) { FocusRequester() }) {
                        if (!requireAccess(com.sokoul.desktop.data.Recording(0, playlist!!.id, channel!!.id, channel.name, channel.logo, channel.name, 0, 0, RecordingStatus.SCHEDULED))) return@TvActionRow
                        scope.launch {
                            val now = System.currentTimeMillis()
                            val result = withContext(Dispatchers.IO) { recorder.schedule(playlist!!, channel!!, channel.name, now, now + minutes * 60_000L) }
                            message = (result as? PcRecordingRules.Verdict.Refused)?.reason ?: L("Recording scheduled.", "Enregistrement planifié.").text()
                        }
                    }
                }
            }
        }

        TvSectionLabel(L("Save location", "Emplacement").text())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            storage.take(2).forEach { target ->
                TvActionRow(target.label, L("${formatBytes(target.availableBytes)} free", "${formatBytes(target.availableBytes)} libres").text(), modifier = Modifier.weight(1f)) {
                    message = (recorder.selectStorage(target.id) as? PcRecordingRules.Verdict.Refused)?.reason
                }
            }
            if (!com.sokoul.pc.Platform.IS_TV) TvActionRow(L("Choose folder…", "Choisir un dossier…").text(), modifier = Modifier.weight(1f)) {
                val folder = com.sokoul.pc.Platform.pickRecordingDirectory() ?: return@TvActionRow
                message = (recorder.selectStorage(folder.absolutePath) as? PcRecordingRules.Verdict.Refused)?.reason
            }
        }
        message?.let { TvPanelNotice(it, error = it.contains("refus", true) || it.contains("un seul", true)) }
        if (busy) TvPanelNotice(L("Scheduling…", "Planification…").text())
        TvSectionLabel(L("Saved library", "Bibliothèque").text())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(accessibleRecordings.filter { it.status !in setOf(RecordingStatus.RECORDING, RecordingStatus.CANCELLED) }, key = { it.id }) { item ->
                val file = item.path?.let(::File)
                val available = file?.isFile == true && file.length() > 0
                TvActionRow(
                    item.title,
                    "${item.channelName}  ·  ${clock(item.startAt)}  ·  ${formatBytes(file?.length() ?: item.bytes)}",
                    trailing = if (available) L("Play", "Lire").text() else if (item.status == RecordingStatus.SCHEDULED) L("Cancel", "Annuler").text() else L("Unavailable", "Indisponible").text(),
                    requester = if (available && recordings.firstOrNull { it.path?.let(::File)?.let { f -> f.isFile && f.length() > 0 } == true }?.id == item.id) first else remember(item.id) { FocusRequester() },
                ) {
                    if (!requireAccess(item)) return@TvActionRow
                    if (item.status == RecordingStatus.SCHEDULED) scope.launch { withContext(Dispatchers.IO) { recorder.cancel(item) }; message = L("Schedule cancelled.", "Planification annulée.").text() }
                    else if (available) session.openRecording(item)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}
