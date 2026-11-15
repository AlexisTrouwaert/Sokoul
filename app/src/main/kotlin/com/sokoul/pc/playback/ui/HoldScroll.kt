package com.sokoul.pc.playback.ui
/**
 * Holding Up or Down in a long list of channels: one row per key at first, then, once held for half a second, three
 * rows per repeat, until the key is let go. Pure, like PlayerNav.decide: the panel only applies what it says.
 */
class HoldScroll(private val clock: () -> Long = System::currentTimeMillis) {
    /** When the key went down; null while no key is held. */
    private var since: Long? = null

    /** Rows to move for a key going down (a first press, or a repeat of a held key). */
    fun down(): Int {
        val now = clock()
        val start = since ?: run {
            since = now
            return 1
        }
        return if (now - start >= HOLD_MS) FAST_STEP else 1
    }

    /** The key is let go: the next press starts slow again. */
    fun up() {
        since = null
    }

    companion object {
        const val HOLD_MS = 500L
        const val FAST_STEP = 3
    }
}
