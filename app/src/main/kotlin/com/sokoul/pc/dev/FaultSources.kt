package com.sokoul.pc.dev

import com.sokoul.desktop.player.StreamRequest
import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.iptv.domain.VideoQuality
import com.sokoul.pc.playback.session.PlayFailure
import com.sokoul.pc.playback.session.PlayFailureException
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.pc.playback.session.Readiness
import com.sokoul.pc.playback.session.SearchUpdate
import com.sokoul.pc.playback.session.SourceFinder
import com.sokoul.pc.playback.session.Transfer
import com.sokoul.pc.playback.session.VersionOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Live check of the player's failures, only with SOKOUL_DEBUG and SOKOUL_FAULTS. Puts versions that fail on purpose
 * before the real ones. SOKOUL_FAULTS names them: refused, full, fake, stall, download. Touches no real account.
 */
class FaultSources(private val real: SourceFinder, private val wanted: Set<String> = (System.getenv("SOKOUL_FAULTS") ?: "").split(',').map { it.trim() }.toSet()) : SourceFinder {
    private fun fake(key: String, quality: VideoQuality, open: suspend (Boolean, (Transfer) -> Unit) -> StreamRequest) =
        PlayVersion("fault-$key", VersionOrigin.Debrid, "TEST $key", AudioLanguage.VF, quality, readiness = Readiness.Ready, open = open)

    @Volatile private var latestReal: List<PlayVersion> = emptyList()

    private val faults: List<PlayVersion> = buildList {
        if ("refused" in wanted) add(fake("refused", VideoQuality.UHD_4K) { _, _ -> delay(STEP_MS); throw PlayFailureException(PlayFailure.Refused(403)) })
        if ("full" in wanted) add(fake("full", VideoQuality.UHD_4K) { _, _ -> delay(STEP_MS); throw PlayFailureException(PlayFailure.ConnectionsFull(2, 2)) })
        if ("fake" in wanted) add(fake("fake", VideoQuality.UHD_4K) { _, _ -> delay(STEP_MS); throw PlayFailureException(PlayFailure.FakeFile) })
        // An address that never answers: the first image never comes.
        if ("stall" in wanted) add(fake("stall", VideoQuality.UHD_4K) { _, _ -> StreamRequest("http://10.255.255.1:9/x.mkv", null, null, "stall", live = false) })
        if ("download" in wanted) {
            add(
                fake("download", VideoQuality.UHD_4K) { _, progress ->
                    for (percent in 0..100 step 10) {
                        progress(Transfer(percent, speedBps = 8_000_000, seeders = 12, bytes = 8_000_000_000))
                        delay(STEP_MS)
                    }
                    throw PlayFailureException(PlayFailure.NotReady(null))
                },
            )
        }
    }

    /** The first real version that opens, seen as something else. */
    private suspend fun firstReal(): StreamRequest {
        val tried = mutableSetOf<String>()
        // The real services answer one after another, after the fake versions are already on the list.
        repeat(REAL_WAIT_SECONDS) {
            latestReal.filter { it.origin == VersionOrigin.Debrid && it.readiness in setOf(Readiness.Ready, Readiness.Likely) && tried.add(it.key) }.forEach { v ->
                try {
                    return v.open(false) { }
                } catch (_: PlayFailureException) {
                    // The next one.
                }
            }
            delay(1_000)
        }
        throw PlayFailureException(PlayFailure.NoSource)
    }

    /** Needs the real versions: a stream cut after a few seconds, and a better version that takes time to download. */
    private fun withReal(): List<PlayVersion> = buildList {
        if ("cut" in wanted) {
            add(
                fake("cut", VideoQuality.UHD_4K) { _, _ ->
                    val stream = firstReal()
                    stream.copy(url = CutProxy(stream.url, CUT_AFTER_BYTES).url)
                },
            )
        }
        if ("upgrade" in wanted) {
            // With "only", a known pair: a ready version to start on, and a better one that takes time.
            if ("only" in wanted) add(fake("ready", VideoQuality.FHD_1080) { _, _ -> firstReal() })
            add(
                PlayVersion("fault-upgrade", VersionOrigin.Debrid, "TEST upgrade", AudioLanguage.VF, VideoQuality.UHD_4K, readiness = Readiness.NotCached) { keep, progress ->
                    if (!keep) throw PlayFailureException(PlayFailure.NotReady(null))
                    for (percent in 0..100 step 25) {
                        progress(Transfer(percent, speedBps = 8_000_000, seeders = 12, bytes = 8_000_000_000))
                        delay(STEP_MS)
                    }
                    firstReal()
                },
            )
        }
    }

    override fun find(request: PlayRequest, refresh: Boolean): Flow<SearchUpdate> =
        // "only" leaves nothing else to fall back on, to reach the failure screen.
        real.find(request, refresh).map { found ->
            latestReal = found.versions
            found.copy(versions = faults + withReal() + if ("only" in wanted) emptyList() else found.versions)
        }

    private companion object {
        const val STEP_MS = 1_500L
        const val REAL_WAIT_SECONDS = 60
        const val CUT_AFTER_BYTES = 40L * 1024 * 1024
    }
}
