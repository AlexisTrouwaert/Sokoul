package com.sokoul.iptv.data.anime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A release the SeaDex community keeps as the best (or an alternative) for an anime, with the files it holds. */
data class SeaDexRelease(val infoHash: String, val group: String?, val best: Boolean, val dualAudio: Boolean, val files: List<Pair<String, Long>>)

/** An opening or ending, in seconds, measured on a file of [episodeLength] seconds. */
data class SkipInterval(val opening: Boolean, val start: Double, val end: Double, val episodeLength: Double)

object AnimeExtrasParse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * SeaDex (`releases.moe` PocketBase, `entries` with `expand=trs`). Torrents of private trackers have their hash
     * hidden ("<redacted>"): they cannot be fetched and are left out.
     */
    fun seaDex(text: String): List<SeaDexRelease> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        return (root["items"] as? JsonArray).orEmpty().flatMap { item ->
            (((item as? JsonObject)?.get("expand") as? JsonObject)?.get("trs") as? JsonArray).orEmpty()
        }.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val hash = (o["infoHash"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return@mapNotNull null
            val files = (o["files"] as? JsonArray).orEmpty().mapNotNull { f ->
                val fo = f as? JsonObject ?: return@mapNotNull null
                val name = (fo["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                name to ((fo["length"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }
            SeaDexRelease(
                hash, (o["releaseGroup"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                (o["isBest"] as? JsonPrimitive)?.booleanOrNull == true, (o["dualAudio"] as? JsonPrimitive)?.booleanOrNull == true, files,
            )
        }.distinctBy { it.infoHash }
    }

    /** AniSkip v2 `skip-times`: the opening and the ending found for an episode. */
    fun aniSkip(text: String): List<SkipInterval> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        if ((root["found"] as? JsonPrimitive)?.booleanOrNull != true) return emptyList()
        return (root["results"] as? JsonArray).orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val type = (o["skipType"] as? JsonPrimitive)?.contentOrNull
            val i = o["interval"] as? JsonObject ?: return@mapNotNull null
            val start = (i["startTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val end = (i["endTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val length = (o["episodeLength"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            if (end <= start || (type != "op" && type != "ed")) return@mapNotNull null
            SkipInterval(type == "op", start, end, length)
        }
    }

    /**
     * The intervals that fit the file being played: measured on a file of nearly the same length (±5 %). Measured on
     * another cut (a recap, a dub with a longer opening), they would jump to the wrong place.
     */
    fun fitting(intervals: List<SkipInterval>, fileSeconds: Double): List<SkipInterval> {
        if (fileSeconds <= 0) return emptyList()
        // No length (IntroDB): only kept when it ends inside the file.
        return intervals.filter {
            (it.episodeLength <= 0 || kotlin.math.abs(it.episodeLength - fileSeconds) <= fileSeconds * SKIP_TOLERANCE) && it.end <= fileSeconds + 1
        }
            .groupBy { it.opening }.values.map { it.first() }
    }

    private const val SKIP_TOLERANCE = 0.05
}
