package com.sokoul.pc

import com.sokoul.desktop.player.LocalMediaRelay
import com.sokoul.desktop.player.StreamRequest
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame

class LocalMediaRelayTest {
    @Test fun capabilitiesHideTargetsAndRevocationPreventsUpstreamRequests() {
        LocalMediaRelay().use { relay ->
            val plain = StreamRequest("https://example.org/master.m3u8", null, null, "Fixture")
            assertSame(plain, relay.register(plain))
            val wrapped = relay.register(plain.copy(headers = mapOf("Cookie" to "fixture-secret")))
            assertEquals("127.0.0.1", URI(wrapped.url).host)
            assertEquals(emptyMap<String, String>(), wrapped.headers)
            assertFalse(wrapped.url.contains("example.org"))
            assertFalse(wrapped.url.contains("fixture-secret"))
            relay.clear()
            val call = URI(wrapped.url).toURL().openConnection() as HttpURLConnection
            call.connectTimeout = 2000
            call.readTimeout = 2000
            try { assertEquals(403, call.responseCode) } finally { call.disconnect() }
        }
    }
}
