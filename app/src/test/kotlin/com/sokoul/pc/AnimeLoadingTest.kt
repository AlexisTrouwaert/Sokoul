package com.sokoul.pc

import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.anime.AnimeMatchCache
import com.sokoul.pc.data.anime.AniParse
import com.sokoul.pc.data.anime.GhibliClient
import com.sokoul.pc.data.providers.ProviderHttp
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class AnimeLoadingTest {
    private val item = TmdbItem(1, "My Neighbor Totoro", "となりのトトロ", 1988, null, null, null, null, listOf(16), mediaType = "movie")

    @Test fun duplicate_titles_share_matching() = runBlocking {
        val cache = AnimeMatchCache()
        val calls = AtomicInteger()
        val results = (1..20).map { async { cache.resolve(21) { calls.incrementAndGet(); delay(30); item } } }.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(results.all { it == item })
    }

    @Test fun distinct_rows_share_the_same_concurrency_budget() = runBlocking {
        val cache = AnimeMatchCache(parallelism = 4)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        (1..20).map { id -> async {
            cache.resolve(id) {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try { delay(20); item } finally { active.decrementAndGet() }
            }
        } }.awaitAll()
        assertTrue(peak.get() in 2..4)
    }

    @Test fun a_temporary_miss_expires() = runBlocking {
        var now = 0L
        val cache = AnimeMatchCache(clock = { now })
        assertNull(cache.resolve(21) { null })
        assertNull(cache.resolve(21) { error("Miss should be cached briefly") })
        now = 60_001L
        assertEquals(item, cache.resolve(21) { item })
    }

    @Test fun cancellation_releases_the_budget_without_caching_a_miss() = runBlocking {
        val cache = AnimeMatchCache(parallelism = 1)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { cache.resolve(21) { awaitCancellation() } }
        pending.cancelAndJoin()
        assertEquals(item, withTimeout(1_000) { cache.resolve(21) { item } })
    }

    @Test fun streaming_links_keep_their_type_and_international_language() {
        val detail = AniParse.detail(Json.parseToJsonElement("""{"externalLinks":[
            {"site":"Crunchyroll","url":"https://www.crunchyroll.com/series/example","language":"English","type":"STREAMING"},
            {"site":"Official","url":"https://example.jp","language":"Japanese","type":"INFO"},
            {"site":"Invalid","url":"javascript:alert(1)","type":"STREAMING"}]}"""))
        assertEquals(2, detail.links.size)
        assertEquals("STREAMING", detail.links.first().type)
        assertEquals("English", detail.links.first().language)
        assertEquals("INFO", detail.links.last().type)
    }

    @Test fun ghibli_films_require_valid_identity_and_match_the_original_movie() {
        val films = GhibliClient.parse(Json.parseToJsonElement("""[
            {"id":"totoro","title":"My Neighbor Totoro","original_title":"となりのトトロ","release_date":"1988"},
            {"id":"invalid","title":"Missing year"}]"""))
        assertEquals(1, films.size)
        assertNull(films.single().match(listOf(item.copy(year = 2026), item.copy(mediaType = "tv"))))
        assertEquals(item, films.single().match(listOf(item)))
        assertTrue(GhibliClient.parse(null).isEmpty())
    }

    @Test fun an_unavailable_ghibli_service_returns_an_empty_optional_catalog() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("fixture unavailable").body("unavailable".toResponseBody()).build()
        }.build()
        val provider = ProviderHttp(client, { null }, { _, _, _ -> })
        assertTrue(GhibliClient(provider).films().isEmpty())
    }
}
