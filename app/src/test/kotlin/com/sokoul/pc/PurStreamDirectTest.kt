package com.sokoul.pc

import com.sokoul.iptv.data.net.TmdbClient
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.sources.PurStreamDirect
import com.sokoul.pc.playback.sources.WebParse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PurStream read by the app: the work recognised by a TMDB image, then its streams in the server's answer shape. */
class PurStreamDirectTest {
    private fun site() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/status" -> MockResponse().setResponseCode(404)
                "/api/v1/search-bar/search/Inception" -> MockResponse().setBody(
                    """{"type":"success","data":{"items":{"movies":{"items":[
                        {"id":11,"type":"movie","title":"Inception (other)","large_poster_path":"https://img.test/w500/other.jpg"},
                        {"id":2458,"type":"movie","title":"Inception","large_poster_path":"https://img.test/w500/aej3.jpg?x=1"}]}}}}""")
                "/api/v1/stream/2458" -> MockResponse().setBody(
                    """{"type":"success","data":{"items":{"sources":[{"stream_url":"https://cdn.test/movies/27205/master.m3u8","source_name":"pulse | 1080p | MULTI","format":"m3u8"}]}}}""")
                "/api/v1/search-bar/search/The%20Handmaid%27s%20Tale" -> MockResponse().setBody(
                    """{"type":"success","data":{"items":{"movies":{"items":[{"id":77,"type":"tv","title":"The Handmaid's Tale","wallpaper_poster_path":"https://img.test/hm.jpg"}]}}}}""")
                "/api/v1/stream/77/episode?season=4&episode=6" -> MockResponse().setBody(
                    """{"type":"success","data":{"items":{"sources":[{"stream_url":"https://cdn.test/tv/77/4/6/master.m3u8","source_name":"pulse | 1080p | MULTI","format":"m3u8"}]}}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    private fun direct(site: MockWebServer, images: Set<String>) = PurStreamDirect(
        OkHttpClient(),
        tmdb = { kind, _ -> TmdbClient.NamesAndImages(if (kind == "movie") "Inception" else "The Handmaid's Tale", null, null, images) },
        statusUrl = site.url("/status").toString(),
        fallbackApi = site.url("/api/v1").toString(),
    )

    @Test fun theFilmIsTheResultWithATmdbImageAndItsStreamsHaveTheServerShape() = runBlocking {
        val site = site()
        try {
            val answer = direct(site, setOf("/aej3.jpg"))!!.find(PlayRequest("movie", 27205, null, null, "Inception", null, null))!!
            val links = WebParse.links("PurStream", answer, null)
            assertEquals(listOf("https://cdn.test/movies/27205/master.m3u8"), links.map { it.url })
        } finally { site.shutdown() }
    }

    @Test fun anEpisodeAsksItsSeasonAndNumber() = runBlocking {
        val site = site()
        try {
            val answer = direct(site, setOf("/hm.jpg")).find(PlayRequest("tv", 69478, 4, 6, "The Handmaid's Tale", null, null))!!
            assertEquals(listOf("https://cdn.test/tv/77/4/6/master.m3u8"), WebParse.links("PurStream", answer, 6).map { it.url })
        } finally { site.shutdown() }
    }

    @Test fun withoutAMatchingImageNothingIsTaken() = runBlocking {
        val site = site()
        try {
            assertNull(direct(site, setOf("/unrelated.jpg")).find(PlayRequest("movie", 27205, null, null, "Inception", null, null)))
        } finally { site.shutdown() }
    }
}
