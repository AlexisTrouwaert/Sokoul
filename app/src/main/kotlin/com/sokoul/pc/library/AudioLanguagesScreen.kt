package com.sokoul.pc.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.AudioIntent
import com.sokoul.desktop.data.AudioFallback
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.SectionPill
import com.sokoul.pc.ui.tvFocusable

/** The languages offered to add, besides the work's original one; any other comes from the tools or a later list. */
internal val AUDIO_LANGUAGE_CHOICES = listOf("fr", "en", "es", "it", "de", "pt", "ja", "ko", "zh", "ru", "ar", "hi", "tr", "nl", "pl", "sv")

/** "Original audio", or the language's name in the interface language. */
internal fun audioEntryName(entry: String): String =
    if (entry == AudioIntent.ORIGINAL) L("Original audio", "Audio original").text() else com.sokoul.pc.playback.ui.languageName(entry)

/** "French → Original audio": the wanted languages in order, as one line. */
// The arrow points the way the text reads: right to left in Arabic.
internal fun audioIntentSummary(intent: AudioIntent): String =
    intent.languages.joinToString(if (com.sokoul.pc.i18n.InterfaceLanguages.current().rtl) " ← " else " → ", transform = ::audioEntryName)

/**
 * The audio languages wanted, in order, for any language (book section 3): the first one available in a version and in
 * its file is played. Same rows and round buttons as the home rows: the arrows change a language's place, the cross
 * removes it, and the list below adds one. It only orders: every version and track stays available in the player.
 */
@Composable
fun AudioLanguagesScreen(prefs: DesktopPrefs, onBack: () -> Unit) {
    var intent by remember { mutableStateOf(prefs.audioIntent) }
    val change = { languages: List<String> ->
        intent = intent.copy(languages = languages)
        prefs.audioIntent = intent
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(Palette.Background).testTag("audio-languages")
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onBack(); true } else false },
    ) {
        SectionPill(Icons.Filled.Translate, L("Audio languages", "Langues audio").text(), onClick = onBack, modifier = Modifier.padding(start = 40.dp, top = 58.dp), onChevron = onBack)
        Label(
            L(
                "The first language a version offers is played. Original audio is the language the work was made in. Every version and track stays available in the player.",
                "La première langue qu'une version propose est lue. L'audio original est la langue dans laquelle l'œuvre a été faite. Toutes les versions et pistes restent disponibles dans le lecteur.",
            ).text(),
            Type.Meta, Palette.Secondary, Modifier.offset(x = 80.dp, y = 150.dp).width(560.dp),
        )
        Column(
            Modifier.offset(x = Metrics.SettingsColumnX - 160.dp).width(Metrics.SettingsColumnWidth + 160.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 90.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.RowGap),
        ) {
            val chosen = intent.languages
            chosen.forEachIndexed { i, entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LanguageRow("${i + 1}. ${audioEntryName(entry)}", null, if (i == 0) first else remember { FocusRequester() }, Modifier.weight(1f).testTag("audio-language-$entry")) {}
                    RoundButton(Icons.Filled.KeyboardArrowUp, i > 0, "audio-language-up-$entry") { change(chosen.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                    RoundButton(Icons.Filled.KeyboardArrowDown, i < chosen.lastIndex, "audio-language-down-$entry") { change(chosen.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                    // At least one language stays: with none, nothing would say what to play.
                    RoundButton(Icons.Filled.Close, chosen.size > 1, "audio-language-remove-$entry") { change(chosen - entry) }
                }
            }
            ChoiceRow(
                L("If no language matches", "Si aucune langue ne correspond").text(),
                "audio-language-fallback", AudioFallback.entries, intent.fallback,
                { fallback -> when (fallback) {
                    AudioFallback.EngineDefault -> L("Play the file's own track", "Lire la piste du fichier").text()
                    AudioFallback.Ask -> L("Ask me", "Me demander").text()
                } },
            ) {
                intent = intent.copy(fallback = it)
                prefs.audioIntent = intent
            }
            GroupTitle(L("Add a language", "Ajouter une langue").text())
            (listOf(AudioIntent.ORIGINAL) + AUDIO_LANGUAGE_CHOICES).filterNot { it in chosen }.forEach { entry ->
                LanguageRow(audioEntryName(entry), Icons.Filled.Add, remember { FocusRequester() }, Modifier.testTag("audio-language-add-$entry")) { change(chosen + entry) }
            }
        }
    }
}

/** A language as a row: OK runs [onClick]; [trailing] shows what it does when it does something. */
@Composable
internal fun LanguageRow(name: String, trailing: ImageVector?, requester: FocusRequester, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.RowFill)
    val text = if (focused) Palette.OnFocus else Palette.OnBackground
    Row(
        modifier.height(Metrics.RowHeight).clip(RoundedCornerShape(50)).background(fill)
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester).padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(name, Type.Button.copy(fontSize = Type.Button.fontSize * 0.96f), text, Modifier.weight(1f), maxLines = 1)
        trailing?.let { Icon(it, null, tint = if (focused) Palette.OnFocus else Palette.Secondary, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, enabled: Boolean, tag: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.ButtonFill)
    Box(
        Modifier.size(Metrics.RowHeight).clip(CircleShape).background(fill).testTag(tag)
            .tvFocusable(onFocusChanged = { focused = it }, onClick = { if (enabled) onClick() }, requester = remember { FocusRequester() }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (focused) Palette.OnFocus else if (enabled) Palette.OnBackground else Color(0x55FFFFFF), modifier = Modifier.size(36.dp))
    }
}
