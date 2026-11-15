package com.sokoul.pc.playback.session
import com.sokoul.desktop.player.DesktopPlayer
import com.sokoul.desktop.player.PlayerStatus
import com.sokoul.desktop.player.StreamRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** The player seen by this package, so playback logic can be tested without libVLC. */
interface PlayerEngine {
    val status: StateFlow<PlayerStatus>
    fun play(request: StreamRequest)
    fun stop()

    /** Measures of the stream, once a second. */
    val stats: StateFlow<com.sokoul.desktop.player.StreamSample?> get() = NO_STATS

    /** Opens a stream with the sound off, ready to become the real thing without reopening. */
    fun preview(request: StreamRequest) = play(request)

    fun endPreview() {}

    fun enterPreview() {}

    /** Returns once the engine can open a stream. Time spent loading it counts against no stream. */
    suspend fun awaitLoaded() {}

    companion object {
        /** [PlayerStatus.error] when libVLC could not load at all. */
        const val MISSING = "missing"
        private val sessions = AtomicLong()
        private val NO_STATS = kotlinx.coroutines.flow.MutableStateFlow<com.sokoul.desktop.player.StreamSample?>(null)

        /**
         * A number for each stream opened. A status only counts for the stream carrying its number: the engine works on
         * its own thread, so the status right after a `play` still describes the previous stream.
         */
        fun nextSession(): Long = sessions.incrementAndGet()
    }
}

/**
 * libVLC is slow to load and its stop can block: commands run in order on their own thread, never on the interface's,
 * and simply wait until the player exists. A stop is over before the next stream opens.
 */
class VlcEngine(private val player: java.util.concurrent.CompletableFuture<DesktopPlayer?>, scope: CoroutineScope) : PlayerEngine {
    private val commands = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sokoul-player").apply { isDaemon = true } }
    private val state = kotlinx.coroutines.flow.MutableStateFlow(PlayerStatus())
    override val status: StateFlow<PlayerStatus> = state
    private val measures = kotlinx.coroutines.flow.MutableStateFlow<com.sokoul.desktop.player.StreamSample?>(null)
    override val stats: StateFlow<com.sokoul.desktop.player.StreamSample?> = measures

    init {
        scope.launch(Dispatchers.IO) {
            val p = kotlinx.coroutines.withContext(Dispatchers.IO) { player.get() }
            if (p == null) {
                state.value = PlayerStatus(error = PlayerEngine.MISSING)
            } else {
                launch { p.stats.collect { measures.value = it } }
                p.status.collect { state.value = it }
            }
        }
    }

    override suspend fun awaitLoaded() {
        while (!player.isDone) kotlinx.coroutines.delay(LOAD_POLL_MS)
    }

    override fun play(request: StreamRequest) {
        commands.execute {
            val asked = System.currentTimeMillis()
            player.get()?.play(request)
            com.sokoul.pc.data.PcLog.event("engine open took ${System.currentTimeMillis() - asked}ms, session ${request.session}")
        }
    }
    override fun preview(request: StreamRequest) { commands.execute { player.get()?.preview(request) } }
    override fun endPreview() { commands.execute { player.get()?.endPreview() } }
    override fun enterPreview() { commands.execute { player.get()?.enterPreview() } }

    override fun stop() {
        commands.execute {
            val asked = System.currentTimeMillis()
            player.get()?.stop()
            com.sokoul.pc.data.PcLog.event("engine stop took ${System.currentTimeMillis() - asked}ms")
        }
    }

    private companion object {
        const val LOAD_POLL_MS = 100L
    }
}
