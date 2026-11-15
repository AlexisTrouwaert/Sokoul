package com.sokoul.pc.playback.sources
import com.sokoul.pc.playback.session.PlayFailureException
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.pc.playback.session.Readiness
import com.sokoul.pc.playback.session.VersionOrigin
import com.sokoul.pc.playback.ui.label
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.iptv.data.debrid.ReleaseName
import com.sokoul.pc.data.anime.AnimeCategories
import com.sokoul.pc.data.anime.AnimeIdentityResolver
import com.sokoul.iptv.data.net.TmdbInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/** A direct stream returned by the user's configured AniProject-compatible backend. */
data class AniProjectStream(val url: String, val label: String, val hls: Boolean)

/** Parses the website backend's public response contract without importing its licensed source. */
object AniProjectParse {
    private const val YEAR_TOLERANCE = 1
    private fun JsonObject.value(name: String) = this[name]
    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.objects(name: String) = (this[name] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun JsonObject.results() = objects("results")

    data class Candidate(val id: String, val title: String, val year: Int?)
    data class Episode(val id: String, val number: Int)

    fun aniwatchSearch(root: JsonElement?): List<Candidate> = (root as? JsonObject)?.results().orEmpty().mapNotNull { o ->
        Candidate(o.text("id") ?: return@mapNotNull null, o.text("name") ?: return@mapNotNull null, year(o.text("name")))
    }

    fun consumetSearch(root: JsonElement?): List<Candidate> = (root as? JsonObject)?.results().orEmpty().mapNotNull { o ->
        Candidate(o.text("id") ?: return@mapNotNull null, o.text("title") ?: return@mapNotNull null, year(o.text("releaseDate")))
    }

    fun episodes(root: JsonElement?): List<Episode> = (root as? JsonObject)?.results().orEmpty().mapNotNull { o ->
        Episode(o.text("episodeId") ?: o.text("id") ?: return@mapNotNull null, o.int("number") ?: return@mapNotNull null)
    }

    fun exactMatch(candidates: List<Candidate>, names: List<String>, year: Int?): Candidate? = candidates.firstOrNull { candidate ->
        AnimeCategories.plain(candidate.title) in names &&
            (year == null || candidate.year == null || kotlin.math.abs(year - candidate.year) <= YEAR_TOLERANCE)
    }

    fun streams(root: JsonElement?, category: String): List<AniProjectStream> {
        val results = (root as? JsonObject)?.value("results") as? JsonObject ?: return emptyList()
        return results.objects("sources").mapNotNull { source ->
            val url = source.text("url") ?: return@mapNotNull null
            val type = source.text("type").orEmpty().lowercase()
            val isHls = (source["isM3U8"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true || type in setOf("hls", "m3u8")
            if (!directMedia(url, isHls)) return@mapNotNull null
            val quality = source.text("quality")?.takeIf { it.isNotBlank() }
            AniProjectStream(url, listOfNotNull(category, quality).joinToString(" · "), isHls || url.substringBefore('?').endsWith(".m3u8", true))
        }.distinctBy(AniProjectStream::url)
    }

    /** Only media playlists/files reach VLC; an iframe or HTML player page is not a playable stream. */
    fun directMedia(url: String, hls: Boolean = false): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        val path = uri.path.orEmpty().lowercase()
        if (Regex("/(embed|player)(/|$)").containsMatchIn(path)) return false
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || (hls && ("m3u8" in path || "/hls/" in "$path/"))
    }

    private fun year(value: String?): Int? = Regex("(?:19|20)\\d{2}").find(value.orEmpty())?.value?.toIntOrNull()
}

/** Calls the AniProject backend routes only when explicitly configured. Every source is isolated and title matched. */
class AniProjectSources(
    http: OkHttpClient,
    private val backendUrl: () -> String?,
    private val animeIdentity: AnimeIdentityResolver?,
    private val probe: StreamProbe,
) {
    private val client = http.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun configured(): Boolean = base() != null

    suspend fun find(request: PlayRequest, info: TmdbInfo?): List<PlayVersion> {
        if (request.kind != "tv" || request.episode == null) return emptyList()
        val base = base() ?: return emptyList()
        val identity = animeIdentity?.identity(request.kind, request.tmdbId, info)
        val reference = if (animeIdentity != null && request.season != null) {
            animeIdentity.episode(identity, request.tmdbId, request.season, request.episode)
        } else null
        val targetEpisode = reference?.absolute ?: request.episode.takeIf { request.season == null || request.season == 1 } ?: return emptyList()
        val names = (identity?.names.orEmpty() + listOfNotNull(info?.originalTitle, request.title))
            .map(AnimeCategories::plain).filter(String::isNotBlank).distinct()
        if (names.isEmpty()) return emptyList()

        return coroutineScope {
            val work = mutableListOf(
                async { isolated { aniwatch(base, request, names, info?.year, targetEpisode) }.orEmpty() },
            )
            listOf("gogoanime", "zoro").forEach { provider ->
                work += async { isolated { consumet(base, request, names, info?.year, targetEpisode, provider) }.orEmpty() }
            }
            work.awaitAll().flatten().distinctBy(PlayVersion::key)
        }
    }

    private suspend fun aniwatch(base: HttpUrl, request: PlayRequest, names: List<String>, year: Int?, episode: Int): List<PlayVersion> {
        val work = names.firstNotNullOfOrNull { name ->
            AniProjectParse.exactMatch(AniProjectParse.aniwatchSearch(get(base, "/search/anime/aniwatch", "query" to name)), names, year)
        } ?: return emptyList()
        val list = get(base, "/episodes/aniwatch/all", "id" to work.id) ?: return emptyList()
        val target = AniProjectParse.episodes(list).firstOrNull { it.number == episode } ?: return emptyList()
        val out = mutableListOf<PlayVersion>()
        for (category in listOf("sub", "dub")) {
            val payload = get(base, "/episodes/aniwatch/episode", "id" to target.id, "category" to category) ?: continue
            out += versions("Aniwatch", AniProjectParse.streams(payload, category), request, target.id)
        }
        return out
    }

    private suspend fun consumet(base: HttpUrl, request: PlayRequest, names: List<String>, year: Int?, episode: Int, provider: String): List<PlayVersion> {
        val work = names.firstNotNullOfOrNull { name ->
            AniProjectParse.exactMatch(AniProjectParse.consumetSearch(get(base, "/search/anime/consumet/$provider", "query" to name)), names, year)
        } ?: return emptyList()
        val list = get(base, "/episodes/consumet/$provider/all", "id" to work.id) ?: return emptyList()
        val target = AniProjectParse.episodes(list).firstOrNull { it.number == episode } ?: return emptyList()
        val payload = get(base, "/episodes/consumet/$provider/episode", "id" to target.id) ?: return emptyList()
        return versions("Consumet · ${provider.replaceFirstChar(Char::uppercase)}", AniProjectParse.streams(payload, "sub/dub"), request, target.id)
    }

    private fun versions(provider: String, streams: List<AniProjectStream>, request: PlayRequest, episodeId: String) = streams.mapIndexed { index, stream ->
        val release = ReleaseName.parse(stream.label)
        val host = runCatching { URI(stream.url).host }.getOrNull()
        PlayVersion(
            key = "anime-api-${provider.lowercase().replace(' ', '-')}-${episodeId.hashCode()}-$index-${stream.url.hashCode().toUInt()}",
            origin = VersionOrigin.Web,
            name = "$provider · ${stream.label}",
            language = com.sokoul.iptv.domain.AudioLanguage.VO,
            quality = release.quality,
            hdr = release.hdr,
            readiness = Readiness.Likely,
            providerHost = host,
            anime = true,
            open = { _, _ ->
                failureOf(probe.check(stream.url, null), film = false)?.let { throw PlayFailureException(it) }
                StreamRequest(stream.url, null, null, request.title, live = false)
            },
        )
    }

    private suspend fun get(base: HttpUrl, route: String, vararg query: Pair<String, String>): JsonElement? = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments(route.trimStart('/')).apply { query.forEach { addQueryParameter(it.first, it.second) } }.build()
        runCatching {
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()?.let { json.parseToJsonElement(it) }
            }
        }.getOrElse { if (it is CancellationException) throw it else null }
    }

    private fun base(): HttpUrl? = backendUrl()?.trim()?.trimEnd('/')?.toHttpUrlOrNull()?.takeIf { url ->
        url.isHttps || url.host in setOf("localhost", "127.0.0.1", "10.0.2.2") || url.host.startsWith("192.168.") || url.host.startsWith("10.") ||
            (url.host.startsWith("172.") && url.host.substringAfter("172.").substringBefore('.').toIntOrNull()?.let { it in 16..31 } == true)
    }

    private suspend fun <T> isolated(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val CALL_TIMEOUT_SECONDS = 12L
    }
}
