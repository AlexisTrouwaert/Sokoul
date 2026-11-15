package com.sokoul.pc

import com.sokoul.pc.live.settledGuidePreview
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GuidePreviewDebounceTest {
    @Test fun waits_for_the_full_quiet_period() = runBlocking {
        val start = System.nanoTime()
        var elapsed = 0L
        settledGuidePreview({ "channel" }) { elapsed = (System.nanoTime() - start) / 1_000_000 }
        assertTrue("preview started at $elapsed ms", elapsed >= 1_200)
    }

    @Test fun fifty_fast_selections_open_only_the_last_preview() = runBlocking {
        val opened = mutableListOf<Int>()
        var pending: Job? = null
        repeat(50) { channel ->
            pending?.cancelAndJoin()
            pending = launch { settledGuidePreview({ channel }) { opened.add(it) } }
            delay(5)
        }
        pending!!.join()
        assertEquals(listOf(49), opened)
    }

    @Test fun opening_a_menu_cancels_a_pending_preview() = runBlocking {
        var resolved = false
        val pending = launch { settledGuidePreview({ resolved = true; "channel" }) { fail("menu opened a stream") } }
        delay(25)
        pending.cancelAndJoin()
        assertFalse(resolved)
    }

    @Test fun a_late_uncancellable_resolution_cannot_open_an_obsolete_stream() = runBlocking {
        val resolving = CompletableDeferred<Unit>()
        var opened = false
        val pending = launch {
            settledGuidePreview({
                resolving.complete(Unit)
                withContext(NonCancellable) { delay(60); "obsolete" }
            }) { opened = true }
        }
        resolving.await()
        pending.cancelAndJoin()
        assertFalse(opened)
    }
}
