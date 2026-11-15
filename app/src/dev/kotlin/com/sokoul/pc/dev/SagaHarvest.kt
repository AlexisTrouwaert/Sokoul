package com.sokoul.pc.dev

import com.sokoul.desktop.data.Catalog
import com.sokoul.desktop.data.CuratedSagas
import com.sokoul.desktop.data.DesktopDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the list of every saga found among TMDB's most voted films, into a resource file. TMDB has no "all collections
 * by popularity" call: a film's details name its collection, so the films are walked. Writes only public ids and names.
 */
private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

private fun get(url: String): JsonObject? {
    repeat(4) { attempt ->
        val result = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.code == 429) null else if (!r.isSuccessful) JsonObject(emptyMap()) else Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
            }
        }.getOrNull()
        if (result != null) return result
        Thread.sleep(800L * (attempt + 1))
    }
    return null
}

private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

private fun JsonObject.i(k: String) = this[k]?.jsonPrimitive?.intOrNull

fun main(args: Array<String>): Unit = runBlocking {
    val db = DesktopDb.open()
    val key = Catalog(db).key()
    db.close()
    val base = "https://api.themoviedb.org/3"

    // Names of what the platform rows would use, to check the ids by eye.
    listOf(213, 1024, 2739, 49, 3186, 285, 4330, 453).forEach { println("network $it = ${get("$base/network/$it?api_key=$key")?.s("name")}") }
    listOf(178464, 20580, 7429, 5358, 3166, 104, 82819, 150, 2).forEach { println("company $it = ${get("$base/company/$it?api_key=$key")?.s("name")}") }

    val pages = args[1].toInt()
    val gate = Semaphore(24)
    val films = withContext(Dispatchers.IO) {
        (1..pages).map { p ->
            async {
                gate.withPermit {
                    val o = get("$base/discover/movie?api_key=$key&sort_by=vote_count.desc&include_adult=false&page=$p")
                    (o?.get("results") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.i("id") }
                }
            }
        }.awaitAll().flatten().distinct()
    }
    println("films walked: ${films.size}")
    val found = withContext(Dispatchers.IO) {
        films.map { id ->
            async {
                gate.withPermit {
                    val o = get("$base/movie/$id?api_key=$key&language=en-US") ?: return@withPermit null
                    val c = o["belongs_to_collection"] as? JsonObject ?: return@withPermit null
                    Triple(c.i("id") ?: return@withPermit null, c.s("name").orEmpty(), o.i("vote_count") ?: 0)
                }
            }
        }.awaitAll().filterNotNull()
    }
    val votes = found.groupBy { it.first }.mapValues { (_, list) -> list.sumOf { it.third } }
    val curated = CuratedSagas.all.flatMap { it.collectionIds }.toSet()
    println("collections found: ${votes.size}")
    val kept = withContext(Dispatchers.IO) {
        votes.keys.filter { it !in curated }.map { id ->
            async {
                gate.withPermit {
                    val o = get("$base/collection/$id?api_key=$key&language=en-US") ?: return@withPermit null
                    val parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { !it.s("release_date").isNullOrBlank() }
                    if (parts.size < 2) null else Triple(id, o.s("name").orEmpty(), parts.size)
                }
            }
        }.awaitAll().filterNotNull()
    }.sortedByDescending { votes[it.first] ?: 0 }
    println("kept with 2+ released films: ${kept.size}")
    println("top: " + kept.take(25).joinToString { it.second })
    println("tail: " + kept.takeLast(8).joinToString { it.second })
    val out = buildJsonObject {
        put("source", "TMDB films by vote count, $pages pages")
        put("sagas", buildJsonArray {
            kept.forEach { (id, name, count) ->
                add(buildJsonObject {
                    put("id", id)
                    put("name", name)
                    put("films", count)
                })
            }
        })
    }
    File(args[0]).writeText(out.toString())
    kotlin.system.exitProcess(0)
}
