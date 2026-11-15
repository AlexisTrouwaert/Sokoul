package com.sokoul.pc

import com.sokoul.pc.playback.sources.AniProjectParse
import com.sokoul.pc.playback.sources.AniProjectSources
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.sources.StreamProbe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AniProjectSourcesTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    @Test fun `Aniwatch and Consumet routes yield only direct matching episode streams`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/search/anime/aniwatch" -> json("""{"results":[{"id":"ani-id","name":"Example Anime"}]}""")
                "/search/anime/consumet/gogoanime" -> json("""{"results":[{"id":"gogo-id","title":"Example Anime","releaseDate":"2020"}]}""")
                "/search/anime/consumet/zoro" -> json("""{"results":[{"id":"zoro-id","title":"Example Anime","releaseDate":"2020"}]}""")
                "/episodes/aniwatch/all" -> json("""{"results":[{"episodeId":"ani-1","number":1},{"episodeId":"ani-2","number":2}]}""")
                "/episodes/consumet/gogoanime/all" -> json("""{"results":[{"id":"gogo-1","number":1},{"id":"gogo-2","number":2}]}""")
                "/episodes/consumet/zoro/all" -> json("""{"results":[{"id":"zoro-1","number":1}]}""")
                "/episodes/aniwatch/episode" -> if (request.requestUrl?.queryParameter("category") == "sub") {
                    json("""{"results":{"sources":[{"url":"https://cdn.test/episode.m3u8","type":"hls"},{"url":"https://embed.test/player/1","type":"hls"}]}}""")
                } else json("""{"results":{"sources":[{"url":"https://cdn.test/dub.mp4","type":"mp4"}]}}""")
                "/episodes/consumet/gogoanime/episode" -> json("""{"results":{"sources":[{"url":"https://cdn.test/gogo.m3u8","isM3U8":true,"quality":"1080p"}]}}""")
                "/episodes/consumet/zoro/episode" -> json("""{"results":{"sources":[{"url":"https://cdn.test/zoro.mp4","type":"mp4"}]}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val sources = AniProjectSources(OkHttpClient(), { server.url("/").toString() }, null, StreamProbe(OkHttpClient()))
        val request = PlayRequest("tv", 123, 1, 1, "Example Anime", null, null)

        val found = sources.find(request, null)

        assertEquals(setOf("Aniwatch · sub", "Aniwatch · dub", "Consumet · Gogoanime · sub/dub · 1080p", "Consumet · Zoro · sub/dub"), found.map { it.name }.toSet())
        assertEquals(4, found.size)
        assertTrue(found.all { it.language == com.sokoul.iptv.domain.AudioLanguage.VO })
        assertEquals(10, server.requestCount)
    }

    @Test fun `exact title and year are required before episode routes are requested`() {
        val root = Json.parseToJsonElement("""{"results":[{"id":"other","title":"Example Anime 2","releaseDate":"2020"},{"id":"remake","title":"Example Anime","releaseDate":"2010"}]}""")
        val results = AniProjectParse.consumetSearch(root)

        assertTrue(AniProjectParse.exactMatch(results, listOf("example anime"), 2020) == null)
        assertTrue(AniProjectParse.exactMatch(results, listOf("example anime"), null)?.id == "remake")
    }

    @Test fun `embed and malformed links are never direct media`() {
        assertTrue(AniProjectParse.directMedia("https://cdn.test/file.m3u8?token=abc"))
        assertTrue(AniProjectParse.directMedia("https://cdn.test/video.mp4"))
        assertTrue(AniProjectParse.directMedia("https://cdn.test/hls/stream?id=1", hls = true))
        assertFalse(AniProjectParse.directMedia("https://embed.test/player/stream.m3u8", hls = true))
        assertFalse(AniProjectParse.directMedia("javascript:alert(1).mp4"))
        assertFalse(AniProjectParse.directMedia("https://cdn.test/embed?id=1", hls = true))
    }

    @Test fun `disabled backend sends no network request`() = runBlocking {
        val sources = AniProjectSources(OkHttpClient(), { null }, null, StreamProbe(OkHttpClient()))
        val found = sources.find(PlayRequest("tv", 123, 1, 1, "Example Anime", null, null), null)
        assertTrue(found.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun `movie requests never query anime sources`() = runBlocking {
        val sources = AniProjectSources(OkHttpClient(), { server.url("/").toString() }, null, StreamProbe(OkHttpClient()))
        val found = sources.find(PlayRequest("movie", 123, null, null, "Resident Evil 3", null, null), null)
        assertTrue(found.isEmpty())
        assertEquals(0, server.requestCount)
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
