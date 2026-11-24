package com.sokoul.pc.startup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.tvFocusable
import kotlin.math.roundToInt

/**
 * A profile's 4-digit code, typed on Sokoul's own pad (the keys of the search keyboard: pills, white under focus) or
 * with the digits of a remote or a keyboard. [check] says whether a code is right; a wrong one shakes the dots and
 * clears them. Back cancels.
 */
@Composable
fun PinPad(title: String, subtitle: String?, check: (String) -> Boolean, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(wrong) {
        if (wrong == 0) return@LaunchedEffect
        for (x in listOf(24f, -20f, 14f, -8f, 0f)) shake.animateTo(x, androidx.compose.animation.core.tween(55))
    }
    fun type(d: Char) {
        if (code.length >= PIN_LENGTH) return
        code += d
        if (code.length == PIN_LENGTH) {
            if (check(code)) onDone(code) else { code = ""; wrong++ }
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    Column(
        Modifier.testTag("pin-pad").onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val digit = DIGIT_KEYS[e.key]
            when {
                digit != null -> { type(digit); true }
                e.key == Key.Backspace || e.key == Key.Delete -> { code = code.dropLast(1); true }
                e.key == Key.Escape || e.key == Key.Back -> { onCancel(); true }
                else -> false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Label(title, Type.RowTitle.copy(fontSize = 48.sp, textAlign = TextAlign.Center), maxLines = 2)
        val note = if (wrong > 0) L("Wrong code. Try again.", "Code incorrect. Réessayez.").text() else subtitle
        note?.let { Label(it, Type.Meta.copy(textAlign = TextAlign.Center), Palette.Secondary, maxLines = 2, modifier = Modifier.testTag("pin-note")) }
        Row(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }.testTag("pin-dots"), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            repeat(PIN_LENGTH) { i ->
                val filled = i < code.length
                Box(Modifier.size(28.dp).clip(CircleShape).background(if (filled) Palette.OnBackground else Color.Transparent).border(2.dp, Palette.OnBackground, CircleShape))
            }
        }
        Spacer(Modifier.height(8.dp))
        // 1 2 3 / 4 5 6 / 7 8 9 / ⌫ 0 ✕, the pad of a phone.
        val rows = listOf("123", "456", "789")
        Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            rows.forEachIndexed { r, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    line.forEachIndexed { c, d -> PadKey(d.toString(), if (r == 0 && c == 0) first else remember { FocusRequester() }) { type(d) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                PadKey("⌫", remember { FocusRequester() }, small = true) { code = code.dropLast(1) }
                PadKey("0", remember { FocusRequester() }) { type('0') }
                PadKey(L("Cancel", "Annuler").text(), remember { FocusRequester() }, small = true, tag = "pin-cancel") { onCancel() }
            }
        }
    }
}

@Composable
private fun PadKey(text: String, requester: FocusRequester, small: Boolean = false, tag: String = "pin-key-$text", onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) Palette.FocusFill else Color(0x14FFFFFF), androidx.compose.animation.core.tween(120))
    val scale by animateFloatAsState(if (focused) 1.1f else 1f)
    Box(
        Modifier.size(KEY_SIZE).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(fill).testTag(tag)
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = Type.Body.copy(
                fontSize = if (small) 22.sp else 38.sp, fontWeight = if (small) FontWeight.SemiBold else FontWeight.Medium,
                color = if (focused) Palette.OnFocus else Color(0xE6FFFFFF), textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}

const val PIN_LENGTH = 4
private val KEY_SIZE = 108.dp
private val KEY_GAP = 22.dp
private val DIGIT_KEYS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)
