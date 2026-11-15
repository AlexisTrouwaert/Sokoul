package com.sokoul.pc.playback.session
import com.sokoul.pc.playback.ui.clock
import com.sokoul.iptv.parser.XtAccount
import kotlinx.coroutines.delay

/**
 * Protects the IPTV subscription, which often allows few connections and can block an account that keeps asking after a
 * refusal: leaves the server time to see a closed stream, asks the account before opening, and holds off after a refusal.
 */
class ConnectionGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val gapMs: Long = GAP_MS,
    private val recheckMs: Long = RECHECK_MS,
) {
    @Volatile private var stoppedAt = 0L
    @Volatile private var refusedAt = 0L

    fun noteStopped() {
        stoppedAt = clock()
    }

    fun noteRefusal() {
        refusedAt = clock()
    }

    /** Waits until a stream may open. Throws the reason when it may not. A failed account check never blocks playback. */
    suspend fun clear(account: suspend () -> XtAccount?) {
        val left = if (refusedAt == 0L) 0 else refusedAt + COOLDOWN_MS - clock()
        if (left > 0) throw PlayFailureException(PlayFailure.Cooldown((left + 999) / 1000))
        val wait = stoppedAt + gapMs - clock()
        if (stoppedAt > 0 && wait > 0) delay(wait)
        val first = account() ?: return
        if (!full(first)) return
        // Our own stream, just closed, may still be counted by the server.
        if (stoppedAt > 0 && clock() - stoppedAt < OWN_STREAM_MS) {
            delay(recheckMs)
            val second = account() ?: return
            if (!full(second)) return
            throw full(second, thrown = true)
        }
        throw full(first, thrown = true)
    }

    private fun full(a: XtAccount): Boolean {
        val max = a.maxConnections ?: return false
        val active = a.activeConnections ?: return false
        return max > 0 && active >= max
    }

    private fun full(a: XtAccount, thrown: Boolean): PlayFailureException {
        check(thrown)
        return PlayFailureException(PlayFailure.ConnectionsFull(a.activeConnections ?: 0, a.maxConnections ?: 0))
    }

    companion object {
        const val COOLDOWN_MS = 30_000L
        private const val GAP_MS = 1_500L
        private const val RECHECK_MS = 2_500L
        private const val OWN_STREAM_MS = 15_000L
    }
}
