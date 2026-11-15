package com.sokoul.pc.title

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import com.sokoul.pc.theme.CardMetrics
import com.sokoul.pc.ui.RowHeading
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.iptv.data.net.TmdbInfo
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.iptv.domain.HdrFormat
import com.sokoul.iptv.domain.VideoQuality
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.data.kind
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label

/** What can really be played, read from the versions found. Nothing here is guessed from the film itself. */
data class PlayFacts(val image: List<String>, val audio: List<String>, val languages: List<String>, val sources: Int)

object ExperienceLogic {
    private val AUDIO = listOf(
        Regex("(?i)atmos") to "Dolby Atmos",
        Regex("(?i)truehd") to "TrueHD",
        Regex("(?i)dts[-. ]?(hd|x)") to "DTS-HD",
        Regex("(?i)\\b7[. ]1\\b") to "7.1",
        Regex("(?i)\\b5[. ]1\\b") to "5.1",
    )

    fun of(versions: List<PlayVersion>): PlayFacts {
        val best = when {
            versions.any { it.quality == VideoQuality.UHD_4K } -> "4K UHD"
            versions.any { it.quality == VideoQuality.FHD_1080 } -> "1080p"
            versions.any { it.quality == VideoQuality.HD_720 } -> "720p"
            else -> null
        }
        val image = listOfNotNull(
            best,
            "Dolby Vision".takeIf { versions.any { it.hdr == HdrFormat.DOLBY_VISION } },
            "HDR10".takeIf { versions.any { it.hdr == HdrFormat.HDR10 } },
        )
        val audio = AUDIO.filter { (r, _) -> versions.any { r.containsMatchIn(it.name) } }.map { it.second }.take(3)
        val languages = AudioLanguage.entries.filter { l -> versions.any { it.language == l } }.map { it.name }
        return PlayFacts(image, audio, languages, versions.size)
    }
}

/** "The experience": the work and what can be played of it here, as tiles. Only what is known is shown. */
@Composable
fun ExperienceBlock(deps: AppDeps, item: TmdbItem, info: TmdbInfo?, extras: com.sokoul.pc.data.TitleExtras) {
    val series = item.kind() == "tv"
    val facts by produceState<PlayFacts?>(null, item.id) {
        val request = PlayRequest(item.kind(), item.id, if (series) 1 else null, if (series) 1 else null, item.title, item.title, item.backdrop)
        runCatching { deps.sources.find(request, false).collect { u -> value = ExperienceLogic.of(u.versions).also { deps.experienceReport = it.toString() } } }
    }
    val f = facts
    val animated = ANIMATION_GENRE in info?.genreIds.orEmpty()
    val o = extras.omdb
    val tiles = listOfNotNull(
        Tile(if (animated) Icons.Filled.Animation else Icons.Filled.Movie, L("TYPE", "TYPE"), if (animated) L("Animation", "Animation").text() else L("Live action", "Prises de vues réelles").text()),
        info?.director?.takeIf { it.isNotBlank() }?.let { Tile(Icons.Filled.Person, if (series) L("CREATED BY", "CRÉÉ PAR") else L("DIRECTED BY", "RÉALISÉ PAR"), it) },
        f?.image?.takeIf { it.isNotEmpty() }?.let { Tile(Icons.Filled.HighQuality, L("PICTURE", "IMAGE"), it.joinToString(" · ")) },
        f?.audio?.takeIf { it.isNotEmpty() }?.let { Tile(Icons.Filled.SurroundSound, L("SOUND", "AUDIO"), it.joinToString(" · ")) },
        f?.languages?.takeIf { it.isNotEmpty() }?.let { Tile(Icons.Filled.Translate, L("LANGUAGES", "LANGUES"), it.joinToString(" · ")) },
        f?.sources?.takeIf { it > 0 }?.let { n -> Tile(Icons.Filled.Dns, L("AVAILABLE", "DISPONIBLE"), "$n source" + if (n > 1) "s" else "") },
        (o?.oscarsWon?.takeIf { it > 0 }?.let { "$it Oscar" + if (it > 1) "s" else "" } ?: o?.awardsWon?.takeIf { it > 0 }?.let { L("$it awards", "$it récompenses").text() })?.let { Tile(Icons.Filled.EmojiEvents, L("AWARDS", "RÉCOMPENSES"), it) },
    )
    Column(Modifier.fillMaxWidth().testTag("title-experience"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        RowHeading(L("The experience", "L'expérience").text(), false)
        Row(Modifier.fillMaxWidth().padding(horizontal = Metrics.PageMargin), horizontalArrangement = Arrangement.spacedBy(CardMetrics.Gap)) {
            tiles.forEach { t ->
                Column(
                    Modifier.weight(1f).height(TILE_H).clip(RoundedCornerShape(CardMetrics.Corner)).background(Palette.ButtonFill).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(t.icon, null, Modifier.size(38.dp), tint = Palette.OnBackground)
                    Spacer(Modifier.weight(1f))
                    Label(t.label.text(), Type.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp), Palette.Secondary, maxLines = 1)
                    Label(t.value, Type.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                }
            }
        }
    }
}

private class Tile(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: L, val value: String)

private val TILE_H = 210.dp
private const val ANIMATION_GENRE = 16
