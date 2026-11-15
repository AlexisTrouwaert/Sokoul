package com.sokoul.pc

import com.sokoul.pc.live.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SportsSourcesTest {
    private val event = SportsEvent("streamed_fixture", "Home v Away", "Football", true, null)
    private val choice = SportsServer("a".repeat(24), "French · HD")
    private fun relay(base: String) = "$base/streamed-proxy?url=https%3A%2F%2Fmedia.test%2Fmaster.m3u8&exp=9999999999&sig=${"A".repeat(43)}"

    @Test fun catalogueParsingKeepsOnlyRegisteredSportsEvents() {
        val data = Json.parseToJsonElement("""{"metas":[{"id":"streamed_live","name":"Live event","_sport":"Football","_isLive":true,"_timestamp":1000},{"id":"streamed_live","name":"Duplicate"},{"id":"vavoo_not-sports","name":"Not sports"},{"id":"streamed_../../x","name":"Invalid"}]}""").jsonObject
        val items = SportsParse.events(data)
        assertEquals(1,items.size)
        assertTrue(items.single().live)
        assertEquals(1000L,items.single().startsAt)
    }

    @Test fun onlyConfiguredSignedMediaRelaysArePlayable() {
        val valid = relay("http://localhost:25569")
        assertEquals(valid,SportsParse.media(valid,"http://localhost:25565"))
        assertNull(SportsParse.media(valid.replace("localhost","evil.test"),"http://localhost:25565"))
        assertNull(SportsParse.media(valid.replace("exp=9999999999","exp=1"),"http://localhost:25565"))
        assertNull(SportsParse.media(valid.replace("/streamed-proxy","/untrusted"),"http://localhost:25565"))
        assertNull(SportsParse.media("https://embed.st/embed/golf/id/1","http://localhost:25565"))
    }

    @Test fun discoveryDoesNotResolveMediaAndOnlySelectedServerIsResolved() = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"metas":[{"id":"streamed_fixture","name":"Home v Away"}]}"""))
            server.enqueue(MockResponse().setBody("""{"streams":[{"_streamedKey":"${choice.key}","title":"French · HD","url":"https://embed.st/embed/x/id/1"}]}"""))
            server.enqueue(MockResponse().setBody("""{"url":"${relay(base)}"}"""))
            val source = SportsSources(OkHttpClient(),{base},{"fixture-key"})
            assertEquals(1,source.events().value!!.size)
            assertEquals(1,server.requestCount)
            assertEquals(1,source.servers(event).value!!.size)
            assertEquals(2,server.requestCount)
            val result = source.resolve(event,choice)
            assertEquals("hls",result.value!!.mediaType)
            assertTrue(result.value!!.live)
            assertEquals("/api/livetv/catalog/tv/streamed_all",server.takeRequest().path)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("/api/livetv/streamed/native/${event.id}/${choice.key}",request.path)
            assertEquals("fixture-key",request.getHeader("x-access-key"))
        }
    }

    @Test fun unavailableDecoderAndVipRefusalAreExplicitAndEmptyCatalogueRemainsEmpty() = runBlocking {
        MockWebServer().use { server ->
            val source = SportsSources(OkHttpClient(),{server.url("/").toString()},{"fixture-key"})
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(SportsFailure.Unavailable,source.resolve(event,choice).failure)
            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(SportsFailure.VipRequired,source.resolve(event,choice).failure)
            server.enqueue(MockResponse().setBody("""{"metas":[]}"""))
            val result = source.events()
            assertNull(result.failure)
            assertTrue(result.value!!.isEmpty())
        }
    }

    @Test fun pendingResponseHasABoundedRetryCountAndMissingVipDoesNotMakeARequest() = runBlocking {
        MockWebServer().use { server ->
            val base = server.url("/").toString()
            val missing = SportsSources(OkHttpClient(),{base},{null})
            assertEquals(SportsFailure.VipRequired,missing.resolve(event,choice).failure)
            assertEquals(0,server.requestCount)
            repeat(4) { server.enqueue(MockResponse().setResponseCode(202)) }
            val source = SportsSources(OkHttpClient(),{base},{"fixture"})
            assertEquals(SportsFailure.Unavailable,source.resolve(event,choice).failure)
            assertEquals(4,server.requestCount)
        }
    }
}
