package com.sokoul.pc

import com.sokoul.desktop.player.StreamRequest
import com.sokoul.iptv.data.debrid.DebridQuery
import com.sokoul.iptv.data.debrid.DebridStream
import com.sokoul.iptv.data.debrid.RealDebridApi
import com.sokoul.iptv.data.debrid.Release
import com.sokoul.pc.playback.sources.DebridTransfers
import com.sokoul.pc.playback.session.Doubt
import com.sokoul.pc.playback.session.Phase
import com.sokoul.pc.playback.session.PlayFailure
import com.sokoul.pc.playback.session.PlayFailureException
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.pc.playback.session.PlaybackBudgets
import com.sokoul.desktop.data.PlaybackPrefs
import com.sokoul.pc.playback.session.PlaybackSession
import com.sokoul.pc.playback.session.Readiness
import com.sokoul.desktop.data.StartMode
import com.sokoul.pc.playback.sources.StartPolicy
import com.sokoul.pc.playback.session.VersionOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Several Real-Debrid versions tried at once: the first link wins, nothing is left behind on the account. */
class RaceTest {
    private val request = PlayRequest("movie", 1, null, null, "Title", null, null)
    private val budgets = PlaybackBudgets(firstChoiceMs = 200, settleMs = 50, searchMs = 300, firstImageMs = 400, stallMs = 300, tickMs = 20)

