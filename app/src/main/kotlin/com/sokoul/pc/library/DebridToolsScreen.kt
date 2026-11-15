package com.sokoul.pc.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.pc.Platform
import com.sokoul.pc.data.L
import com.sokoul.pc.playback.session.*
import com.sokoul.pc.playback.sources.*
import com.sokoul.pc.playback.ui.*
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.PillButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Manual host-link utility backed by the existing VIP service; generated links stay in screen memory. */
@Composable
fun DebridToolsScreen(service: DebridToolsService, onPlay: (StreamRequest) -> Unit, onOpenDownload: (String) -> Unit, onBack: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf<List<HostDebridProvider>>(emptyList()) }
    var selected by remember { mutableStateOf<HostDebridProvider?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<DebridToolFailure?>(null) }
    var result by remember { mutableStateOf<DebridToolResult?>(null) }
    var editing by remember { mutableStateOf(!Platform.IS_TV) }
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    val row = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishEditing() {
        if (Platform.IS_TV) {
            editing = false
            keyboard?.hide()
            scope.launch { withFrameNanos { }; runCatching { row.requestFocus() } }
        }
    }
    suspend fun loadProviders() {
        busy = true
        error = null
        try {
            providers = service.providers()
            selected = providers.firstOrNull()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DebridToolException) { error = failure.reason }
        catch (_: Exception) { error = DebridToolFailure.Unavailable }
        finally { busy = false }
    }
    LaunchedEffect(service) {
        if (Platform.IS_TV) { withFrameNanos { }; runCatching { row.requestFocus() } }
        loadProviders()
    }
    LaunchedEffect(editing) {
        if (editing && Platform.IS_TV) {
            withFrameNanos { }; runCatching { field.requestFocus() }; keyboard?.show()
        }
    }
    Column(Modifier.fillMaxSize().background(com.sokoul.pc.theme.PageGradient).onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
            if (editing && Platform.IS_TV) finishEditing() else onBack()
            true
        } else false
    }.padding(horizontal = 80.dp, vertical = 56.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Same title and summary styles as the settings panes it opens from, and the same name as its entry.
        Label(L("Host link tools", "Outils de liens hébergés").text(), Type.RowTitle.copy(fontSize = 46.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), Palette.OnBackground)
        Label(L("Generate a download or playable link with a host service enabled on your Sokoul server.", "Générez un lien de téléchargement ou de lecture avec un service activé sur votre serveur Sokoul.").text(), Type.Meta, Palette.Secondary)
        if (!editing && Platform.IS_TV) {
            SettingsRow(L("Host link", "Lien de l'hébergeur").text(), if (link.isBlank()) L("Press OK to enter a link", "Appuyez sur OK pour saisir un lien").text() else L("Link entered", "Lien saisi").text(), true, row, { editing = true }, "debrid-link-edit")
        } else {
            BasicTextField(link, { if (it.length <= 8192) { link = it; result = null; error = null } },
                singleLine = true, textStyle = Type.Body.copy(color = Palette.OnBackground),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finishEditing() }),
                modifier = Modifier.fillMaxWidth().background(com.sokoul.pc.theme.Glass.RowCurrent, RoundedCornerShape(com.sokoul.pc.theme.Corners.Row)).padding(horizontal = com.sokoul.pc.ui.PanelMetrics.RowPad, vertical = 22.dp)
                    .focusRequester(field).testTag("debrid-link-input").onPreviewKeyEvent {
                        if (Platform.IS_TV && it.type == KeyEventType.KeyDown && (it.key == Key.DirectionDown || it.key == Key.DirectionUp)) { finishEditing(); true } else false
                    })
        }
        if (providers.isNotEmpty()) {
            SettingsRow(L("Service", "Service").text(), selected?.label, false, remember { FocusRequester() }, {
                if (!busy) { selected = providers[(providers.indexOf(selected) + 1) % providers.size]; result = null }
            }, "debrid-provider")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PillButton(if (busy) L("Generating…", "Génération…").text() else L("Generate link", "Générer le lien").text(), {
                val provider = selected
                if (!busy && provider != null) scope.launch {
                    busy = true; error = null; result = null
                    try { result = service.unlock(link, provider) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: DebridToolException) { error = failure.reason }
                    catch (_: Exception) { error = DebridToolFailure.Unavailable }
                    finally { busy = false }
                }
            }, Modifier.testTag("debrid-generate"))
            PillButton(L("Reload services", "Recharger les services").text(), { if (!busy) scope.launch { loadProviders() } }, Modifier.testTag("debrid-reload"))
        }
        error?.let { reason ->
            val message = when (reason) {
                DebridToolFailure.Configuration -> L("Configure your Sokoul server and access key in Credentials.", "Configurez le serveur Sokoul et sa clé dans les Identifiants.")
                DebridToolFailure.InvalidLink -> L("Enter a public HTTP or HTTPS host link.", "Saisissez un lien HTTP ou HTTPS public d'hébergeur.")
                DebridToolFailure.VipRequired -> L("The server requires an active VIP key.", "Le serveur demande une clé VIP active.")
                DebridToolFailure.ProviderRefused -> L("This service could not unlock the link.", "Ce service n'a pas pu débrider le lien.")
                DebridToolFailure.Unavailable -> L("The service is unavailable. Try again later.", "Le service est indisponible. Réessayez plus tard.")
            }
            Label(message.text(), Type.Body, Palette.Error, Modifier.testTag("debrid-error"))
        }
        if (!busy && error == null && providers.isEmpty()) Label(L("No host service is enabled on the server.", "Aucun service de débridage n'est activé sur le serveur.").text(), Type.Body, Palette.Secondary)
        result?.let { unlocked ->
            Label(unlocked.filename.ifBlank { L("Generated link", "Lien généré").text() }, Type.Body, Palette.OnBackground, Modifier.testTag("debrid-result"))
            Label("${unlocked.provider.label} · ${unlocked.host}" + if (unlocked.filesize > 0) " · ${unlocked.filesize / 1_000_000} MB" else "", Type.Meta, Palette.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (unlocked.playable) PillButton(L("Play", "Lire").text(), {
                    onPlay(StreamRequest(unlocked.link, null, null, unlocked.filename, live = false,
                        mediaType = if (unlocked.filename.endsWith(".m3u8", true) || unlocked.link.substringBefore('?').endsWith(".m3u8", true)) "hls" else null))
                }, Modifier.testTag("debrid-play"))
                PillButton(L("Open download", "Ouvrir le téléchargement").text(), { onOpenDownload(unlocked.link) }, Modifier.testTag("debrid-download"))
            }
        }
        PillButton(L("Back", "Retour").text(), onBack, Modifier.testTag("debrid-back"))
    }
}
