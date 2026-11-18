package com.sokoul.pc.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.tvFocusable

private enum class Pane(val id: String, val icon: ImageVector, val english: String, val french: String, val summaryEn: String, val summaryFr: String) {
    ACCOUNTS("accounts", Icons.Filled.Person, "Accounts", "Comptes", "Who is watching, and the services Sokoul draws on.", "Qui regarde, et les services dont Sokoul se sert."),
    APPEARANCE("appearance", Icons.Filled.Palette, "Appearance", "Apparence", "The colour of what shows your progress.", "La couleur de ce qui montre votre progression."),
    HOME("home", Icons.Filled.Home, "Home", "Accueil", "The rows of the home page and what moves by itself.", "Les rangées de l'accueil et ce qui bouge tout seul."),
    MENU("menu", Icons.Filled.Menu, "Menu", "Menu", "Hide the entries you never use.", "Cachez les entrées dont vous ne vous servez pas."),
    SUBTITLES("subtitles", Icons.Filled.Subtitles, "Subtitles", "Sous-titres", "How subtitles look, for every video.", "L'aspect des sous-titres, pour toutes les vidéos."),
    PLAYBACK("playback", Icons.Filled.PlayCircle, "Playback", "Lecture", "How a title starts and which version comes first.", "Comment un titre démarre et quelle version passe en premier."),
    LANGUAGE("language", Icons.Filled.Language, "Language", "Langue", "The language of the interface.", "La langue de l'interface."),
    ABOUT("about", Icons.Filled.Info, "About", "À propos", "This app, its credits and a little housekeeping.", "Cette application, ses crédits et un peu de ménage.");

    fun label() = L(english, french).text()
    fun summary() = L(summaryEn, summaryFr).text()
}

/**
 * Settings as Apple TV lays them out: the categories down the left, and what the chosen one holds on the right, with its
 * title and a line on what it is for. Moving between categories shows each at once; Right goes into it and Left comes back.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun SettingsScreen(
    firstRow: FocusRequester,
    prefs: DesktopPrefs?,
    onCredentials: () -> Unit,
    profile: com.sokoul.pc.data.Profile? = null,
    onProfiles: (() -> Unit)? = null,
    hooks: SettingsHooks = SettingsHooks(),
    /** The category shown first (a pane id); the renders and the tests ask for one. */
    initialPane: String = "accounts",
) {
    var pane by remember { mutableStateOf(Pane.entries.firstOrNull { it.id == initialPane } ?: Pane.ACCOUNTS) }
    // Asking for the focus on the pane's group gives it to its first row, whichever row lies nearest to the category.
    val paneEntry = remember { FocusRequester() }
    val panes = remember(prefs) { if (prefs == null) listOf(Pane.ACCOUNTS) else Pane.entries.toList() }
    // The category that takes the focus when the page opens is the one shown first.
    val start = remember { Pane.entries.firstOrNull { it.id == initialPane } ?: Pane.ACCOUNTS }
    androidx.compose.runtime.CompositionLocalProvider(com.sokoul.pc.ui.LocalHoverOnly provides true) {
    Box(Modifier.fillMaxSize().background(com.sokoul.pc.theme.PageGradient).testTag("settings")) {
        Column(Modifier.offset(x = 80.dp, y = 150.dp).width(540.dp)) {
            Label(com.sokoul.pc.i18n.tr(com.sokoul.pc.i18n.Text.MenuSettings), com.sokoul.pc.theme.PanelType.Title.copy(fontSize = 56.sp), modifier = Modifier.padding(start = com.sokoul.pc.ui.PanelMetrics.RowPad, bottom = 26.dp))
            // Coming back into the list from the right lands on the category in use, not on whichever lies nearest.
            Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                panes.forEach { p ->
                    CategoryRow(p, p == pane, if (p == start) firstRow else remember { FocusRequester() }, onEnter = { runCatching { paneEntry.requestFocus() }.isSuccess }) { pane = p }
                }
            }
        }
        key(pane) {
            Column(
                Modifier.offset(x = 700.dp).width(1140.dp).fillMaxHeight().focusRequester(paneEntry).focusGroup().verticalScroll(rememberScrollState()).padding(top = 150.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Label(pane.label(), com.sokoul.pc.theme.PanelType.Title, modifier = Modifier.padding(start = com.sokoul.pc.ui.PanelMetrics.RowPad).testTag("settings-title"))
                Label(pane.summary(), com.sokoul.pc.theme.PanelType.Callout, Palette.Secondary, Modifier.padding(start = com.sokoul.pc.ui.PanelMetrics.RowPad, bottom = 14.dp, end = 30.dp))
                when (pane) {
                    Pane.ACCOUNTS -> AccountsPane(prefs, profile, onProfiles, onCredentials, hooks)
                    Pane.APPEARANCE -> prefs?.let { AppearancePane(it, hooks) }
                    Pane.HOME -> prefs?.let { HomePane(it, hooks) }
                    Pane.MENU -> prefs?.let { MenuPane(it, hooks) }
                    Pane.SUBTITLES -> prefs?.let { SubtitlesPane(it) }
                    Pane.PLAYBACK -> prefs?.let { PlaybackPane(it, hooks) }
                    Pane.LANGUAGE -> prefs?.let { LanguagePane(it, hooks) }
                    Pane.ABOUT -> AboutPane(hooks)
                }
            }
        }
    }
    }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }
}

/** One category: its icon and name; it is shown as the current one even when the focus has gone into its rows. */
@Composable
private fun CategoryRow(p: Pane, current: Boolean, requester: FocusRequester, onEnter: () -> Boolean, onSelect: () -> Unit) {
    com.sokoul.pc.ui.PanelOption(
        p.label(),
        Modifier.testTag("settings-pane-${p.id}")
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && onEnter() },
        requester = requester,
        restFill = if (current) com.sokoul.pc.theme.Glass.RowCurrent else Color.Transparent,
        titleStyle = com.sokoul.pc.theme.PanelType.Option.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium),
        // The keyboard shows a category as it reaches it; the mouse only by a click, so that crossing the list changes nothing.
        onFocused = { if (it && !com.sokoul.pc.ui.FocusSource.lastWasMouse) onSelect() },
        leading = { tint -> Icon(p.icon, null, tint = tint, modifier = Modifier.size(32.dp)) },
        onClick = onSelect,
    )
}
