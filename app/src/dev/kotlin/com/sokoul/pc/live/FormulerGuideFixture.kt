package com.sokoul.pc.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label

/** Offline render fixture using the production header and Canvas drawing path. */
@Composable
internal fun FormulerGuideFixture(scenario: String) {
    val now = 1_800_000_000_000L
    val hour = 3_600_000L
    val left = now - 20 * 60_000L
    val channels = remember { (0..59).map { i -> Channel(0, "sample-$i", i + 1,
        listOf("ARTE", "France 2", "France 3", "TV5MONDE", "Culture", "Découverte", "Cinéma", "Sport")[i % 8],
        null, "sample", null, "sample-$i", null, null, null, i) } }
    val title = when (scenario) {
        "long" -> L("Across the world: an extraordinary journey through landscapes, people and traditions", "À travers le monde : un voyage extraordinaire au cœur des paysages, des peuples et des traditions").text()
        "short" -> L("News in five minutes", "Le journal en cinq minutes").text()
        else -> L("A night at the opera", "Une soirée à l’opéra").text()
    }
    val description = L("A journey behind the scenes with the artists who bring the performance to life. Meet the orchestra and explore the stage.",
        "Une découverte des coulisses aux côtés des artistes qui donnent vie au spectacle. Rencontrez l’orchestre et explorez la scène.").text()
    val schedules = channels.mapIndexed { i, c ->
        c.id to if (scenario == "empty" || i == 5) emptyList() else {
            val start = now - (if (scenario == "short") 2 else 20 + i % 4 * 5) * 60_000L
            val end = start + (if (scenario == "short") 5 else 55 + i % 3 * 15) * 60_000L
            listOf(Program(c.id, start - hour, start, L("Earlier today", "Plus tôt").text(), null),
                Program(c.id, start, end, if (i == 0) title else L("Evening programme", "Programme de soirée").text(), description),
                Program(c.id, end, end + hour, L("Documentary", "Documentaire").text(), null),
                Program(c.id, end + hour, end + 3 * hour, L("Late cinema", "Cinéma de nuit").text(), null))
        }
    }.toMap()
    val focus = remember { GuideFocus().apply { anchor = now } }
    val text = rememberTextMeasurer()
    val layers = List(12) { rememberGraphicsLayer() }
    val rowLayers = remember(layers) { GuideRowLayers(layers) }
    val density = LocalDensity.current.density
    Column(Modifier.fillMaxSize().background(FormulerGuideStyle.background)) {
        Row(Modifier.height(120.dp).fillMaxWidth().padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(L("Sokoul   ·   TV Guide", "Sokoul   ·   Guide TV").text(), Type.Nav, FormulerGuideStyle.ink)
        }
        FormulerGuideHeader(channels[0], GuideLogic.programAt(schedules[channels[0].id].orEmpty(), now), now, null, onWatch = {}) {
            if (scenario == "preview") {
                // A labelled stand-in frame tests the available-video layout, not real decoding.
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF304A53), Color(0xFF20292E)))), contentAlignment = Alignment.Center) {
                    Label(L("Preview frame · fixture", "Image d’aperçu · démonstration").text(), Type.Body, FormulerGuideStyle.ink)
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label(L("Preview unavailable", "Aperçu indisponible").text(), Type.Body, FormulerGuideStyle.secondary)
            }
        }
        Row(Modifier.height(56.dp).padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            listOf(L("Culture · 60 channels", "Culture · 60 chaînes").text(), L("Now", "Maintenant").text(),
                L("Tonight", "Ce soir").text(), L("Tomorrow", "Demain").text(), L("Search", "Rechercher").text()).forEach {
                Label(it, Type.Body, FormulerGuideStyle.ink)
            }
        }
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            drawGuide(text, channels, { schedules[it].orEmpty() }, { _, _ -> false }, emptySet(), focus,
                emptyMap(), now, true, density, "", 0f, 0f, left, 620f, 80f, 1f, guideSizes(GuideLevel.Guide, true), television = true, rowLayers = rowLayers)
        }
    }
}
