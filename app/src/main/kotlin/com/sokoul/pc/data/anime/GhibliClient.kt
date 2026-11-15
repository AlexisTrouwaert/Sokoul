package com.sokoul.pc.data.anime

import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.kind
import com.sokoul.pc.data.providers.ProviderHttp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Independent adapter for the documented Ghibli film endpoint, not a streaming host. */
class GhibliClient(private val http: ProviderHttp) {
    suspend fun films(): List<GhibliFilm> = parse(http.get("ghibli",
        "https://ghibliapi.vercel.app/films".toHttpUrl(), "films", 7 * 24 * 3_600_000L))

    companion object {
        fun parse(root: JsonElement?): List<GhibliFilm> = (root as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>().mapNotNull { o ->
                fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val id = text("id") ?: return@mapNotNull null
                val title = text("title") ?: return@mapNotNull null
                val year = text("release_date")?.toIntOrNull() ?: return@mapNotNull null
                GhibliFilm(id, title, text("original_title"), text("original_title_romanised"), year)
            }.distinctBy { it.id }
    }
}

data class GhibliFilm(val id: String, val title: String, val original: String?, val romanised: String?, val year: Int) {
    fun match(candidates: List<TmdbItem>): TmdbItem? {
        val names = listOfNotNull(title, original, romanised).map(AnimeCategories::plain).toSet()
        return candidates.firstOrNull { item ->
            item.kind() == "movie" && item.year == year && 16 in item.genreIds &&
                listOfNotNull(item.title, item.originalTitle).any { AnimeCategories.plain(it) in names }
        }
    }
}
