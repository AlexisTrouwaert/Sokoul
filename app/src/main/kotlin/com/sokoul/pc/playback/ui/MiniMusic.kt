package com.sokoul.pc.playback.ui
import androidx.compose.foundation.background
import com.sokoul.pc.ui.glassSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label

/** What is playing while one reads or browses. The remote's play and next keys drive it. */
@Composable
fun MiniMusic(deps: AppDeps, modifier: Modifier) {
    val clip = deps.playback.clip ?: return
    val status by (deps.player ?: return).status.collectAsState()
    Row(
        modifier.zIndex(10f).padding(end = 40.dp, bottom = 32.dp).width(560.dp).glassSurface(RoundedCornerShape(28.dp), 28.dp).padding(horizontal = 22.dp, vertical = 14.dp).testTag("mini-music"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (status.paused) Icons.Filled.Pause else Icons.Filled.MusicNote, null, Modifier.size(36.dp), tint = Palette.OnBackground)
        Column {
            Label(clip.title, Type.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Label(L("${clip.slug} · play/pause, next", "${clip.slug} · lecture/pause, suivant").text(), Type.Body, Palette.Secondary, maxLines = 1)
        }
    }
}
