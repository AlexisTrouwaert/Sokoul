package com.sokoul.pc.playback.ui
import com.sokoul.pc.playback.session.PlayRequest
import androidx.compose.animation.core.Animatable
import com.sokoul.pc.ui.glassSurface
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.iptv.data.net.TmdbEpisode
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.PillButton
import kotlinx.coroutines.delay

private const val SHOWN_MS = 1_100L
private const val MAX_VOLUME = 150f

/** Which episode comes next. Pure and tested. */
object EpisodeOrder {
    /** The next one of [season], else null: the caller then looks at the first of the following season. */
    fun nextIn(season: List<TmdbEpisode>, episode: Int): TmdbEpisode? =
        season.filter { it.episode > episode }.minByOrNull { it.episode }

    /** At the very end of an episode, or close to it, the card offering the next one is shown. */
    fun cardShown(timeMs: Long, lengthMs: Long): Boolean = lengthMs > MIN_LENGTH_MS && lengthMs - timeMs in 1..CARD_BEFORE_END_MS

    /** Seconds before the next episode starts by itself, null when it will not (setting off, card dismissed). */
    fun countdown(shownForMs: Long, autoNext: Boolean, dismissed: Boolean): Int? =
        if (!autoNext || dismissed) null else ((AUTO_NEXT_MS - shownForMs + 999) / 1000).toInt().coerceAtLeast(0)

    const val AUTO_NEXT_MS = 10_000L
    private const val MIN_LENGTH_MS = 120_000L
    private const val CARD_BEFORE_END_MS = 40_000L
}

/** "23:14": when the film will be over if it goes on now, at the speed it plays. */
fun endsAt(nowMs: Long, timeMs: Long, lengthMs: Long, rate: Float): Long =
    nowMs + ((lengthMs - timeMs).coerceAtLeast(0) / rate.coerceAtLeast(0.25f)).toLong()

/** The level of the sound, seen only while it changes. Only opacity moves. */
@Composable
internal fun VolumeBubble(count: Int, volume: Int, muted: Boolean, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(count) {
        if (count > 0) {
            alpha.snapTo(1f)
            delay(SHOWN_MS)
            alpha.animateTo(0f, tween(300))
        }
    }
    Column(
        modifier.graphicsLayer { this.alpha = alpha.value }.width(84.dp).glassSurface(RoundedCornerShape(42.dp), 42.dp)
            .padding(vertical = 20.dp).testTag("player-volume"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(8.dp).height(240.dp).clip(RoundedCornerShape(4.dp)).background(Palette.PillFill), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(if (muted) 0f else (volume / MAX_VOLUME).coerceIn(0f, 1f)).background(Color.White))
        }
        Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(34.dp), tint = Color.White)
        Label(if (muted) "0" else "$volume", Type.Body, maxLines = 1)
    }
}

/** "+30 s" on the side the picture jumps to. */
@Composable
internal fun SeekFlash(count: Int, text: String, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(count) {
        if (count > 0) {
            alpha.snapTo(1f)
            alpha.animateTo(0f, tween(800))
        }
    }
    Box(
        modifier.graphicsLayer { this.alpha = alpha.value }.glassSurface(RoundedCornerShape(50), 40.dp).padding(horizontal = 30.dp, vertical = 16.dp).testTag("player-seek-flash"),
    ) {
        Label(text, Type.Button, maxLines = 1)
    }
}

/** The next episode, near the end of this one. */
@Composable
internal fun NextEpisodeCard(
    next: PlayRequest,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    requester: androidx.compose.ui.focus.FocusRequester,
    secondsLeft: Int? = null,
    onCancel: (() -> Unit)? = null,
) {
    Row(
        modifier.glassSurface(RoundedCornerShape(36.dp), 36.dp).padding(horizontal = 28.dp, vertical = 18.dp).testTag("player-next-episode"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column {
            val heading = secondsLeft?.let { com.sokoul.pc.data.L("Next episode in $it s", "Épisode suivant dans $it s").text() } ?: tr(Text.NextEpisode)
            Label(heading, Type.Body, Palette.Secondary, maxLines = 1, modifier = Modifier.testTag("player-next-heading"))
            Label(next.kicker.orEmpty(), Type.Meta, maxLines = 1, modifier = Modifier.width(520.dp))
        }
        PillButton(tr(Text.Watch), onPlay, Modifier.testTag("player-next-play"), requester)
        if (secondsLeft != null && onCancel != null) {
            PillButton(com.sokoul.pc.data.L("Cancel", "Annuler").text(), onCancel, Modifier.testTag("player-next-cancel"))
        }
    }
}
