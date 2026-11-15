@file:Suppress("PackageDirectoryMismatch")

package androidx.compose.ui.input.pointer

import androidx.compose.ui.Modifier

/** The desktop's helper: a pointer on a TV is rare, but the screens use it. */
fun Modifier.onPointerEvent(
    eventType: PointerEventType,
    pass: PointerEventPass = PointerEventPass.Main,
    onEvent: AwaitPointerEventScope.(event: PointerEvent) -> Unit,
): Modifier = pointerInput(eventType, pass, onEvent) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(pass)
            if (event.type == eventType) onEvent(event)
        }
    }
}

val PointerEvent.keyboardModifiers: PointerKeyboardModifiers get() = PointerKeyboardModifiers(0)

