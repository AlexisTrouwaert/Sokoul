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
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.playback.sources.LocalServerKey
import com.sokoul.pc.ui.*
import kotlinx.coroutines.launch

internal fun SportsFailure.label(): String = when (this) {
    SportsFailure.NotConfigured -> L("Set up the Sokoul server in Settings > Credentials.","Configurez le serveur Sokoul dans Réglages > Identifiants.")
    SportsFailure.VipRequired -> L("This server requires a VIP account for native sports playback.","Ce serveur exige un compte VIP pour la lecture native du sport.")
    SportsFailure.Unavailable -> L("This sports source is unavailable. Try another server or refresh later.","Cette source sportive est indisponible. Essayez un autre serveur ou actualisez plus tard.")
    SportsFailure.InvalidResponse -> L("The sports server did not provide a playable native stream.","Le serveur de sport n’a pas fourni de flux natif lisible.")
}.text()

/** A native event/server picker; discovery never opens a stream or consumes an IPTV connection. */
@Composable
internal fun SportsScreen(
    deps: AppDeps,
    onPlay: (String, StreamRequest, String, String?, () -> Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val source = remember(deps) {
        SportsSources(deps.smallHttp,
            { ProviderConfig.key(deps.db,"mainapi_url") ?: ProviderConfig.MAINAPI_DEFAULT_URL },
            { ProviderConfig.key(deps.db,"mainapi") ?: LocalServerKey.read() })
    }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var selected by remember { mutableStateOf<SportsEvent?>(null) }
    var servers by remember { mutableStateOf(emptyList<SportsServer>()) }
    var failure by remember { mutableStateOf<SportsFailure?>(null) }
    var loading by remember { mutableStateOf(true) }
    var resolving by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var serverRefresh by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf("") }
    val profile = deps.profiles.current

    LaunchedEffect(refresh,profile.id) {
        if (profile.kids) { events = emptyList(); loading = false; return@LaunchedEffect }
        loading = true
        failure = null
        val response = source.events()
        events = response.value.orEmpty()
        failure = response.failure
        loading = false
    }
    LaunchedEffect(selected,serverRefresh,profile.id) {
        if (profile.kids) { servers = emptyList(); loading = false; return@LaunchedEffect }
        val event = selected ?: return@LaunchedEffect
        loading = true
        failure = null
        servers = emptyList()
        val response = source.servers(event)
        servers = response.value.orEmpty()
        failure = response.failure
        loading = false
    }
    LaunchedEffect(selected,loading) { withFrameNanos { }; runCatching { first.requestFocus() } }
    fun back() {
        if (selected != null && !resolving) { selected = null; failure = null; loading = false }
        else onClose()
    }
    val categories = listOf("") + events.map { it.sport }.filter(String::isNotBlank).distinct().sorted()
    TvPanel(
        L("Sports", "Sport").text(), selected?.name ?: L("Live and upcoming events", "Rencontres en direct et à venir").text(),
        Modifier.testTag("sports-screen").onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { back(); true } else false
        }, width = TvPanelSize.Wide.width, height = TvPanelSize.Wide.height,
    ) {
        Row(Modifier.padding(horizontal=28.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            PillButton(L("Back","Retour").text(),::back,requester=first)
            if (selected == null) {
                PillButton(L("Refresh","Actualiser").text(),{ refresh++ },Modifier.testTag("sports-refresh"))
                PillButton(category.ifEmpty { L("All sports","Tous les sports").text() },{
                    category = categories[(categories.indexOf(category)+1).mod(categories.size)]
                },Modifier.testTag("sports-category"))
            } else {
                PillButton(L("Refresh servers","Actualiser les serveurs").text(),{ serverRefresh++ },Modifier.testTag("sports-refresh-servers"))
            }
        }
        if (profile.kids) {
            TvPanelNotice(L("Choose an adult profile to browse these external sports sources.","Choisissez un profil adulte pour consulter ces sources sportives externes.").text())
        } else if (loading || resolving) {
            TvPanelNotice(if (resolving) L("Preparing the selected server…","Préparation du serveur choisi…").text() else L("Loading sports…","Chargement du sport…").text())
        } else {
            failure?.let { TvPanelNotice(it.label(),error=true) }
            val event = selected
            if (event == null) {
                val visible = events.filter { category.isEmpty() || it.sport == category }
                if (visible.isEmpty() && failure == null) TvPanelNotice(L("No events are listed right now.","Aucune rencontre n’est annoncée pour le moment.").text())
                LazyColumn(Modifier.weight(1f).padding(horizontal=24.dp).testTag("sports-events")) {
                    items(visible,key={ it.id }) { item ->
                        val whenText = if (item.live) L("Live","En direct").text() else item.startsAt?.let {
                            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
                        } ?: L("Upcoming","À venir").text()
                        TvActionRow(item.name,listOf(item.sport,whenText).filter(String::isNotBlank).joinToString(" · "),Modifier.testTag("sports-event-${item.id}")) {
                            selected = item
                        }
                    }
                }
            } else {
                if (servers.isEmpty() && failure == null) TvPanelNotice(L("No server is available for this event yet.","Aucun serveur n’est encore disponible pour cette rencontre.").text())
                LazyColumn(Modifier.weight(1f).padding(horizontal=24.dp).testTag("sports-servers")) {
                    items(servers,key={ it.key }) { server ->
                        TvActionRow(server.title,L("Play in Sokoul","Lire dans Sokoul").text(),Modifier.testTag("sports-server-${server.key}")) {
                            if (!resolving) scope.launch {
                                resolving = true
                                failure = null
                                val result = source.resolve(event,server)
                                failure = result.failure
                                resolving = false
                                result.value?.let { stream ->
                                    val profileId = profile.id
                                    onPlay("sports:${event.id}:${server.key}",stream,event.name,null,{ deps.profiles.current.id == profileId && !deps.profiles.current.kids })
                                    onClose()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