    private fun waitUntil(what: String, check: () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out: $what")
            delay(10)
        }
    }

    private fun session(engine: FakeEngine, size: Int, vararg versions: PlayVersion) = PlaybackSession(
        engine, finder(*versions), CoroutineScope(SupervisorJob() + Dispatchers.Default),
        prefs = { PlaybackPrefs() }, frenchSystem = { true }, budgets = budgets, raceSize = { size },
    )

    private fun v(key: String, readiness: Readiness = Readiness.Unknown, origin: VersionOrigin = VersionOrigin.Debrid, rejected: Doubt? = null, open: suspend (Boolean) -> StreamRequest) =
        PlayVersion(key, origin, key, readiness = readiness, rejected = rejected) { keep, _ -> open(keep) }

    private fun stream(url: String) = StreamRequest(url, null, null, "t", live = false)

    @Test fun `the first link wins and the slower tries are cancelled`() {
        val slowCancelled = AtomicBoolean(false)
        val engine = FakeEngine()
        val s = session(
            engine, 3,
            v("a") { delay(300); throw PlayFailureException(PlayFailure.NotReady(0)) },
            v("b") { delay(50); stream("b") },
            v("c") { try { delay(5_000); stream("c") } catch (e: CancellationException) { slowCancelled.set(true); throw e } },
        )
        s.play(request)
        waitUntil("plays b") { s.phase is Phase.Playing && engine.played.lastOrNull()?.url == "b" }
        waitUntil("c cancelled") { slowCancelled.get() }
        assertEquals(listOf("b"), engine.played.map { it.url })
    }

    @Test fun `a loser that got its link too removes what it added`() {
        val discarded = AtomicBoolean(false)
        val engine = FakeEngine()
        val loser = PlayVersion("late", VersionOrigin.Debrid, "late", readiness = Readiness.Unknown, discard = { discarded.set(true) }) { _, _ ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(150) }
            stream("late")
        }
        val s = session(engine, 2, v("first") { delay(20); stream("first") }, loser)
        s.play(request)
        waitUntil("plays first") { s.phase is Phase.Playing && engine.played.lastOrNull()?.url == "first" }
        waitUntil("late one discarded") { discarded.get() }
    }

    @Test fun `without a race the versions are tried one after the other`() {
        val engine = FakeEngine()
        val opened = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = session(
            engine, 1,
            v("a") { opened += "a"; throw PlayFailureException(PlayFailure.NotReady(0)) },
            v("b") { opened += "b"; stream("b") },
        )
        s.play(request)
        waitUntil("plays b") { s.phase is Phase.Playing }
        assertEquals(listOf("a", "b"), opened)
    }

    @Test fun `when nobody wins the best one is downloaded`() {
        val engine = FakeEngine()
        val s = session(
            engine, 3,
            v("a") { keep -> if (keep) stream("a-download") else throw PlayFailureException(PlayFailure.NotReady(0)) },
            v("b") { throw PlayFailureException(PlayFailure.NotReady(0)) },
        )
        s.play(request)
        waitUntil("downloads a") { s.phase is Phase.Playing && engine.played.lastOrNull()?.url == "a-download" }
    }

    @Test fun `a version in the account goes alone, and IPTV or rejected versions never race`() {
        fun plain(key: String, readiness: Readiness = Readiness.Unknown, origin: VersionOrigin = VersionOrigin.Debrid, rejected: Doubt? = null) =
            v(key, readiness, origin, rejected) { stream(key) }
        val ready = listOf(plain("held", Readiness.Ready), plain("x"), plain("y"))
        assertTrue(StartPolicy.race(ready, StartMode.ReadyFirst, emptySet(), emptySet(), emptySet(), emptySet(), 3).isEmpty())
        val iptvFirst = listOf(plain("tv", Readiness.Ready, VersionOrigin.Iptv), plain("x"), plain("y"))
        assertTrue(StartPolicy.race(iptvFirst, StartMode.ReadyFirst, emptySet(), emptySet(), emptySet(), emptySet(), 3).isEmpty())
        val mixed = listOf(plain("x"), plain("other", rejected = Doubt.OtherWork), plain("tv", origin = VersionOrigin.Iptv), plain("y"), plain("z"), plain("w"))
        assertEquals(listOf("x", "y", "z"), StartPolicy.race(mixed, StartMode.ReadyFirst, emptySet(), emptySet(), emptySet(), emptySet(), 3).map { it.key })
        assertTrue(StartPolicy.race(mixed, StartMode.ReadyFirst, emptySet(), emptySet(), emptySet(), emptySet(), 1).isEmpty())
    }

    @Test fun `a Real-Debrid limit ends the race`() {
        val engine = FakeEngine()
        val opened = java.util.Collections.synchronizedList(mutableListOf<String>())
        val s = session(
            engine, 2,
            v("a") { opened += "a"; throw PlayFailureException(PlayFailure.DebridLimit) },
            v("b") { opened += "b"; throw PlayFailureException(PlayFailure.DebridLimit) },
            v("c") { opened += "c"; stream("c") },
        )
        s.play(request)
        waitUntil("gives up") { s.phase is Phase.Failed }
        assertEquals(PlayFailure.DebridLimit, (s.phase as Phase.Failed).failure)
        assertFalse("c" in opened)
    }

    @Test fun `three Real-Debrid network errors in a row leave it for the other sources`() {
        val engine = FakeEngine()
        val opened = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun down(key: String) = v(key, Readiness.Likely) { opened += key; throw PlayFailureException(PlayFailure.Network) }
        val s = session(
            engine, 1,
            down("a"), down("b"), down("c"), down("d"), down("e"),
            v("tv", Readiness.Unknown, VersionOrigin.Iptv) { opened += "tv"; stream("tv") },
        )
        s.play(request)
        waitUntil("plays IPTV") { s.phase is Phase.Playing && engine.played.lastOrNull()?.url == "tv" }
        assertEquals(listOf("a", "b", "c", "tv"), opened)
    }

    /** Real-Debrid that never reads the torrent's files: the try waits, and is cancelled meanwhile. */
    private class SlowRd : Dispatcher() {
        @Volatile var deleted = false
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("torrents/addMagnet") -> MockResponse().setBody("""{"id":"T1"}""")
                path.contains("torrents/info/T1") -> MockResponse().setBody("""{"id":"T1","hash":"abc","status":"magnet_conversion","progress":0,"files":[],"links":[]}""")
                path.contains("torrents/delete/T1") -> { deleted = true; MockResponse().setResponseCode(204) }
                path.startsWith("/torrents") -> MockResponse().setBody("[]")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test fun `a cancelled try removes the torrent it added`() = runBlocking<Unit> {
        val server = MockWebServer()
        val rd = SlowRd()
        server.dispatcher = rd
        server.start()
        val transfers = DebridTransfers(RealDebridApi(OkHttpClient(), { "token" }, server.url("/").toString().trimEnd('/'), spacingMs = 0), pollMs = 50, quickPollMs = 200)
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val job = launch(Dispatchers.IO) {
            runCatching { transfers.link(DebridStream("$hash:-1", "F", null, hash, null, null, Release()), DebridQuery(1, false), keep = false) {} }
        }
        delay(300)
        job.cancel()
        job.join()
        assertTrue("the added torrent is removed", rd.deleted)
        server.shutdown()
    }
}
