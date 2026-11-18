package com.sokoul.pc.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.pc.data.L
import com.sokoul.pc.i18n.InterfaceLanguages
import com.sokoul.pc.i18n.Strings
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.LocalHoverOnly
import com.sokoul.pc.ui.SectionPill

/** A language named in itself (Español, Deutsch, العربية), so that its reader finds it whatever the interface shows. */
internal fun interfaceLanguageName(code: String): String =
    if (code == "auto") L("System", "Système").text()
    else java.util.Locale.forLanguageTag(code).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }

/**
 * The interface language as a list, as Apple TV shows it: "System" then every shipped language, the one in use checked.
 * OK applies it at once, and the page itself redraws in the new language; Back returns to the settings.
 */
@Composable
fun InterfaceLanguageScreen(prefs: DesktopPrefs, onChanged: () -> Unit, onBack: () -> Unit) {
    var chosen by remember { mutableStateOf(prefs.language.takeIf { it == "auto" || InterfaceLanguages.all.any { l -> l.code == it } } ?: "auto") }
    val choices = listOf("auto") + InterfaceLanguages.all.map { it.code }
    val requesters = remember { choices.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requesters.getValue(chosen).requestFocus() }
    }
    CompositionLocalProvider(LocalHoverOnly provides true) {
        Box(
            Modifier.fillMaxSize().background(Palette.Background).testTag("interface-languages")
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onBack(); true } else false },
        ) {
            SectionPill(Icons.Filled.Language, L("Language", "Langue").text(), onClick = onBack, modifier = Modifier.padding(start = 40.dp, top = 58.dp), onChevron = onBack)
            Label(
                L("The whole interface changes at once. Titles and descriptions follow the language too.", "Toute l'interface change aussitôt. Les titres et les descriptions suivent aussi la langue.").text(),
                Type.Meta, Palette.Secondary, Modifier.offset(x = 80.dp, y = 150.dp).width(560.dp),
            )
            Column(
                Modifier.offset(x = Metrics.SettingsColumnX - 160.dp).width(Metrics.SettingsColumnWidth + 160.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 90.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(Metrics.RowGap),
            ) {
                choices.forEach { code ->
                    LanguageRow(
                        interfaceLanguageName(code), if (code == chosen) Icons.Filled.Check else null, requesters.getValue(code),
                        Modifier.fillMaxWidth().testTag("interface-language-$code"),
                    ) {
                        if (code != chosen) {
                            chosen = code
                            prefs.language = code
                            Strings.applyLanguage(code)
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}
