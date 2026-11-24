package com.sokoul.iptv.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** « Aussi sur » : plateformes d'abonnement françaises lues dans `watch/providers`. */
class TmdbProvidersTest {
    private fun client(answer: (String) -> String): TmdbClient {
        val http = OkHttpClient.Builder().addInterceptor { request ->
            val incoming = request.request()
            Response.Builder().request(incoming).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer(incoming.url.encodedPath).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val cache = object : TmdbCache {
            override suspend fun get(key: String): TmdbCached? = null
            override suspend fun put(key: String, json: String, fetchedAt: Long) = Unit
        }
        return TmdbClient(http, Json, cache)
    }

    @Test fun `series search has its own pages and keeps titles without a poster`() = runBlocking {
        val paths = mutableListOf<String>()
        val tmdb = client { path ->
            paths += path
            when (path) {
                "/3/search/multi" -> """{"total_pages":32,"results":[{"media_type":"movie","id":12,"title":"The Office film"}]}"""
                "/3/search/tv" -> """{"total_pages":1,"results":[{"id":12,"name":"The Office","first_air_date":"2005-03-24"}]}"""
                else -> error("Unexpected endpoint $path")
            }
        }
        assertEquals("movie", tmdb.searchPage("fixture", "The Office", 1).items.single().mediaType)
        val result = tmdb.searchPage("fixture", "The Office", 1, seriesOnly = true)
        val title = result.items.single()
        assertEquals(listOf("/3/search/multi", "/3/search/tv"), paths)
        assertEquals(1, result.totalPages)
        assertEquals("tv", title.mediaType)
        assertEquals("The Office", title.title)
        assertNull(title.poster)
    }

    @Test fun `season episodes use unique numbers in order and stay in their season`() = runBlocking {
        val tmdb = client { path ->
            assertEquals("/3/tv/17/season/2", path)
            """{"episodes":[
                {"season_number":2,"episode_number":5,"name":"Five"},
                {"season_number":2,"episode_number":1,"name":"One"},
                {"season_number":8,"episode_number":2},
                {"episode_number":5,"name":"Duplicate"},
                {"episode_number":3,"name":"Three"},
                {"episode_number":0}
            ]}"""
        }
        val episodes = tmdb.season("fixture", 17, 2)
        assertEquals(listOf(1, 3, 5), episodes.map { it.episode })
        assertEquals(listOf("One", "Three", "Five"), episodes.map { it.name })
    }

    @Test fun `abonnements de France seulement, par priorite`() {
        val o = Json.parseToJsonElement(
            """{"results":{
              "FR":{"flatrate":[{"provider_name":"Netflix","display_priority":3},{"provider_name":"Disney Plus","display_priority":1}],
                    "rent":[{"provider_name":"Apple TV","display_priority":2}]},
              "US":{"flatrate":[{"provider_name":"Hulu","display_priority":1}]}}}""",
        ) as JsonObject
        assertEquals(listOf("Disney Plus", "Netflix"), TmdbClient.parseProviders(o, "FR"))
        assertEquals(emptyList<String>(), TmdbClient.parseProviders(o, "BE"))
        assertEquals(emptyList<String>(), TmdbClient.parseProviders(null, "FR"))
    }
}
