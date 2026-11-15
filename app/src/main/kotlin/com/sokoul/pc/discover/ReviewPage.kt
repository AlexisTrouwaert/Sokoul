package com.sokoul.pc.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.discovery.Persona
import com.sokoul.iptv.discovery.TitleTime
import com.sokoul.iptv.discovery.YearSummary
import com.sokoul.pc.anime.Choices
import com.sokoul.pc.data.DiscoverData
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.CardMetrics
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.CardRow
import com.sokoul.pc.ui.FocusCard
import com.sokoul.pc.ui.Label
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** The year in review, after Movix's "Wrapped": figures, profile, favourite titles and genres, eras of the year. */
@Composable
internal fun ReviewPage(data: DiscoverData, onOpenTitle: (List<TmdbItem>, Int, CardOrigin?) -> Unit) {
    val now = LocalDate.now().year
    var year by remember { mutableIntStateOf(now) }
    val state by produceState<Pair<Int, YearSummary?>?>(null, year) {
        value = year to runCatching { data.review(year) }.getOrNull()
    }
    val summary = state?.takeIf { it.first == year }?.second
    val loading = state?.first != year
    LazyColumn(Modifier.fillMaxSize().testTag("review"), contentPadding = PaddingValues(top = 110.dp, bottom = 80.dp)) {
        item {
            Column(Modifier.padding(horizontal = Metrics.PageMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label(L("Your year in review", "Votre année en revue").text(), Type.Meta.copy(fontWeight = FontWeight.SemiBold), Palette.Secondary)
                Label("$year", Type.RowTitle.copy(fontSize = Type.RowTitle.fontSize * 2f))
                Choices(listOf(now to L("This year", "Cette année"), now - 1 to L("Last year", "L'an dernier")), year, "review-year") { year = it }
            }
        }
        when {
            loading -> item { Label(L("Counting…", "Calcul en cours…").text(), Type.Body, Palette.Secondary, Modifier.padding(Metrics.PageMargin)) }
            summary == null -> item {
                Label(L("Nothing watched this year yet.", "Rien de regardé cette année pour l'instant.").text(), Type.Body, Palette.Secondary, Modifier.padding(Metrics.PageMargin))
            }
            else -> {
                item { Figures(summary) }
                item { PersonaCard(summary.persona) }
                if (summary.topTitles.isNotEmpty()) item {
                    val items = summary.topTitles.map { it.asItem() }
                    CardRow(L("Your most watched", "Vos plus regardés").text(), summary.topTitles, key = { "${it.kind}-${it.id}" }, testTag = "review-top") { i, t ->
                        TitleTile(t, i + 1) { origin -> onOpenTitle(items, i, origin) }
                    }
                }
                if (summary.topGenres.isNotEmpty()) item {
                    Section(L("Your genres", "Vos genres").text()) {
                        summary.topGenres.forEach { g -> Bar(g.name, g.percent, "${hours(g.minutes)}") }
                    }
                }
                if (summary.eras.isNotEmpty()) item {
                    Section(L("Your eras", "Vos périodes").text()) {
                        summary.eras.forEach { e ->
                            val label = if (e.kind == "format") kindName(e.label) else e.label
                            val months = if (e.fromMonth == e.toMonth) month(e.fromMonth) else "${month(e.fromMonth)} – ${month(e.toMonth)}"
                            Label("$months · $label (${e.share} %) · ${e.title.title}", Type.Body, Palette.OnBackground, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Figures(s: YearSummary) {
    Row(Modifier.padding(horizontal = Metrics.PageMargin, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Figure(hours(s.totalMinutes), L("watched", "regardées"))
        Figure("${s.uniqueTitles}", L("titles", "titres"))
        Figure("${s.activeDays}", L("days in front of the screen", "jours devant l'écran"))
        Figure("${s.longestStreak}", L("days in a row, at best", "jours d'affilée, au mieux"))
        Figure("${s.peakHour} h", L("your favourite hour", "votre heure préférée"))
        Figure(month(s.busiestMonth), L("your busiest month", "votre mois le plus chargé"))
    }
}

@Composable
private fun Figure(value: String, label: L) {
    Column(Modifier.width(250.dp).clip(RoundedCornerShape(18.dp)).background(Palette.ButtonFill).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(value, Type.RowTitle.copy(fontSize = Type.RowTitle.fontSize * 1.3f, fontWeight = FontWeight.Bold), maxLines = 1)
        Label(label.text(), Type.Meta, Palette.BarText, maxLines = 2)
    }
}

private val PERSONAS = mapOf(
    Persona.NightOwl to (L("The Night Owl", "L'Oiseau de nuit") to L("Your best sessions start after 10 pm.", "Vos meilleures séances commencent après 22 h.")),
    Persona.EarlyBird to (L("The Early Bird", "Le Lève-tôt") to L("An episode before coffee.", "Un épisode avant le café.")),
    Persona.Cinephile to (L("The Cinephile", "Le Cinéphile") to L("Films are your religion.", "Les films, c'est votre religion.")),
    Persona.Binger to (L("The Binge-Watcher", "Le Binge-Watcher") to L("\"One more episode\" is your motto.", "« Encore un épisode » est votre devise.")),
    Persona.SeriesAddict to (L("The Series Addict", "L'Accro aux séries") to L("Series are your second family.", "Les séries sont votre deuxième famille.")),
    Persona.StreakMachine to (L("The Machine", "La Machine") to L("Not a single day off.", "Aucun jour de repos.")),
    Persona.Explorer to (L("The Explorer", "L'Explorateur") to L("Always hunting for the next gem.", "Toujours à la recherche de la prochaine pépite.")),
    Persona.Omnivore to (L("The All-Rounder", "Le Touche-à-tout") to L("Films and series: you take it all.", "Films et séries : vous prenez tout.")),
    Persona.MovieLover to (L("The Movie Lover", "L'Amoureux du cinéma") to L("Nothing beats a good film.", "Rien ne vaut un bon film.")),
    Persona.SeriesFan to (L("The Series Fan", "Le Fan de séries") to L("Series set the pace of your days.", "Les séries rythment vos journées.")),
)

@Composable
private fun PersonaCard(p: Persona) {
    val (title, line) = PERSONAS.getValue(p)
    Box(
        Modifier.padding(horizontal = Metrics.PageMargin, vertical = 8.dp).fillMaxWidth().height(200.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5B2A86), Color(0xFF1F3B73)))).testTag("review-persona"),
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(L("Your viewer profile", "Votre profil de spectateur").text(), Type.Meta, Palette.BarText)
            Label(title.text(), Type.RowTitle.copy(fontSize = Type.RowTitle.fontSize * 1.4f, fontWeight = FontWeight.Bold), maxLines = 1)
            Label(line.text(), Type.Body, Palette.OnBackground, maxLines = 1)
        }
    }
}

@Composable
private fun TitleTile(t: TitleTime, rank: Int, onClick: (CardOrigin) -> Unit) {
    FocusCard(CardMetrics.EpisodeWidth, 200.dp, onClick) { _ ->
        Art(t.image, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color(0x22000000), 1f to Color(0xF2000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Label("#$rank · ${hours(t.minutes)}", Type.Body.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Label(t.title, Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = Metrics.PageMargin, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(title, Type.RowTitle, maxLines = 1)
        content()
    }
}

@Composable
private fun Bar(name: String, percent: Int, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Label(name, Type.Body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(260.dp), maxLines = 1)
        Box(Modifier.width(700.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.ButtonFill)) {
            Box(Modifier.fillMaxWidth(percent.coerceIn(2, 100) / 100f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.OnBackground))
        }
        Label("$percent % · $detail", Type.Meta, Palette.BarText, maxLines = 1)
    }
}

private fun TitleTime.asItem() = TmdbItem(id, title, null, null, image, image, null, null, mediaType = kind)

private fun hours(minutes: Long): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h"

private fun month(m: Int): String = Month.of(m).getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() }

private fun kindName(kind: String): String = if (kind == "tv") L("Series", "Séries").text() else L("Films", "Films").text()
