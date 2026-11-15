package com.sokoul.pc.playback.sources
import com.sokoul.pc.playback.session.PlayFailureException
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.pc.playback.session.Readiness
import com.sokoul.pc.playback.session.SourceNotice
import com.sokoul.pc.playback.session.VersionOrigin
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.pc.data.anime.AnimeCategories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.concurrent.TimeUnit

/** Catalogue ranks retain the server order, including specials that do not count toward absolute episodes. */
internal object AnimeSamaParse {
    data class Target(val anime: Int, val season: Int, val episode: Int)
    fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
    fun objects(o: JsonObject, key: String) = (o[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun matches(work: JsonObject, normalized: List<String>): Boolean {
        val aliases = listOfNotNull(text(work, "name")) + (work["alternative_names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return aliases.any { AnimeCategories.plain(it) in normalized }
    }
    fun pending(root: JsonArray?, names: List<String>): Boolean {
        val normalized = names.map(AnimeCategories::plain)
        return root.orEmpty().filterIsInstance<JsonObject>().any { matches(it, normalized) && (it["cataloguePending"] as? JsonPrimitive)?.contentOrNull == "true" }
    }
    private fun seasonNumber(season: JsonObject): Int? {
        val name = AnimeCategories.plain(text(season, "name").orEmpty())
        if (Regex("""\b(films?|oavs?|ovas?|specials?|speciales?|speciaux)\b""").containsMatchIn(name)) return null
        return (season["number"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
            ?: Regex("""^(?:saison|saga)\s+(\d+)\b""").find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }
    fun target(root: JsonArray, names: List<String>, movie: Boolean, season: Int?, episode: Int?, absolute: Int?): Target? {
        val normalized = names.map(AnimeCategories::plain)
        root.forEachIndexed { ai, element ->
            val work = element as? JsonObject ?: return@forEachIndexed
            if (!matches(work, normalized)) return@forEachIndexed
            if ((work["cataloguePending"] as? JsonPrimitive)?.contentOrNull == "true") return@forEachIndexed
            val seasons = objects(work, "seasons")
            if (movie) {
                // A catalogue with several films cannot identify the requested film by its parent title alone.
                seasons.forEachIndexed { si, s ->
                    if (text(s, "name").orEmpty().contains("film", true)) {
                        val episodes = objects(s, "episodes")
                        val exact = episodes.indexOfFirst { text(it, "name")?.let(AnimeCategories::plain) in normalized }
                        if (exact >= 0) return Target(ai + 1, si + 1, exact + 1)
                        if (episodes.size == 1) return Target(ai + 1, si + 1, 1)
                    }
                }
            } else {
                var left = absolute
                val numbered = seasons.withIndex().mapNotNull { entry -> seasonNumber(entry.value)?.let { number -> entry to number } }
                    .distinctBy { it.second }.sortedBy { it.second }.map { it.first }
                for ((si, s) in numbered) {
                    val count = objects(s, "episodes").size
                    if (left != null) {
                        if (left in 1..count) return Target(ai + 1, si + 1, left)
                        left -= count
                    } else if (seasonNumber(s) == season && episode != null && episode in 1..count) {
                        return Target(ai + 1, si + 1, episode)
                    }
                }
            }
        }
        return null
    }

    fun links(root: JsonArray, target: Target, serverBase: String? = null, allowLocal: Boolean = false): List<WebLink> {
        val work = root.getOrNull(target.anime - 1) as? JsonObject ?: return emptyList()
        val s = objects(work, "seasons").getOrNull(target.season - 1) ?: return emptyList()
        val e = objects(s, "episodes").getOrNull(target.episode - 1) ?: return emptyList()
        val resolved = (e["m3u8ByPlayer"] as? JsonObject).orEmpty()
        val metadata = e["mediaByPlayer"] as? JsonObject
        return objects(e, "streaming_links").flatMap { link ->
            val language = text(link, "language")?.let(WebParse::languageOf)
            (link["players"] as? JsonArray).orEmpty().mapNotNull { player ->
                val embed = (player as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val url = (resolved[embed] as? JsonPrimitive)?.contentOrNull
                if (url == null) {
                    if (!allowLocal || text(work, "extractionStatus") !in setOf("resolved", "unavailable") || !NativeEmbedResolver.supported(embed)) return@mapNotNull null
                    val host = runCatching { URI(embed).host }.getOrNull() ?: return@mapNotNull null
                    return@mapNotNull WebLink("Anime-Sama", "${language ?: "?"} · $host", embed, language, false, localExtraction = true)
                }
                val media = (metadata?.get(embed) as? JsonObject)?.takeIf { text(it, "url") == null || text(it, "url") == url }
                val format = media?.let { text(it, "format") }
                if (!SourceMedia.playable(url, format, serverBase)) return@mapNotNull null
                val host = runCatching { URI(embed).host }.getOrNull() ?: return@mapNotNull null
                WebLink("Anime-Sama", "${language ?: "?"} \u00b7 $host", url, language, SourceMedia.hls(url, format),
                    media?.let(SourceMedia::headers).orEmpty(), media?.let { SourceMedia.subtitles(it, serverBase) }.orEmpty(), SourceMedia.type(url, format))
            }
        }.distinctBy { it.url to it.language }
    }
}

/** Uses Mainapi for catalogue lookup and host extraction; never opens embed HTML in the player. */
class AnimeSamaSource(http: OkHttpClient, private val baseUrl: () -> String?, private val accessKey: () -> String?, private val probe: StreamProbe, private val localResolver: NativeEmbedResolver? = null) {
    data class Result(val versions: List<PlayVersion> = emptyList(), val notices: List<SourceNotice> = emptyList())
    private val client = http.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun find(request: PlayRequest, names: List<String>, absolute: Int?): Result = withTimeoutOrNull(60_000L) {
        var unavailable = false
        for (name in names.distinct()) {
            var lookup = get(name)
            if (lookup.unavailable) unavailable = true
            var catalogue = lookup.payload
            fun mapped(payload: JsonArray?) = payload?.let { AnimeSamaParse.target(it, names, request.kind == "movie", request.season, request.episode, absolute) }
            var target = mapped(catalogue)
            var retries = 0
            while (target == null && AnimeSamaParse.pending(catalogue, names) && retries < 8) {
                delay(1_000L)
                retries++
                lookup = get(name)
                if (lookup.unavailable) { unavailable = true; break }
                catalogue = lookup.payload
                target = mapped(catalogue)
            }
            if (target == null && AnimeSamaParse.pending(catalogue, names)) unavailable = true
            val selected = target ?: continue
            val resolved = get(name, selected).payload ?: return@withTimeoutOrNull Result(notices = listOf(SourceNotice.AnimeSamaUnavailable))
            val work = resolved.getOrNull(selected.anime - 1) as? JsonObject
            if (work?.let { AnimeSamaParse.text(it, "extractionStatus") } == "vip_required") return@withTimeoutOrNull Result(notices = listOf(SourceNotice.AnimeSamaVipRequired))
            val versions = AnimeSamaParse.links(resolved, selected, baseUrl(), localResolver != null).map { link ->
                PlayVersion(key = "anime-sama-${link.url.hashCode().toUInt()}", origin = VersionOrigin.Web,
                    name = "Anime-Sama \u00b7 ${link.name}", language = link.language, readiness = Readiness.Likely,
                    anime = true, announcedAudio = if (link.language == AudioLanguage.VF) listOf("FR") else emptyList(),
                    providerHost = URI(link.url).host,
                    open = { _, _ ->
                        if (link.localExtraction) return@PlayVersion localResolver!!.resolve(link.url, request.title)
                        failureOf(probe.check(link.url, link.headers["User-Agent"], link.headers["Referer"]), request.kind == "movie")?.let { throw PlayFailureException(it) }
                        StreamRequest(link.url, link.headers["User-Agent"], link.headers["Referer"], request.title, live = false, mediaType = link.mediaType, externalSubtitles = link.subtitles)
                    })
            }
            return@withTimeoutOrNull Result(versions, if (versions.isEmpty()) listOf(SourceNotice.AnimeSamaUnavailable) else emptyList())
        }
        Result(notices = if (unavailable) listOf(SourceNotice.AnimeSamaUnavailable) else emptyList())
    } ?: Result(notices = listOf(SourceNotice.AnimeSamaUnavailable))

    private data class Lookup(val payload: JsonArray? = null, val unavailable: Boolean = false)

    private suspend fun get(name: String, target: AnimeSamaParse.Target? = null): Lookup = withContext(Dispatchers.IO) {
        val base = baseUrl()?.trim()?.trimEnd('/')?.toHttpUrlOrNull() ?: return@withContext Lookup(unavailable = true)
        val url = base.newBuilder().addPathSegments("anime/search").addPathSegment(name).apply {
            target?.let { addQueryParameter("resolve", "1"); addQueryParameter("anime", "${it.anime}"); addQueryParameter("season", "${it.season}"); addQueryParameter("episode", "${it.episode}") }
        }.build()
        try {
            client.newCall(Request.Builder().url(url).apply { accessKey()?.let { header("x-access-key", it) } }.build()).awaitSourceResponse().use { response ->
                if (!response.isSuccessful) Lookup(unavailable = true) else {
                    val payload = response.body?.string()?.let { json.parseToJsonElement(it) as? JsonArray }
                    Lookup(payload, unavailable = payload == null)
                }
            }
        } catch (c: CancellationException) { throw c } catch (_: Exception) { Lookup(unavailable = true) }
    }
}
