package com.sokoul.pc.live

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.tvFocusable

/** Explicitly opened by its caller: no IME or focus-triggered editor, on either TV or physical keyboards. */
@Composable
internal fun TvTextKeyboard(
    text: String, onChange: (String) -> Unit, onDone: () -> Unit,
    modifier: Modifier = Modifier, limit: Int = 80,
    doneLabel: String = L("Show results", "Afficher les résultats").text(),
) {
    val first = remember { FocusRequester() }
    var accents by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // A panel opening over another may disable the previous focus owner in the same frame; a single early
    // request can then be dropped and leave the remote with nothing to move. Retry briefly until a key holds focus.
    LaunchedEffect(Unit) {
        repeat(6) { attempt ->
            if (focused) return@LaunchedEffect
            if (attempt == 0) withFrameNanos { } else kotlinx.coroutines.delay(80)
            runCatching { first.requestFocus() }
        }
    }
    // Fast typing delivers several keys before the caller recomposes with the new text, so edits build on
    // the latest value sent, not on the possibly stale parameter. A caller value that was never sent here
    // (an external reset or prefill) replaces it.
    val current = remember { mutableStateOf(text) }
    val pending = remember { ArrayDeque<String>() }
    when {
        text == current.value -> pending.clear()
        text in pending -> Unit
        else -> { current.value = text; pending.clear() }
    }
    fun change(value: String) { current.value = value; pending.addLast(value); if (pending.size > 64) pending.removeFirst(); onChange(value) }
    fun append(value: String) = change((current.value + value).take(limit))
    Column(modifier.onFocusChanged { focused = it.hasFocus }.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val char = event.utf16CodePoint.toChar()
        when {
            event.key == Key.Backspace -> { change(current.value.dropLast(1)); true }
            event.key !in listOf(Key.Enter, Key.NumPadEnter) && (char.isLetterOrDigit() || char == ' ' || char in "'-&.") -> { append(char.toString()); true }
            else -> false
        }
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(current.value.ifBlank { L("Type with your remote", "Saisissez avec la télécommande").text() },
            Type.Meta, if (current.value.isBlank()) FormulerGuideStyle.secondary else FormulerGuideStyle.ink,
            Modifier.fillMaxWidth().height(64.dp).padding(8.dp).testTag("tv-keyboard-value"), maxLines = 2)
        val letters = if (accents) "ÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ-'&.0123456789" else "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        letters.toList().chunked(9).forEachIndexed { row, keys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEachIndexed { index, letter ->
                    TvKeyboardKey(letter.toString(), Modifier.weight(1f), if (row == 0 && index == 0) first else remember { FocusRequester() }) {
                        append(letter.lowercaseChar().toString())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TvKeyboardKey(if (accents) "ABC" else "É À &", Modifier.weight(1f)) { accents = !accents }
            TvKeyboardKey(L("Space", "Espace").text(), Modifier.weight(1.5f)) { append(" ") }
            TvKeyboardKey("⌫", Modifier.weight(1f).testTag("tv-keyboard-delete")) { change(current.value.dropLast(1)) }
        }
        TvKeyboardKey(doneLabel, Modifier.fillMaxWidth().testTag("tv-keyboard-done"), onClick = onDone)
    }
}

@Composable
private fun TvKeyboardKey(label: String, modifier: Modifier, requester: FocusRequester = remember { FocusRequester() }, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (focused) FormulerGuideStyle.selected else FormulerGuideStyle.surface, tween(110))
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.height(64.dp).clip(shape).background(fill)
        .border(if (focused) 2.dp else 1.dp, if (focused) FormulerGuideStyle.ink else FormulerGuideStyle.secondary.copy(alpha = .15f), shape)
        .tvFocusable(requester = requester, onFocusChanged = { focused = it }, onClick = onClick), contentAlignment = Alignment.Center) {
        Label(label, Type.Body.copy(fontSize = 24.sp), FormulerGuideStyle.ink, maxLines = 1)
    }
}
