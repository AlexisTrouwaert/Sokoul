package com.sokoul.pc

import com.sokoul.desktop.player.StreamRequest
import com.sokoul.pc.playback.session.*
import com.sokoul.pc.playback.sources.*
import com.sokoul.pc.playback.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

class PersonalPlaybackTest {
    private fun channel(id: String, allowed: () -> Boolean = { true }) = LiveChannel(id, id, null, null,
        StreamRequest(id, null, null, id), canOpen = allowed)
    private suspend fun await(check: () -> Boolean) = withTimeout(3_000) { while (!check()) delay(10) }
    private val budgets = PlaybackBudgets(firstImageMs = 500, stallMs = 30_000, tickMs = 20)

    @Test fun `scope change opens nothing and next zap enters a scope excluding current channel`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val e = FakeEngine()
            val a = channel("a"); val b = channel("b"); val c = channel("c")
            var ring = listOf(a, b)
            val history = java.util.concurrent.CopyOnWriteArrayList<String>()
            val s = PlaybackSession(e, SourceFinder { _, _ -> emptyFlow() }, scope, budgets = budgets,
                resolveLiveScope = { ring }, onLiveStarted = { history += it })
            s.playLive(a, listOf(a, b)); await { s.phase is Phase.Playing && history.size == 1 }
            ring = listOf(b, c)
            delay(50)
            assertEquals(1, e.played.size)
            s.zap(1); await { s.liveBaseKey == "b" && s.phase is Phase.Playing }
            s.zap(-1); await { s.liveBaseKey == "c" && s.phase is Phase.Playing }
            s.previous(); await { s.liveBaseKey == "b" && s.phase is Phase.Playing }
            assertEquals(listOf("a", "b", "c", "b"), e.played.map { it.url })
            s.close()
        } finally { scope.cancel() }
    }

    @Test fun `empty or restricted scope never silently falls back or opens another stream`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val e = FakeEngine(); val a = channel("a")
            var ring = emptyList<LiveChannel>()
            val s = PlaybackSession(e, SourceFinder { _, _ -> emptyFlow() }, scope, budgets = budgets, resolveLiveScope = { ring })
            s.playLive(a, listOf(a)); await { s.phase is Phase.Playing }
            s.zap(1); await { s.liveNotice != null }
            assertEquals(1, e.played.size)
            ring = listOf(channel("locked") { false })
            s.zap(1); delay(80)
            assertEquals(1, e.played.size)
            s.close()
        } finally { scope.cancel() }
    }

    @Test fun `late scope resolution is canceled by a newer zap or explicit selection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val e = FakeEngine(); val a = channel("a"); val b = channel("b"); val c = channel("c")
            var slow = true
            val s = PlaybackSession(e, SourceFinder { _, _ -> emptyFlow() }, scope, budgets = budgets,
                resolveLiveScope = { if (slow) { delay(250); listOf(b) } else listOf(c) })
            s.playLive(a, listOf(a, b)); await { s.phase is Phase.Playing }
            s.zap(1); delay(30); slow = false; s.zap(1)
            await { s.liveBaseKey == "c" && s.phase is Phase.Playing }
            delay(300)
            assertEquals(listOf("a", "c"), e.played.map { it.url })
            slow = true; s.zap(1); s.playLive(a, listOf(a))
            await { s.liveBaseKey == "a" && s.phase is Phase.Playing }; delay(300)
            assertEquals(listOf("a", "c", "a"), e.played.map { it.url })
            s.close()
        } finally { scope.cancel() }
    }

    @Test fun `current group survives scope changes and quality switches do not duplicate history`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val e = FakeEngine(); val a = channel("a"); val b = channel("b"); val c = channel("c")
            var favorites = true
            val history = java.util.concurrent.CopyOnWriteArrayList<String>()
            val s = PlaybackSession(e, SourceFinder { _, _ -> emptyFlow() }, scope, budgets = budgets,
                resolveLiveScope = { original -> if (favorites) listOf(c) else original }, onLiveStarted = { history += it })
            s.playLive(a, listOf(a, b)); await { history.size == 1 }
            s.playVariant(channel("a-hd")); await { s.live?.key == "a-hd" && s.phase is Phase.Playing }
            assertEquals(listOf("a"), history.toList())
            s.zap(1); await { s.liveBaseKey == "c" && s.phase is Phase.Playing }
            favorites = false
            s.zap(1); await { s.liveBaseKey == "a" && s.phase is Phase.Playing }
            s.zap(1); await { s.liveBaseKey == "b" && s.phase is Phase.Playing }
            s.close()
        } finally { scope.cancel() }
    }
}
