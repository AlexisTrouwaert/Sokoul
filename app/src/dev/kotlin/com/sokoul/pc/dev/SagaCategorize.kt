package com.sokoul.pc.dev

import com.sokoul.desktop.data.Catalog
import com.sokoul.desktop.data.CuratedSagas
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.pc.data.SagaCategories
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
import kotlin.system.exitProcess

/**
 * Adds a category to each saga of `all_sagas.json`, after dev/SagaHarvest. A collection's films carry their genres, and
 * the keywords of its most voted films tell superheroes apart. Writes only public ids, names and categories.
 */
private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

private fun fetch(url: String): JsonObject? {
    repeat(4) { attempt ->
        val text = runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
        if (text != null) return Json.parseToJsonElement(text) as? JsonObject
        Thread.sleep(800L * (attempt + 1))
    }
    return null
}

private const val BASE = "https://api.themoviedb.org/3"

private fun categoryOf(key: String, ids: List<Int>): String? {
    val parts = ids.flatMap { id -> (fetch("$BASE/collection/$id?api_key=$key&language=en-US")?.get("parts") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } }
        .filter { !it["release_date"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
    if (parts.isEmpty()) return null
    val top = parts.sortedByDescending { it["vote_count"]?.jsonPrimitive?.intOrNull ?: 0 }.take(2).mapNotNull { it["id"]?.jsonPrimitive?.intOrNull }
    val keywords = top.flatMap { film ->
        (fetch("$BASE/movie/$film/keywords?api_key=$key")?.get("keywords") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
    }
    val genres = parts.map { p -> (p["genre_ids"] as? JsonArray).orEmpty().map { it.jsonPrimitive.int } }
    return SagaCategories.of(genres, keywords).name
}

fun main(args: Array<String>): Unit = runBlocking {
    val db = DesktopDb.open()
    val key = Catalog(db).key()
    db.close()
    val gate = Semaphore(20)
    val file = File(args[0])
    val root = Json.parseToJsonElement(file.readText()) as JsonObject
    val sagas = (root["sagas"] as JsonArray).map { it as JsonObject }
    val categorized = withContext(Dispatchers.IO) {
        sagas.map { s -> async { gate.withPermit { s to categoryOf(key, listOf(s["id"]!!.jsonPrimitive.int)) } } }.awaitAll()
    }
    val curated = withContext(Dispatchers.IO) {
        CuratedSagas.all.map { s -> async { gate.withPermit { s.key to categoryOf(key, s.collectionIds) } } }.awaitAll()
    }
    val out = buildJsonObject {
        put("source", root["source"]!!)
        put("curated", buildJsonObject { curated.forEach { (k, c) -> if (c != null) put(k, c) } })
        put("sagas", buildJsonArray {
            categorized.forEach { (s, c) ->
                add(buildJsonObject {
                    s.forEach { (k, v) -> if (k != "category") put(k, v) }
                    if (c != null) put("category", c)
                })
            }
        })
    }
    file.writeText(out.toString())
    categorized.map { it.second }.groupingBy { it }.eachCount().forEach { (c, n) -> println("$c: $n") }
    println("curated: " + curated.joinToString { "${it.first}=${it.second}" })
    exitProcess(0)
}
