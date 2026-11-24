package com.sokoul.pc.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether the app is in front: its window focused and not minimized on the PC, the activity started on Android.
 * Behind another window, nothing that only decorates runs: the screensaver, the hero's trailer and carousel, the live
 * preview. Sokoul PC behind a browser drew its screensaver at 60 frames a second, 21 % of a core (2026-11-24).
 * The film being played is never touched.
 */
object AppFocus {
    val active = MutableStateFlow(true)
}
