package com.sokoul.pc.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.ui.TvActionRow
import com.sokoul.pc.ui.TvPanel
import com.sokoul.pc.ui.TvPanelSize
import com.sokoul.pc.ui.TvPanelNotice
import com.sokoul.pc.ui.TvSectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The TV page's settings, behind its gear: how channel up/down travels, how the guide is ordered, the live preview,
 * and the provider's own controls (organization with the parental code, subscription and guide). One place only.
 */
@Composable
internal fun TvSettingsPanel(deps: AppDeps, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val repo = deps.personalTv
    val revision by repo.revision.collectAsState()
    val state by produceState(TvPersonalState(), revision) { value = withContext(Dispatchers.IO) { repo.state() } }
    var preview by remember { mutableStateOf(deps.prefs.livePreview) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    fun onOff(value: Boolean) = if (value) L("On", "Activé").text() else L("Off", "Désactivé").text()
    TvPanel(
        L("TV settings", "Réglages TV").text(),
        L("Zapping, guide order, preview and your provider", "Zapping, ordre du guide, aperçu et fournisseur").text(),
        width = TvPanelSize.Standard.width, height = TvPanelSize.Standard.height,
        footer = L("OK to change · Back to guide", "OK pour modifier · Retour au guide").text(),
        modifier = Modifier.testTag("tv-settings"),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TvSectionLabel(L("Channel up / down", "Chaîne suivante / précédente").text())
            TvZapScope.entries.forEachIndexed { i, mode ->
                TvActionRow(zapScopeLabel(mode), selected = state.zapScope == mode, requester = if (i == 0) first else remember { FocusRequester() },
                    modifier = Modifier.testTag("tv-settings-zap-${mode.name}")) {
                    scope.launch(Dispatchers.IO) { repo.setZapScope(mode) }
                }
            }
            if (state.zapScope == TvZapScope.ActiveCollection && state.activeCollectionId == null)
                TvPanelNotice(L("Choose a collection in the group column, then Menu › Use for zapping.",
                    "Choisissez une collection dans la colonne des groupes, puis Menu › Activer pour zapper.").text())
            TvSectionLabel(L("Guide", "Guide").text(), Modifier.padding(top = 12.dp))
            TvActionRow(L("Favorites first", "Favoris en tête").text(), onOff(state.favoritesPinned), modifier = Modifier.testTag("tv-settings-pin")) {
                scope.launch(Dispatchers.IO) { repo.setPinned(!state.favoritesPinned) }
            }
            TvActionRow(L("Live preview", "Aperçu en direct").text(), onOff(preview), modifier = Modifier.testTag("tv-settings-preview")) {
                preview = !preview
                deps.prefs.livePreview = preview
            }
            TvActionRow(L("Organize groups and channels", "Organiser les groupes et les chaînes").text(),
                L("Hide, order, parental code", "Masquer, ordonner, code parental").text(), modifier = Modifier.testTag("tv-settings-organize")) { onOpen("organize") }
            TvSectionLabel(L("Provider", "Fournisseur").text(), Modifier.padding(top = 12.dp))
            TvActionRow(L("Subscription and guide", "Abonnement et guide").text(),
                L("Status, stream format, guide offset, refresh", "État, format du flux, décalage du guide, actualisation").text(),
                modifier = Modifier.testTag("tv-settings-account")) { onOpen("account") }
        }
    }
}
