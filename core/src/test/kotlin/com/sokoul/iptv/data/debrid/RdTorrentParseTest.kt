package com.sokoul.iptv.data.debrid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RdTorrentParseTest {
    @Test fun theAddedDateIsRead() {
        val o = Json.parseToJsonElement("""{"id":"A","filename":"Film.mkv","hash":"ABC","status":"downloaded","progress":100,"bytes":1,"added":"2026-10-28T12:34:56.000Z"}""") as JsonObject
        val t = RealDebridApi.parseTorrent(o)
        assertEquals(java.time.Instant.parse("2026-10-28T12:34:56Z").toEpochMilli(), t.addedAt)
        assertEquals("abc", t.hash)
    }
}
