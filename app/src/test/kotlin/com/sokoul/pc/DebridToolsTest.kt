package com.sokoul.pc

import com.sokoul.pc.playback.session.*
import com.sokoul.pc.playback.sources.*
import com.sokoul.pc.playback.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DebridToolsTest {
    @Test fun providerDiscoveryAndSelectedUnlockKeepVipOnTheServerAndPreserveFileIdentity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"success","providers":["deepbrid","unknown","deepbrid","realdebrid"]}"""))
            server.enqueue(MockResponse().setBody("""{"status":"success","data":{"link":"https://media.example/file.mp4?signature=fixture","filename":"Episode.mp4","filesize":1000000,"host":"host.example"}}"""))
            val service = DebridToolsService(OkHttpClient(), { server.url("/").toString() }, { "fixture-key" })
            assertEquals(listOf(HostDebridProvider.DEEPBRID, HostDebridProvider.REALDEBRID), service.providers())
            val result = service.unlock("https://host.example/file/1", HostDebridProvider.DEEPBRID)
            assertTrue(result.playable)
            assertEquals("Episode.mp4", result.filename)
            assertEquals(1000000L, result.filesize)
            assertEquals("/api/media/debrid/providers", server.takeRequest().path)
            val unlock = server.takeRequest()
            assertEquals("/api/media/debrid/unlock", unlock.path)
            assertEquals("fixture-key", unlock.getHeader("x-access-key"))
            val body = unlock.body.readUtf8()
            assertTrue(body.contains("deepbrid"))
            assertTrue(body.contains("https://host.example/file/1"))
        }
    }

    @Test fun unsupportedProvidersAndPrivateLinksNeverReachTheUnlockTransport() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"success","providers":["deepbrid"]}"""))
            val service = DebridToolsService(OkHttpClient(), { server.url("/").toString() }, { "fixture-key" })
            service.providers()
            for ((link, provider) in listOf("http://127.0.0.1/file" to HostDebridProvider.DEEPBRID, "https://host.example/1" to HostDebridProvider.BESTDEBRID)) {
                try { service.unlock(link, provider); fail("Invalid request must fail") } catch (_: DebridToolException) { }
            }
            assertEquals(1, server.requestCount)
            assertFalse(DebridToolsService.publicLink("http://[::1]/file"))
            assertFalse(DebridToolsService.publicLink("https://user:secret@host.example/file"))
            assertFalse(DebridToolsService.publicLink("file:///private"))
        }
    }

    @Test fun vipDenialAndBestDebridResponseShapesRemainExplicit() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"VIP_REQUIRED"}"""))
            val service = DebridToolsService(OkHttpClient(), { server.url("/").toString() }, { "fixture-key" })
            try { service.providers(); fail("VIP denial must not become an empty enabled list") }
            catch (failure: DebridToolException) { assertEquals(DebridToolFailure.VipRequired, failure.reason) }
        }
        val json = Json
        for (shape in listOf("""[{"download":"https://media.example/f"}]""", """{"data":{"download":"https://media.example/f"}}""", """{"data":[{"download":"https://media.example/f"}]}""")) {
            assertNotNull(DebridToolsService.bestCandidate(json.parseToJsonElement(shape))?.get("download"))
        }
        assertFalse(DebridToolResult("https://media.example/archive.zip", "archive.zip", 0, "host", HostDebridProvider.DEEPBRID).playable)
    }
}
