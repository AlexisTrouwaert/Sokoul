package com.sokoul.iptv.data.net

import com.sokoul.iptv.data.anime.AnimeExtrasParse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroDbTest {
    private val tedLasso = """{"imdb_id":"tt10986410","season":1,"episode":1,"intro":{"start_sec":345.4,"end_sec":366.1},"recap":null,
        |"outro":{"start_sec":1818,"end_sec":1855},"no_intro":false}""".trimMargin()

    @Test
    fun reads_intro_and_outro_and_caches_the_answer() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(tedLasso)); start() }
        val store = HashMap<String, TmdbCached>()
        val cache = object : TmdbCache {
            override suspend fun get(key: String) = store[key]
            override suspend fun put(key: String, json: String, fetchedAt: Long) { store[key] = TmdbCached(json, fetchedAt) }
        }
        val db = IntroDb(OkHttpClient(), cache, server.url("/"))
        val first = db.segments("tt10986410", 1, 1)
        val second = db.segments("tt10986410", 1, 1)
        assertEquals(1, server.requestCount)
        assertEquals("/segments?imdb_id=tt10986410&season=1&episode=1", server.takeRequest().path)
        assertEquals(first, second)
        assertEquals(listOf(true to 345.4, false to 1818.0), first.map { it.opening to it.start })
        server.shutdown()
    }

    @Test
    fun unknown_episode_and_bad_id_give_nothing() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(404)); start() }
        assertTrue(IntroDb(OkHttpClient(), null, server.url("/")).segments("tt1234567", 1, 9).isEmpty())
        assertTrue(IntroDb(OkHttpClient(), null, server.url("/")).segments("../evil", 1, 1).isEmpty())
        server.shutdown()
    }

    @Test
    fun segments_without_length_fit_only_inside_the_file() {
        val segments = IntroDb.parse(tedLasso)
        assertEquals(2, AnimeExtrasParse.fitting(segments, 1856.0).size)
        assertEquals(listOf(true), AnimeExtrasParse.fitting(segments, 1500.0).map { it.opening })
    }
}
