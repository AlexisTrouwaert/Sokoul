package com.sokoul.pc.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Two-finger pinch on a precision touchpad. Windows delivers it as Ctrl + wheel, so the same gesture also works with
 * Ctrl + mouse wheel. Spreading the fingers opens what has focus, pinching them goes back.
 */
class PinchActions {
    /** What spreading activates: the focused card, or the top screen's own action when it sets one. */
    var focusedCard: (() -> Unit)? = null
    var screenIn: (() -> Unit)? = null
    var screenOut: (() -> Unit)? = null
    /** Fallback for pinching out when no screen claims it. */
    var back: (() -> Unit)? = null

    private var accumulated = 0f
    private var lastGestureAt = 0L
    private var lastEventAt = 0L

    /** [delta] follows the wheel: negative when the wheel goes up, which is what spreading the fingers sends. */
    fun onDelta(delta: Float, now: Long = System.currentTimeMillis()) {
        if (now - lastEventAt > RESET_AFTER_MS) accumulated = 0f
        lastEventAt = now
        if (now - lastGestureAt < COOLDOWN_MS) return
        accumulated += delta
        when {
            accumulated <= -THRESHOLD -> fire(now) { (screenIn ?: focusedCard)?.invoke() }
            accumulated >= THRESHOLD -> fire(now) { (screenOut ?: back)?.invoke() }
        }
    }

    private inline fun fire(now: Long, action: () -> Unit) {
        accumulated = 0f
        lastGestureAt = now
        action()
    }

    private companion object {
        const val THRESHOLD = 1.5f
        const val COOLDOWN_MS = 450L
        const val RESET_AFTER_MS = 300L
    }
}

val LocalPinch = staticCompositionLocalOf { PinchActions() }
