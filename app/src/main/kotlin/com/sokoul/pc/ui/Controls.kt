package com.sokoul.pc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type

/** Pill button: dark with a hairline at rest, off-white with black text and slightly larger with focus. */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    requester: FocusRequester = remember { FocusRequester() },
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) Metrics.ButtonFocusScale else 1f)
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Palette.ButtonFill)
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(Metrics.ButtonHeight)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (focused) Color.Transparent else Palette.Hairline, shape)
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = Type.Button.copy(color = if (focused) Palette.OnFocus else Palette.OnBackground))
    }
}

/** Back arrow in the leading corner of every page that opens over another. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, onTablet: Boolean = false) {
    if (com.sokoul.pc.Platform.IS_TV && !Form.isPhone && !(onTablet && Form.tablet)) return
    var focused by remember { mutableStateOf(false) }
    // A light disc: a dark one vanished on the black of the player, and the tablet's arrow went unseen (2026-11-24).
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color(0x40FFFFFF))
    val touch = Form.tablet || Form.isPhone
    Box(
        modifier
            .size(if (touch) BACK_TOUCH else Metrics.PillIcon)
            .clip(CircleShape)
            .background(fill)
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, com.sokoul.pc.data.L("Back", "Retour").text(), tint = if (focused) Palette.OnFocus else Palette.OnBackground, modifier = Modifier.size(if (touch) 52.dp else 34.dp))
    }
}

/** Under a thumb: the size of the bar's round icons. */
private val BACK_TOUCH = 84.dp

/** Section pill in the top-left corner: chevron, round icon, section name. */
@Composable
fun SectionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    requester: FocusRequester = remember { FocusRequester() },
    onChevron: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.PillFill else Color.Transparent)
    Row(
        modifier
            .height(Metrics.PillHeight)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester)
            .padding(end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.KeyboardArrowLeft, "Back", tint = Palette.OnBackground,
            modifier = Modifier.size(34.dp).let { m ->
                if (onChevron != null) {
                    m.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onChevron).testTag("pill-back")
                } else {
                    m
                }
            },
        )
        RoundIcon(icon, Metrics.PillIcon.value.toInt())
        BasicText(label, style = Type.Pill.copy(color = Palette.OnBackground))
    }
}

@Composable
fun RoundIcon(icon: ImageVector, sizeDp: Int, fill: Color = Palette.PillFill, tint: Color = Palette.OnBackground) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((sizeDp * 0.56f).dp))
    }
}

@Composable
fun Label(text: String, style: TextStyle, color: Color = Palette.OnBackground, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier = modifier, style = style.copy(color = color), maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}
