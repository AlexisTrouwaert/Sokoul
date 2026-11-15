package com.sokoul.pc.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sokoul.pc.ui.ScaledRoot
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Every panel of the player and of the TV pages, rendered over a picture-like background so the glass can be judged
 * (mode "panels"). Fixtures only: nothing opens a stream or reads a credential.
 */
internal fun renderPanels(out: File) {
    val versions = (0 until 24).map { index ->
        com.sokoul.pc.playback.session.PlayVersion(
            "preview-$index", com.sokoul.pc.playback.session.VersionOrigin.entries[index % 3],
            "One.Piece.S01E01.${if (index % 2 == 0) "MULTi.1080p.WEB-DL.x264" else "VF.720p.WEB"}-GROUP",
            com.sokoul.iptv.domain.AudioLanguage.entries[index % 4], com.sokoul.iptv.domain.VideoQuality.FHD_1080,
            sizeBytes = 1_600_000_000L + index * 90_000_000L,
            readiness = com.sokoul.pc.playback.session.Readiness.Ready,
            announcedAudio = listOf(listOf("FR", "JA"), listOf("FR"), listOf("JA"), emptyList())[index % 4],
            rejected = com.sokoul.pc.playback.session.Doubt.OtherEpisode.takeIf { index % 10 == 9 },
            open = { _, _ -> error("Preview sources never open a stream") },
        )
    }
    val episodes = object : com.sokoul.pc.playback.ui.EpisodeSource {
        override suspend fun seasonCount(tvId: Int) = 3
        override suspend fun episodes(tvId: Int, season: Int) = (1..12).map {
            com.sokoul.iptv.data.net.TmdbEpisode(season, it, listOf("Romance Dawn", "The Great Swordsman", "Morgan versus Luffy", "Luffy's Past", "Fear, Mysterious Power")[it % 5], null, null, 24)
        }
        override suspend fun watched(tvId: Int) = mapOf((1 to 1) to 1f, (1 to 2) to 0.4f)
    }
    val request = com.sokoul.pc.playback.session.PlayRequest("tv", 37854, 1, 2, "One Piece", "S1E2", null)

    val surfaces: List<Pair<String, @Composable BoxScope.() -> Unit>> = listOf(
        "audio" to {
            com.sokoul.pc.playback.ui.TrackMenu("Audio", listOf(1 to "Français", 2 to "Japonais · 5.1", 3 to "Anglais"), 1, {},
                Modifier.align(Alignment.BottomEnd).then(com.sokoul.pc.playback.ui.PlayerPopover))
        },
        "more" to {
            com.sokoul.pc.playback.ui.TrackMenu("Options", listOf(1 to "Stream details", 2 to "Picture format", 3 to "Sleep timer", 4 to "Speed", 5 to "Subtitle style", 6 to "Watch party"), -1, {},
                Modifier.align(Alignment.BottomEnd).then(com.sokoul.pc.playback.ui.PlayerPopover), chevrons = true)
        },
        "format" to {
            com.sokoul.pc.playback.ui.FormatPanel(com.sokoul.desktop.data.VideoFormat.entries.first(), {}, Modifier.align(Alignment.BottomEnd).then(com.sokoul.pc.playback.ui.PlayerPopover))
        },
        "subtitles" to {
            com.sokoul.pc.playback.ui.SubtitlesMenu(listOf(1 to "Track 1 - [French]", 2 to "Track 2 - [English]", 3 to "Track 3 - [Japanese]"), 1, null, {},
                Modifier.align(Alignment.CenterEnd).padding(end = 40.dp))
        },
        "episodes" to {
            com.sokoul.pc.playback.ui.EpisodesPanel(episodes, request, Modifier.align(Alignment.CenterEnd).padding(end = 40.dp)) {}
        },
        "sources" to {
            com.sokoul.pc.playback.ui.SourceBrowserContent(versions, versions[1].key, false, Modifier.align(Alignment.CenterEnd).padding(end = 40.dp), verified = { it.key == versions[1].key })
        },
        "still" to {
            com.sokoul.pc.playback.ui.StillWatchingPrompt({}, {}, androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }, Modifier.align(Alignment.Center))
        },
        "actions" to {
            com.sokoul.pc.ui.TvPanel("Le monde incroyable de Gumball", "Cartoon Network · 17:20 – 17:30", Modifier.align(Alignment.Center),
                width = com.sokoul.pc.ui.TvPanelSize.Standard.width, height = com.sokoul.pc.ui.TvPanelSize.Standard.height) {
                val first = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                com.sokoul.pc.ui.TvActionRow("Regarder", requester = first) {}
                com.sokoul.pc.ui.TvActionRow("Ajouter aux favoris", "Dans le groupe Jeunesse") {}
                com.sokoul.pc.ui.TvSectionLabel("Enregistrement")
                com.sokoul.pc.ui.TvActionRow("Enregistrer ce programme", trailing = "30 min") {}
                com.sokoul.pc.ui.TvActionRow("Me le rappeler", selected = true) {}
                com.sokoul.pc.ui.TvActionRow("Masquer la chaîne", destructive = true) {}
            }
        },
    )
    listOf("fr", "en").forEach { language ->
        com.sokoul.pc.i18n.Strings.applyLanguage(language)
        listOf(1920 to 1080, 1280 to 720).forEach { (width, height) ->
            surfaces.forEach { (name, panel) ->
                val scene = ImageComposeScene(width, height, Density(1f)) {
                    ScaledRoot { Box(Modifier.fillMaxSize()) { Backdrop(); panel() } }
                }
                var time = 0L
                repeat(10) { scene.render(time); time += 100_000_000L; Thread.sleep(10) }
                File(out, "panel-$name-$language-${width}x$height.png").writeBytes(scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("panel-$name-$language-${width}x$height")
                scene.close()
            }
        }
    }
}

/** A colourful still in place of a film frame, darkened at the bottom like the player's controls. */
@Composable
private fun Backdrop() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1B4F72), Color(0xFF6C3483), Color(0xFFD35400), Color(0xFFF4D03F)))))
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x66FFFFFF), Color.Transparent), radius = 900f)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))))
}
