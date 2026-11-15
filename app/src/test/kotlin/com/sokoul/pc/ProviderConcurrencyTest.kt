package com.sokoul.pc

import com.sokoul.pc.data.providers.ProviderHttp
import com.sokoul.pc.data.anime.FanSources
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProviderConcurrencyTest {
    private val url = "https://fixture.invalid/data".toHttpUrl()
    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .header("Retry-After", "60").body(body.toResponseBody("application/json".toMediaType())).build()

    @Test fun duplicate_requests_use_one_successful_response() = runBlocking {
        val count = AtomicInteger()
        val store = ConcurrentHashMap<String, Pair<String, Long>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet(); Thread.sleep(40)
            response(chain.request(), 200, """{"ok":true}""")
        }.build()
        val provider = ProviderHttp(client, store::get, { k, v, t -> store[k] = v to t })
        val results = (1..20).map { async { provider.get("fixture", url, "same", 60_000) } }.awaitAll()
        assertEquals(1, count.get())
        assertTrue(results.all { it != null })
    }

    @Test fun queued_calls_honor_a_new_cooldown() = runBlocking {
        val count = AtomicInteger()
        val entered = CountDownLatch(2)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val index = count.incrementAndGet()
            entered.countDown()
            check(entered.await(2, TimeUnit.SECONDS))
            if (index != 1) Thread.sleep(100)
            response(chain.request(), if (index == 1) 429 else 200, "{}")
        }.build()
        val provider = ProviderHttp(client, { null }, { _, _, _ -> })
        (1..10).map { async { provider.get("fixture", url, "different-$it", 60_000) } }.awaitAll()
        assertEquals(2, count.get())
    }

    @Test fun graphql_errors_are_not_saved_as_successful_rows() = runBlocking {
        val store = ConcurrentHashMap<String, Pair<String, Long>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, """{"errors":[{"message":"temporary error"}]}""")
        }.build()
        val provider = ProviderHttp(client, store::get, { k, v, t -> store[k] = v to t })
        assertNull(provider.get("anilist", url, "error", 60_000))
        assertTrue(store.isEmpty())
    }

    @Test fun filtered_manga_pages_do_not_hide_later_readable_chapters() = runBlocking {
        val offsets = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val offset = chain.request().url.queryParameter("offset")
            offsets += offset
            val body = if (offset == "0") """{"total":2,"data":[{"id":"removed","attributes":{"pages":0,"translatedLanguage":"fr"}}]}"""
                else """{"total":2,"data":[{"id":"readable","attributes":{"chapter":"2","pages":20,"translatedLanguage":"en"}}]}"""
            response(chain.request(), 200, body)
        }.build()
        val fans = FanSources(ProviderHttp(client, { null }, { _, _, _ -> }))
        assertEquals(listOf("readable"), fans.chapters("fixture", listOf("fr", "en")).map { it.id })
        assertEquals(listOf("0", "1"), offsets)
    }

    @Test fun old_cached_graphql_errors_are_replaced_by_usable_data() = runBlocking {
        val store = ConcurrentHashMap<String, Pair<String, Long>>()
        store["anilist:row"] = """{"errors":[{"message":"old failure"}]}""" to System.currentTimeMillis()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            response(chain.request(), 200, """{"data":{"Page":{"media":[]}}}""")
        }.build()
        val provider = ProviderHttp(client, store::get, { k, v, t -> store[k] = v to t })
        assertNotNull(provider.get("anilist", url, "row", 60_000))
        assertEquals(1, calls.get())
        assertTrue(store.getValue("anilist:row").first.contains("media"))
    }
}
