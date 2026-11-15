package com.sokoul.pc.party

import androidx.compose.animation.animateColorAsState
import com.sokoul.pc.ui.glassSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.PillButton
import com.sokoul.pc.ui.moveWithArrow
import kotlinx.coroutines.launch

/**
 * The watch party panel of the player: create a room for the title playing, or join one by its code; once in, the
 * code to share, who is there, the chat and the way out. Same glass panel as the player's other menus.
 */
@Composable
fun WatchPartyPanel(party: WatchParty, prefs: DesktopPrefs, current: PartyMedia?, positionSec: () -> Double, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(party.active) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(
        modifier.width(720.dp).glassSurface(RoundedCornerShape(40.dp), 40.dp).padding(28.dp).testTag("player-party"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Label(L("Watch party", "Watch party").text(), Type.Meta, Palette.Secondary)
        when (val s = party.state) {
            is PartyState.In -> InRoom(party, s, first, scope)
            else -> {
                var server by remember { mutableStateOf(prefs.partyServer) }
                var nickname by remember { mutableStateOf(prefs.partyNickname) }
                var code by remember { mutableStateOf("") }
                var createMode by remember { mutableStateOf("classic") }
                Field(L("Server", "Serveur").text(), server, "party-server", first) { server = it }
                Field(L("Your name", "Votre nom").text(), nickname, "party-nickname") { nickname = it }
                if (com.sokoul.pc.Platform.IS_TV) {
                    Label(
                        L("The address of the computer running the watch party server, for instance http://192.168.0.20:25566", "L'adresse de l'ordinateur qui fait tourner le serveur watch party, par exemple http://192.168.0.20:25566").text(),
                        Type.Body, Palette.Secondary,
                    )
                }
                val ready = server.trim().removePrefix("http://").removePrefix("https://").isNotBlank() && nickname.isNotBlank() && s !is PartyState.Connecting
                fun remember() {
                    prefs.partyServer = server.trim()
                    prefs.partyNickname = nickname.trim()
                }
                if (current != null) {
                    PillButton(
                        if (createMode == "pro") L("Synchronization: Pro", "Synchronisation : Pro").text() else L("Synchronization: Classic", "Synchronisation : classique").text(),
                        { createMode = if (createMode == "pro") "classic" else "pro" }, Modifier.testTag("party-create-mode"),
                    )
                    PillButton(L("Start a watch party", "Lancer une watch party").text(), {
                        if (ready) {
                            remember()
                            scope.launch { party.create(server.trim(), nickname.trim(), current, positionSec(), createMode) }
                        }
                    }, Modifier.testTag("party-create"))
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Field(L("Code", "Code").text(), code, "party-code", width = 360) { code = it.uppercase().take(6) }
                    PillButton(L("Join", "Rejoindre").text(), {
                        if (ready && code.length >= 4) {
                            remember()
                            scope.launch { party.join(server.trim(), code, nickname.trim()) }
                        }
                    }, Modifier.testTag("party-join"))
                }
                when (s) {
                    PartyState.Connecting -> Label(L("Connecting…", "Connexion…").text(), Type.Body, Palette.Secondary)
                    is PartyState.Failed -> Label(s.message, Type.Body, Palette.Error, Modifier.testTag("party-error"))
                    else -> Label(
                        L("Everyone plays the title with their own sources; play, pause and jumps stay in step.", "Chacun lit le titre avec ses propres sources ; lecture, pause et sauts restent synchronisés.").text(),
                        Type.Body, Palette.Secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun InRoom(party: WatchParty, s: PartyState.In, first: FocusRequester, scope: kotlinx.coroutines.CoroutineScope) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Label(s.code, Type.RowTitle.copy(fontSize = 56.sp, fontWeight = FontWeight.Bold), modifier = Modifier.testTag("party-room-code"))
        Label(
            (if (s.host) L("Share this code", "Partagez ce code") else L("You joined", "Vous avez rejoint")).text(),
            Type.Body, Palette.Secondary,
        )
    }
    Label(party.participants.joinToString(" · ").ifEmpty { "…" }, Type.Meta, Palette.BarText, maxLines = 2)
    val modeLabel = if (party.syncMode == "pro") L("Synchronization: Pro", "Synchronisation : Pro").text() else L("Synchronization: Classic", "Synchronisation : classique").text()
    if (s.host && party.syncSupported) {
        PillButton(modeLabel, { party.setSyncMode(if (party.syncMode == "pro") "classic" else "pro") }, Modifier.testTag("party-room-mode"))
    } else Label(modeLabel, Type.Body, Palette.Secondary, Modifier.testTag("party-room-mode-label"))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x33000000)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val last = party.messages.takeLast(VISIBLE_MESSAGES)
        if (last.isEmpty()) Label(L("No messages yet.", "Pas encore de message.").text(), Type.Body, Palette.Secondary)
        last.forEach { m ->
            Label(if (m.system) m.text else "${m.from} : ${m.text}", Type.Body, if (m.system) Palette.Secondary else Palette.OnBackground, maxLines = 2)
        }
    }
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Field(L("Message", "Message").text(), text, "party-message", first, width = 440, onDone = {
            party.say(text)
            text = ""
        }) { text = it }
        PillButton(L("Send", "Envoyer").text(), {
            party.say(text)
            text = ""
        }, Modifier.testTag("party-send"))
    }
    PillButton(L("Leave the watch party", "Quitter la watch party").text(), { scope.launch { party.leave() } }, Modifier.testTag("party-leave"))
}

/** A one-line text field in the look of the setup screen's rows: grey pill, white when focused. */
@Composable
private fun Field(label: String, value: String, tag: String, requester: FocusRequester? = null, width: Int = 664, onDone: (() -> Unit)? = null, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.RowFill)
    val text = if (focused) Palette.OnFocus else Palette.OnBackground
    val focusManager = LocalFocusManager.current
    Row(
        Modifier.width(width.dp).height(64.dp).clip(RoundedCornerShape(32.dp)).background(fill).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, Type.Body.copy(fontWeight = FontWeight.SemiBold), if (focused) Palette.OnFocus.copy(alpha = 0.6f) else Palette.Secondary)
        Spacer(Modifier.width(18.dp))
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = Type.Meta.copy(color = text),
            cursorBrush = SolidColor(text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth().testTag(tag)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    when {
                        e.type != KeyEventType.KeyDown -> false
                        onDone != null && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                            onDone()
                            true
                        }
                        e.key == Key.DirectionUp || e.key == Key.DirectionDown -> focusManager.moveWithArrow(e)
                        else -> false
                    }
                },
        )
    }
}

private const val VISIBLE_MESSAGES = 6
