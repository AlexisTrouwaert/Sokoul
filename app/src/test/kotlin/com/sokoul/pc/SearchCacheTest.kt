package com.sokoul.pc

import com.sokoul.desktop.data.DesktopDebrid
import com.sokoul.iptv.data.debrid.DebridAddons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The answers of the search services are kept on the device under a key that never holds the Real-Debrid token. */
class SearchCacheTest {
    private val token = "SECRET-TOKEN-1234"
    private val path = "stream/series/tt0388629:1:1.json"

    @Test fun `no key holds the token or Comet's configuration`() {
        val keys = listOf(
            DesktopDebrid.cacheKey(DebridAddons.torrentio(token) + "/" + path),
            DesktopDebrid.cacheKey(DebridAddons.comet(token) + "/" + path),
            DesktopDebrid.cacheKey(DebridAddons.torrentioPlain() + "/" + path),
        )
        keys.forEach { k ->
            assertFalse(k, k.contains(token))
            assertFalse(k, k.contains(DebridAddons.cometConfig(token)))
            assertFalse(k, k.contains("realdebrid"))
        }
        assertEquals("debrid:torrentio:fr:series/tt0388629:1:1.json", keys[0])
        assertEquals("debrid:comet:all:series/tt0388629:1:1.json", keys[1])
        assertEquals("debrid:torrentio-fast:fr:series/tt0388629:1:1.json", keys[2])
    }

    @Test fun `each service and language keeps its own answer`() {
        assertNotEquals(
            DesktopDebrid.cacheKey(DebridAddons.torrentioPlain(true) + "/" + path),
            DesktopDebrid.cacheKey(DebridAddons.torrentioPlain(false) + "/" + path),
        )
        assertNotEquals(
            DesktopDebrid.cacheKey(DebridAddons.torrentioPlain() + "/" + path),
            DesktopDebrid.cacheKey(DebridAddons.torrentioPlain() + "/stream/series/tt0388629:1:2.json"),
        )
    }
}
