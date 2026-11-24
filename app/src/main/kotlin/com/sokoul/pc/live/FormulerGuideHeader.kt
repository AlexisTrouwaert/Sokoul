package com.sokoul.pc.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.data.L
import com.sokoul.pc.playback.ui.clock
import com.sokoul.pc.theme.InterDisplay
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Form
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.LocalActiveTab
import com.sokoul.pc.ui.PillButton

/** Editorial content and video have independent identities while the viewer browses. */
@Composable
internal fun FormulerGuideHeader(
    channel: Channel,
    program: Program?,
    now: Long,
    previewName: String?,
    modifier: Modifier = Modifier,
    watchFocus: FocusRequester = remember { FocusRequester() },
    onWatch: () -> Unit,
    preview: @Composable BoxScope.() -> Unit,
) {
    val style = FormulerGuideStyle
    val tab = LocalActiveTab.current
    val live = program != null && program.start <= now && program.end > now
    BoxWithConstraints(modifier.fillMaxWidth().height(style.headerHeight)
        .padding(horizontal = Form.pick(Metrics.PageMargin, 32.dp, 24.dp), vertical = 14.dp)
        .testTag("tv-editorial-header")) {
      val compact = maxWidth < 900.dp
      val previewWidth = minOf(460.dp, maxWidth * 0.35f)
      Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 48.dp),
          verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelLogo(channel.name, channel.logo, Modifier.size(72.dp, 36.dp).testTag("tv-hero-logo"), guideId = channel.epgKey)
                if (program != null) Label(TvCatalog.shortLabel(channel.name), Type.Body.copy(fontWeight = FontWeight.SemiBold), style.secondary,
                    Modifier.weight(1f, fill = false).testTag("tv-hero-name"), maxLines = 1)
                if (live) Label(L("LIVE", "DIRECT").text(), Type.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), style.live, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Label(program?.title ?: TvCatalog.shortLabel(channel.name),
                    Type.RowTitle.copy(fontFamily = InterDisplay, fontSize = if (compact) 32.sp else 48.sp, lineHeight = if (compact) 36.sp else 51.sp), style.ink,
                    Modifier.testTag("tv-hero-title"), maxLines = 2)
            }
            if (program != null) {
                Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Label("${clock(program.start)} – ${clock(program.end)}", Type.Body, style.secondary, maxLines = 1)
                    if (live && !compact) {
                        val fraction = ((now - program.start).toFloat() / (program.end - program.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        Box(Modifier.width(180.dp).height(3.dp).background(style.line)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(style.ink))
                        }
                        Label(L("${((program.end - now) / 60_000).coerceAtLeast(1)} min left", "Encore ${((program.end - now) / 60_000).coerceAtLeast(1)} min").text(), Type.Body.copy(fontSize = 18.sp), style.secondary, maxLines = 1)
                    }
                }
            }
            program?.description?.takeIf { !compact && it.isNotBlank() }?.let {
                Label(it, Type.Body.copy(fontSize = 23.sp, lineHeight = 27.sp), style.secondary, maxLines = 1)
            }
            PillButton(L("Watch", "Regarder").text(), onWatch,
                Modifier.focusProperties { if (tab != null) up = tab }.testTag("tv-watch"), requester = watchFocus)
        }
        val shape = RoundedCornerShape(18.dp)
        Box(Modifier.width(previewWidth).aspectRatio(16f / 9f).clip(shape).background(style.surface)
            .border(1.dp, style.line, shape).testTag("tv-preview-frame")) {
            preview()
            previewName?.let {
                Label(it, Type.Body.copy(fontSize = 16.sp), style.ink,
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(style.background.copy(alpha = .90f)).padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
            }
        }
      }
    }
}
