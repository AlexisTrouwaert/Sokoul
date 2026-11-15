package com.sokoul.iptv.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class XtreamGuideFallbackTest {
    @Test fun `empty short guide falls back to full channel table and accepts end timestamp`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"epg_listings":[]}"""))
            server.enqueue(MockResponse().setBody("""{"epg_listings":[{"start_timestamp":"1800000000","end_timestamp":"1800003600","title":"RW1pc3Npb24="}]}"""))
            val client = XtreamClient(OkHttpClient(), Json)
            val rows = client.shortEpg(XtreamAuth(server.url("/").toString().trimEnd('/'), "user", "pass", null), 154)
            assertEquals(1, rows.size)
            assertEquals(1_800_000_000_000L, rows.single().start)
            assertEquals(1_800_003_600_000L, rows.single().stop)
            assertEquals("Emission", rows.single().title)
            assertEquals("get_short_epg", server.takeRequest().requestUrl!!.queryParameter("action"))
            assertEquals("get_simple_data_table", server.takeRequest().requestUrl!!.queryParameter("action"))
        }
    }

    @Test fun `populated short guide does not issue redundant full table request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"epg_listings":[{"start_timestamp":1800000000,"stop_timestamp":1800003600,"title":"Current"}]}"""))
            val rows = XtreamClient(OkHttpClient(), Json).shortEpg(XtreamAuth(server.url("/").toString(), "user", "pass", null), 1)
            assertEquals("Current", rows.single().title)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `unavailable short route uses full guide and rejects invalid intervals`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"epg_listings":[{"start_timestamp":1800000000000,"end_timestamp":1800003600000,"title":"Valid"},{"start_timestamp":1800000000,"end_timestamp":1799999999,"title":"Invalid"}]}"""))
            val rows = XtreamClient(OkHttpClient(), Json).shortEpg(XtreamAuth(server.url("/").toString(), "user", "pass", null), 1)
            assertEquals(listOf("Valid"), rows.map { it.title })
            assertEquals(1_800_000_000_000L, rows.single().start)
        }
    }
}
