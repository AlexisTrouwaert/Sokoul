package com.sokoul.pc.dev

import com.sokoul.desktop.data.Catalog
import com.sokoul.desktop.data.CuratedSagas
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.ProviderConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Writes every candidate logo for each saga to a local HTML sheet, to pick them by eye. Only public image URLs are
 * written: API keys go in the requests and never in the output.
 */
private val http = OkHttpClient()

private fun getJson(url: String, secret: String): JsonObject? = runCatching {
    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) null else Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
    }
}.getOrNull().also { check(secret.isNotEmpty()) }

private fun list(o: JsonObject?, field: String) = (o?.get(field) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

fun main(args: Array<String>): Unit = runBlocking {
    val db = DesktopDb.open()
    val catalog = Catalog(db)
    val tmdbKey = catalog.key()
    val fanartKey = ProviderConfig.key(db, "fanart").orEmpty()
    val html = StringBuilder("<!doctype html><meta charset=utf-8><style>body{background:#333;color:#fff;font:14px Arial;margin:8px}h3{margin:10px 0 4px}div.c{display:inline-block;width:190px;height:96px;margin:3px;background:#555;vertical-align:top;font-size:10px;overflow:hidden}img{max-width:186px;max-height:78px;display:block}</style>")
    for (saga in CuratedSagas.all) {
        val films = saga.collectionIds.flatMap { id ->
            list(getJson("https://api.themoviedb.org/3/collection/$id?api_key=$tmdbKey&language=en-US", tmdbKey), "parts")
        }.sortedBy { it.s("release_date")?.takeIf { d -> d.isNotBlank() } ?: "9999" }
        val collectionLogos = saga.collectionIds.sumOf { id -> list(getJson("https://api.themoviedb.org/3/collection/$id/images?api_key=$tmdbKey", tmdbKey), "logos").size }
        println("${saga.key}: films=${films.size} collectionLogos=$collectionLogos first=${films.firstOrNull()?.s("title")}")
        html.append("<h3>${saga.key} (${films.joinToString(", ") { it.s("id") + ":" + it.s("title")?.take(18) }.take(160)})</h3>")
        var shown = 0
        val only = System.getenv("AUDIT_SAGA")
        if (only != null && saga.key != only) continue
        for (film in if (only != null) films else films.take(4)) {
            val id = film.s("id") ?: continue
            val tmdb = list(getJson("https://api.themoviedb.org/3/movie/$id/images?api_key=$tmdbKey&include_image_language=en,null", tmdbKey), "logos")
                .filter { !it.s("file_path").orEmpty().endsWith(".svg") }.take(3)
                .map { "T" to "https://image.tmdb.org/t/p/w300" + it.s("file_path") }
            val fanart = if (fanartKey.isEmpty()) emptyList() else list(getJson("https://webservice.fanart.tv/v3/movies/$id?api_key=$fanartKey", fanartKey), "hdmovielogo")
                .filter { it.s("lang") in setOf("en", "00", "") }.take(4)
                .map { "F" to it.s("url").orEmpty().replace(" ", "%20") }
            (tmdb + fanart).forEach { (source, url) ->
                html.append("<div class=c><img src=\"$url\" loading=eager>$source $id ${url.substringAfterLast('/').take(26)}</div>")
                File(args[1]).appendText("${saga.key}|$source|$id|$url\n")
                shown++
            }
        }
        println("   candidates=$shown")
    }
    File(args[0]).writeText(html.toString())
    db.close()
    kotlin.system.exitProcess(0)
}
