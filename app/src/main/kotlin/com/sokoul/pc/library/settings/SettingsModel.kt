package com.sokoul.pc.library.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.sokoul.pc.data.L

/** The two families of Settings (openspec settings-redesign, D2): what belongs to the person, what belongs to the device. */
enum class SettingsFamily(val label: L) {
    Profile(L("For this profile", "Pour ce profil")),
    Device(L("For this device", "Pour cet appareil")),
}

/**
 * The eight categories, in reading order. [id] is stable (tests, renders, search, focus memory); [legacy] keeps the pane ids
 * the app and the renders used before the redesign working.
 */
enum class SettingsCategory(
    val id: String,
    val family: SettingsFamily,
    val icon: ImageVector,
    val label: L,
    val summary: L,
    val legacy: Set<String> = emptySet(),
) {
    PROFILE("profile", SettingsFamily.Profile, Icons.Filled.Person, L("Profile", "Profil"), L("Who is watching, and how Sokoul starts.", "Qui regarde, et comment Sokoul démarre."), setOf("accounts")),
    PLAYBACK("playback", SettingsFamily.Profile, Icons.Filled.PlayCircle, L("Playback", "Lecture"), L("How a title starts and which version comes first.", "Comment un titre démarre et quelle version passe en premier.")),
    LANGUAGES("languages", SettingsFamily.Profile, Icons.Filled.Subtitles, L("Languages and subtitles", "Langues et sous-titres"), L("Subtitle languages and how they look.", "La langue des sous-titres et leur aspect."), setOf("subtitles")),
    DISPLAY("display", SettingsFamily.Profile, Icons.Filled.Monitor, L("Display", "Affichage"), L("Home rows, menu, accent colour and what moves by itself.", "Rangées de l'accueil, menu, couleur d'accent et ce qui bouge tout seul."), setOf("appearance", "home", "menu")),
    SOURCES("sources", SettingsFamily.Device, Icons.Filled.Storage, L("Content sources", "Sources de contenu"), L("What Sokoul uses to find and play your programmes, on this device.", "Ce que Sokoul utilise pour trouver et lire vos programmes, sur cet appareil.")),
    LIVE_TV("livetv", SettingsFamily.Device, Icons.Filled.LiveTv, L("Live TV", "TV en direct"), L("Channels, guide and subscription.", "Chaînes, guide et abonnement.")),
    INTERFACE_LANGUAGE("language", SettingsFamily.Device, Icons.Filled.Language, L("Interface language", "Langue de l'interface"), L("The language of Sokoul.", "La langue de Sokoul.")),
    TOOLS("tools", SettingsFamily.Device, Icons.Filled.Build, L("Tools and maintenance", "Outils et maintenance"), L("Link tools, a little housekeeping, and credits.", "Outils de liens, un peu de ménage, et les crédits."), setOf("about"));

    companion object {
        /** A current or former pane id; unknown ids open the first category. */
        fun of(id: String?): SettingsCategory = entries.firstOrNull { it.id == id || id in it.legacy } ?: PROFILE
    }
}

/** One place in Settings: a category page, optionally a sub-page inside it. */
data class SettingsDestination(val category: SettingsCategory, val subPage: String? = null)

/**
 * Where the person is in Settings: a stack of destinations (Back pops one level) and, per page, the row that last had the
 * focus, so coming back lands where they left.
 */
class SettingsNavigation(start: SettingsCategory, val available: List<SettingsCategory> = SettingsCategory.entries) {
    private val stack = ArrayDeque(listOf(SettingsDestination(start.takeIf { it in available } ?: available.first())))
    var current by mutableStateOf(stack.last())
        private set
    private val lastRow = mutableMapOf<String, String>()

    /** Shows a category from the column: the stack starts again from it. */
    fun select(category: SettingsCategory) {
        if (category !in available) return
        stack.clear()
        stack.addLast(SettingsDestination(category))
        current = stack.last()
    }

    fun open(subPage: String) {
        stack.addLast(SettingsDestination(current.category, subPage))
        current = stack.last()
    }

    /** One level up. False at the root, where Back leaves Settings. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        current = stack.last()
        return true
    }

    val depth get() = stack.size

    /** "Settings › Languages and subtitles › Audio languages". */
    fun breadcrumb(subPageLabel: (String) -> String): List<String> = buildList {
        add(L("Settings", "Réglages").text())
        add(current.category.label.text())
        current.subPage?.let { add(subPageLabel(it)) }
    }

    fun remember(rowId: String) { lastRow[key()] = rowId }
    fun lastRow(): String? = lastRow[key()]
    private fun key() = current.category.id + "/" + (current.subPage ?: "")
}
