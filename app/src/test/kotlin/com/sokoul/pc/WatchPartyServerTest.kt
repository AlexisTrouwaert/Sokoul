package com.sokoul.pc

import com.sokoul.pc.party.PartyMedia
import com.sokoul.pc.party.PartyState
import com.sokoul.pc.party.WatchParty
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two Sokoul clients against the real watch party server (API/watchpartyAPI, `node watchparty.js`). Skipped unless
 * SOKOUL_PARTY_SERVER names a running server, for example http://localhost:25566.
 */
class WatchPartyServerTest {
    private val server = System.getenv("SOKOUL_PARTY_SERVER").orEmpty()

    private fun until(what: String, ms: Long = 8_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    @Test
    fun hostAndGuestStayInStep() = runBlocking {
        assumeTrue("SOKOUL_PARTY_SERVER not set", server.isNotBlank())
        val http = OkHttpClient()
        val host = WatchParty(http)
        val guest = WatchParty(http)
        try {
            val film = PartyMedia("movie", 27205, null, null, "Inception", null)
            assertTrue(host.create(server, "Host", film, 12.0))
            until("host in room") { host.state is PartyState.In }
            val code = (host.state as PartyState.In).code
            assertEquals(6, code.length)

            var opened: PartyMedia? = null
            guest.onMedia = { opened = it }
            assertTrue(guest.join(server, code, "Guest"))
            until("guest in room") { guest.state is PartyState.In }
            until("both listed") { guest.participants.size == 2 && host.participants.size == 2 }
            // The guest learns what is playing from the room itself.
            until("title reaches the guest") { opened == film }

            host.say("hello")
            until("chat reaches the guest") { guest.messages.any { it.text == "hello" && it.from == "Host" } }

            host.tell(true, 95.0, "play")
            until("playback reaches the guest") { guest.remote?.playing == true }
            assertEquals(95.0, guest.remote!!.positionSec, 0.5)
            host.tell(false, 140.0, "pause")
            until("pause reaches the guest") { guest.remote?.playing == false }
            assertEquals(140.0, guest.remote!!.positionSec, 0.5)

            val episode = PartyMedia("tv", 1399, 2, 5, "Game of Thrones", null)
            host.changeMedia(episode)
            until("next episode reaches the guest") { opened == episode }

            guest.leave()
            until("guest gone") { host.participants.size == 1 }
        } finally {
            host.leave()
            guest.leave()
        }
    }
}
