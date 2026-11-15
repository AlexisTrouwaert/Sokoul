package com.sokoul.pc.live

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Canceling the owning selection effect cancels both the quiet period and late resolutions. */
internal suspend fun <T : Any> settledGuidePreview(resolve: suspend () -> T?, play: (T) -> Unit) {
    delay(1_200L)
    val candidate = resolve()
    currentCoroutineContext().ensureActive()
    candidate?.let(play)
}
