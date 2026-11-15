package com.sokoul.iptv.data.net

import com.sokoul.iptv.data.anime.SkipInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Intros and credits of series and films from IntroDB (public API, no key, by IMDb id). Anime keep AniSkip; this
 * covers the rest. Answers are kept by [cache] for a week: segments rarely change.
 */
class IntroDb(
    private val http: OkHttpClient,
    private val cache: TmdbCache? = null,
    private val base: HttpUrl = "https://api.introdb.app/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun segments(imdbId: String, season: Int?, episode: Int?): List<SkipInterval> {
        if (!Regex("^tt\\d{5,10}$").matches(imdbId)) return emptyList()
        val url = base.newBuilder().addPathSegment("segments").addQueryParameter("imdb_id", imdbId).apply {
            if (season != null && episode != null) {
                addQueryParameter("season", season.toString())
                addQueryParameter("episode", episode.toString())
            }
        }.build().toString()
        val key = "introdb:$url"
        val cached = cache?.get(key)?.takeIf { System.currentTimeMillis() - it.fetchedAt < WEEK_MS }?.json
        val body = cached ?: withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    // 404: nobody submitted this episode yet. Kept, so it is not asked again every play.
                    if (r.code == 404) "{}" else if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
        }?.also { if (cached == null) cache?.put(key, it, System.currentTimeMillis()) } ?: return emptyList()
        return parse(body)
    }

    companion object {
        private const val WEEK_MS = 7 * 24 * 3_600_000L

        /** Intro and outro, with no episode length (IntroDB gives none): the player keeps those that fit the file. */
        fun parse(body: String): List<SkipInterval> {
            val o = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            fun interval(name: String, opening: Boolean): SkipInterval? {
                val s = o[name] as? JsonObject ?: return null
                val start = s["start_sec"]?.jsonPrimitive?.doubleOrNull ?: return null
                val end = s["end_sec"]?.jsonPrimitive?.doubleOrNull ?: return null
                return if (end > start) SkipInterval(opening, start, end, 0.0) else null
            }
            return listOfNotNull(interval("intro", true), interval("outro", false))
        }
    }
}
