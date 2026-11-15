package com.sokoul.pc.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.data.L
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.theme.InterDisplay
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label

/** Editorial content and video have independent identities while the viewer browses. */
@Composable
internal fun FormulerGuideHeader(
    channel: Channel,
    program: Program?,
    now: Long,
    previewName: String?,
    modifier: Modifier = Modifier,
    preview: @Composable BoxScope.() -> Unit,
) {
    val style = FormulerGuideStyle
    val live = program != null && program.start <= now && program.end > now
    Row(modifier.fillMaxWidth().height(style.headerHeight).padding(horizontal = 80.dp, vertical = 12.dp)
        .testTag("tv-editorial-header"), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelLogo(channel.name, channel.logo, Modifier.size(72.dp, 36.dp), guideId = channel.epgKey)
                Label(TvCatalog.shortLabel(channel.name), Type.Body.copy(fontWeight = FontWeight.SemiBold), style.secondary, maxLines = 1)
                if (live) Label(L("LIVE", "DIRECT").text(), Type.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), style.live, maxLines = 1)
            }
            Box(Modifier.height(74.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Label(program?.title ?: L("Programme information unavailable", "Programme non communiqué").text(),
                    Type.RowTitle.copy(fontFamily = InterDisplay, fontSize = 32.sp, lineHeight = 36.sp), style.ink,
                    Modifier.testTag("tv-hero-title"), maxLines = 2)
            }
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (program != null) {
                    Label("${clock(program.start)} – ${clock(program.end)}", Type.Body, style.secondary, maxLines = 1)
                    if (live) {
                        val fraction = ((now - program.start).toFloat() / (program.end - program.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(Modifier.width(180.dp).height(3.dp).background(style.line)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(style.ink))
                        }
                        Label(L("${((program.end - now) / 60_000).coerceAtLeast(1)} min left", "Encore ${((program.end - now) / 60_000).coerceAtLeast(1)} min").text(), Type.Body.copy(fontSize = 18.sp), style.secondary, maxLines = 1)
                    }
                } else Label(L("The channel remains available to watch.", "La chaîne reste disponible à la lecture.").text(), Type.Body, style.secondary, maxLines = 1)
            }
            program?.description?.takeIf { it.isNotBlank() }?.let {
                Label(it, Type.Body.copy(fontSize = 20.sp, lineHeight = 25.sp), style.secondary, maxLines = 2)
            }
        }
        Box(Modifier.size(352.dp, 198.dp).clip(RoundedCornerShape(12.dp)).background(style.surface)
            .border(1.dp, style.line, RoundedCornerShape(12.dp)).testTag("tv-preview-frame")) {
            preview()
            previewName?.let {
                Label(it, Type.Body.copy(fontSize = 16.sp), style.ink,
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(style.background.copy(alpha = .90f)).padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
            }
        }
    }
}
