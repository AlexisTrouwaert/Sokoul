package com.sokoul.pc.playback.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Anime-Sama's pages read the way the Sokoul server reads them (`API/Mainapi/services/animeSamaCatalog.js` and the
 * `animeSama` route), so that the app can do it alone when the server is off.
 */
internal object AnimeSamaSite {
    const val LINK_PATH = "/api/lien.php"
    /** Language pages a season may have, in the server's order: the first is read for the episode count. */
    val LANGUAGES = listOf("vostfr", "vf", "vosteng", "vostspa", "vj", "va", "vf1", "vf2", "vkr")
    private const val PROXY_PREFIX = "https://proxy.liyao.space/------"

    data class Result(val url: String, val name: String, val alternativeNames: List<String>)
    data class Panel(val name: String, val url: String, val number: Int?)
    data class Reference(val id: String, val page: String, val origin: String)

    private val rxComments = listOf(Regex("<!--[\\s\\S]*?-->"), Regex("/\\*[\\s\\S]*?\\*/"))
    private val rxLineComment = Regex("(^|[^:\"'\\\\])//[^\\n]*")
    fun stripComments(source: String): String =
        rxComments.fold(source) { text, rx -> text.replace(rx, "") }.replace(rxLineComment, "$1")

    private val rxAnchor = Regex("<a\\b([^>]*\\bclass=\"asn-search-result\"[^>]*)>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
    private val rxTag = Regex("<[^>]+>")

    /** The search page's results: catalogue link, title and alternative names. */
    fun searchResults(html: String): List<Result> = rxAnchor.findAll(html).mapNotNull { m ->
        val href = Regex("href=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
        if (!Regex("/catalogue/[a-zA-Z0-9][a-zA-Z0-9\\-_.]+").containsMatchIn(href)) return@mapNotNull null
        val inner = m.groupValues[2]
        val title = Regex("<h3\\b[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)?.replace(rxTag, "")?.trim().orEmpty()
        if (title.isEmpty()) return@mapNotNull null
        val subtitle = Regex("<p\\b[^>]*class=\"asn-search-result-subtitle\"[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(inner)
            ?.groupValues?.get(1)?.replace(rxTag, "")?.trim().orEmpty()
        Result(href, title, subtitle.split(',').map(String::trim).filter(String::isNotEmpty))
    }.toList()

    private val rxPanel = Regex("panneauAnime\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+?)/?(?:vostfr|vf\\d*|vosteng|vostspa|vj|va|vkr)?\"\\s*\\)")

    /** Season cards declared by `panneauAnime("name", "saison1/vostfr")`, commented and template cards excluded. */
    fun seasonPanels(html: String, catalogueUrl: String): List<Panel> {
        val base = catalogueUrl.trimEnd('/')
        val slug = base.substringAfterLast('/')
        val seen = HashSet<String>()
        return rxPanel.findAll(stripComments(html)).mapNotNull { m ->
            val name = m.groupValues[1].trim()
            var link = m.groupValues[2].trim().trimEnd('/')
            if (name.isEmpty() || link.isEmpty() || name == "nom" || link == "url") return@mapNotNull null
            if (slug.isNotEmpty() && link.startsWith("$slug/")) link = link.removePrefix("$slug/")
            link = link.trimStart('/')
            val url = "$base/$link"
            if (!seen.add(url)) return@mapNotNull null
            Panel(name, url, Regex("^saison(\\d+)$", RegexOption.IGNORE_CASE).find(link)?.groupValues?.get(1)?.toIntOrNull())
        }.toList()
    }

    /** Languages a season page declares (`var languesDispo = ["vostfr","vf"]`), or null when it says nothing. */
    fun availableLanguages(html: String?): List<String>? {
        val raw = Regex("languesDispo\\s*=\\s*(\\[[^\\]]*\\])").find(html ?: return null)?.groupValues?.get(1) ?: return null
        val list = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return null
        return list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.matches(Regex("[a-z0-9]{1,10}")) } }
    }

    private fun meta(source: String): Map<String, List<String>> {
        val raw = Regex("epsMeta\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;").find(source)?.groupValues?.get(1) ?: return emptyMap()
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        return o.mapValues { (_, v) -> (v as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty() }
    }

    private fun arrays(source: String): List<List<String>> {
        val byIndex = sortedMapOf<Int, List<String>>()
        Regex("\\beps(\\d+)\\s*=\\s*\\[([\\s\\S]*?)\\]").findAll(source).forEach { m ->
            val entries = Regex("([\"'])((?:\\\\.|(?!\\1).)*)\\1").findAll(m.groupValues[2]).map { it.groupValues[2].trim() }.toList()
            byIndex[m.groupValues[1].toInt()] = entries
        }
        return byIndex.values.toList()
    }

    fun isPlayerUrl(url: String?): Boolean = url != null && Regex("^https?://[^/\\s]+\\.[^/\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun referenceUrl(page: HttpUrl, id: String, host: String): String =
        page.newBuilder().encodedPath(LINK_PATH).query(null).addQueryParameter("l", id).addQueryParameter("p", page.encodedPath)
            .apply { if (host.isNotEmpty()) addQueryParameter("h", host) }.build().toString()

    /**
     * Player references per episode, in player order: inline `epsN` arrays of a season page (or a legacy episodes.js).
     * A reference is kept without the expiring token; a video the site marks as removed goes last.
     */
    fun episodePlayers(source: String, pageUrl: String): List<List<String>> {
        val page = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val lists = arrays(stripComments(source))
        if (lists.isEmpty()) return emptyList()
        val meta = meta(source)
        val episodes = (0 until lists.maxOf { it.size }).map { index ->
            val live = ArrayList<String>()
            val flagged = ArrayList<String>()
            for (list in lists) {
                val entry = list.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: continue
                val reference = Regex("^lien:(\\d{1,12})$").find(entry)?.groupValues?.get(1)
                if (reference != null) {
                    val info = meta[reference].orEmpty()
                    val host = info.firstOrNull()?.takeIf { it.matches(Regex("[a-z0-9.-]{1,80}", RegexOption.IGNORE_CASE)) }.orEmpty()
                    (if (info.size > 1 && info[1].isNotEmpty() && info[1] !in setOf("false", "0", "null")) flagged else live) += referenceUrl(page, reference, host)
                    continue
                }
                val url = entry.removePrefix(PROXY_PREFIX)
                if (isPlayerUrl(url)) live += url
            }
            (live + flagged).distinct()
        }.toMutableList()
        while (episodes.isNotEmpty() && episodes.last().isEmpty()) episodes.removeAt(episodes.lastIndex)
        return episodes
    }

    /** A `lien.php` reference of an Anime-Sama catalogue page, or null for anything else. */
    fun reference(url: String): Reference? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.encodedPath != LINK_PATH || !parsed.host.matches(Regex("anime-sama\\.[a-z]{2,10}", RegexOption.IGNORE_CASE))) return null
        val id = parsed.queryParameter("l")?.takeIf { it.matches(Regex("\\d{1,12}")) } ?: return null
        val path = parsed.queryParameter("p")?.takeIf { it.matches(Regex("/catalogue/[^?#]+/")) } ?: return null
        val origin = "${parsed.scheme}://${parsed.host}" + if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) ":${parsed.port}" else ""
        return Reference(id, origin + path, origin)
    }

    /** The page's expiring token (`var epsJeton = "…"`). */
    fun token(html: String?): String? {
        if (html == null) return null
        return Regex("epsJeton\\s*=\\s*\"([^\"]{8,200})\"").find(html)?.groupValues?.get(1)
    }

    /** `name=value` pairs of the page's cookies, for the same-origin reference requests. */
    fun cookie(setCookies: List<String>): String = setCookies.map { it.substringBefore(';').trim() }
        .filter { it.matches(Regex("[A-Za-z0-9_-]+=[^;\\s]*")) }.joinToString("; ")
}

/**
 * DNS over HTTPS for the catalogue's hosts only: some providers answer a blocking address for anime-sama.*. Resolvers
 * are reached by IP; private, loopback or link-local answers are refused; every other host keeps the system resolver.
 */
internal class AnimeSamaDns(http: OkHttpClient, private val now: () -> Long = System::currentTimeMillis) : Dns {
    private val client = http.newBuilder().dns(Dns.SYSTEM).callTimeout(6, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (!CATALOGUE.matches(hostname)) return Dns.SYSTEM.lookup(hostname)
        cache[hostname]?.takeIf { it.first > now() }?.let { return it.second }
        for (resolver in RESOLVERS) {
            val answer = runCatching { ask(resolver, hostname) }.getOrNull() ?: continue
            cache[hostname] = (now() + answer.second * 1000L) to answer.first
            return answer.first
        }
        throw java.net.UnknownHostException("anime_sama_dns_unavailable")
    }

    private fun ask(resolver: String, hostname: String): Pair<List<InetAddress>, Long>? {
        val url = "$resolver?name=$hostname&type=A".toHttpUrlOrNull() ?: return null
        client.newCall(Request.Builder().url(url).header("Accept", "application/dns-json").build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return parse(response.body?.string() ?: return null)
        }
    }

    companion object {
        /** The catalogue hosts some providers block, as the server lists them (`API/Mainapi/utils/animeSamaDns.js`). */
        val CATALOGUE = Regex("(?:[a-z0-9-]+\\.)*(?:anime-sama|coflix|french-?stream)\\.[a-z]{2,10}", RegexOption.IGNORE_CASE)
        private val RESOLVERS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")

        /** Public A records of a DNS JSON answer and their shortest TTL (at least 60 s), or null when there is none. */
        fun parse(body: String): Pair<List<InetAddress>, Long>? {
            val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if ((o["Status"] as? JsonPrimitive)?.contentOrNull != "0") return null
            val records = (o["Answer"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "1" }
            val addresses = records.mapNotNull { r ->
                (r["data"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) }
                    ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            }.filter(com.sokoul.extract.PublicNetwork::isPublic)
            if (addresses.isEmpty()) return null
            val ttl = records.mapNotNull { (it["TTL"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.minOrNull() ?: 60L
            return addresses to ttl.coerceAtLeast(60L)
        }
    }
}

/**
 * Anime-Sama read by the app itself, in the shape of the Sokoul server's `/anime/search` answer so that
 * [AnimeSamaParse] matches and links it unchanged. Players are left for the local extractor (no relay).
 */
class AnimeSamaDirect(http: OkHttpClient, private val site: String = "https://anime-sama.to", private val now: () -> Long = System::currentTimeMillis) {
    private val client = http.newBuilder().dns(AnimeSamaDns(http)).callTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()
    private val catalogues = ConcurrentHashMap<String, Pair<Long, JsonArray>>()

    /** Works matching [name], with their seasons and the episode count of each (players of one language only). */
    suspend fun search(name: String): JsonArray? {
        catalogues[name]?.takeIf { it.first > now() }?.let { return it.second }
        val html = post("$site/template-php/defaut/fetch.php", name) ?: return null
        val works = coroutineScope {
            AnimeSamaSite.searchResults(html).take(MAX_WORKS).map { result -> async { work(result) } }.awaitAll()
        }
        val answer = JsonArray(works.filterNotNull())
        catalogues[name] = (now() + CATALOGUE_MS) to answer
        return answer
    }

    /** The works of [search] with the target episode's players in every declared language, references resolved. */
    internal suspend fun resolve(name: String, target: AnimeSamaParse.Target): JsonArray? {
        val root = search(name) ?: return null
        val work = root.getOrNull(target.anime - 1)?.jsonObject ?: return null
        val seasons = work["seasons"]?.jsonArray ?: return null
        val season = seasons.getOrNull(target.season - 1)?.jsonObject ?: return null
        val base = season["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val first = get("$base/${AnimeSamaSite.LANGUAGES.first()}/", referer = "$site/")
        val declared = AnimeSamaSite.availableLanguages(first)
        val languages = AnimeSamaSite.LANGUAGES.filter { declared == null || it == AnimeSamaSite.LANGUAGES.first() || it in declared }
        val links = coroutineScope {
            languages.map { language ->
                async {
                    val page = "$base/$language/"
                    val html = if (language == AnimeSamaSite.LANGUAGES.first()) first else get(page, referer = "$site/")
                    val players = html?.let { AnimeSamaSite.episodePlayers(it, page).getOrNull(target.episode - 1) }.orEmpty()
                    language to resolvePlayers(players)
                }
            }.awaitAll()
        }.filter { it.second.isNotEmpty() }
        val episodes = season["episodes"]?.jsonArray.orEmpty().mapIndexed { i, e ->
            if (i != target.episode - 1) e else buildJsonObject {
                e.jsonObject.forEach { (k, v) -> if (k != "streaming_links") put(k, v) }
                putJsonArray("streaming_links") {
                    links.forEach { (language, players) -> addJsonObject { put("language", language); putJsonArray("players") { players.forEach { add(it) } } } }
                }
            }
        }
        val resolvedSeason = JsonObject(season + ("episodes" to JsonArray(episodes)))
        val resolvedWork = JsonObject(work + ("seasons" to JsonArray(seasons.mapIndexed { i, s -> if (i == target.season - 1) resolvedSeason else s })) +
            ("extractionStatus" to JsonPrimitive("resolved")))
        return JsonArray(root.mapIndexed { i, w -> if (i == target.anime - 1) resolvedWork else w })
    }

    private suspend fun work(result: AnimeSamaSite.Result): JsonObject? {
        val url = absolute(result.url) ?: return null
        val html = get(url, referer = "$site/") ?: return null
        val panels = AnimeSamaSite.seasonPanels(html, url)
        val gate = Semaphore(PARALLEL)
        val counted = coroutineScope {
            panels.map { panel ->
                async {
                    // Only numbered seasons and films count episodes: one language page each, the first that has players.
                    gate.withPermit { panel to episodeCount(panel.url) }
                }
            }.awaitAll()
        }
        return buildJsonObject {
            put("name", result.name)
            putJsonArray("alternative_names") { result.alternativeNames.forEach { add(it) } }
            put("url", url)
            putJsonArray("seasons") {
                counted.forEach { (panel, count) ->
                    addJsonObject {
                        put("name", panel.name)
                        panel.number?.let { put("number", it) }
                        put("url", panel.url)
                        putJsonArray("episodes") { repeat(count) { addJsonObject { put("index", it + 1); putJsonArray("streaming_links") { } } } }
                    }
                }
            }
        }
    }

    private suspend fun episodeCount(seasonUrl: String): Int {
        for (language in listOf("vostfr", "vf")) {
            val page = "$seasonUrl/$language/"
            val html = get(page, referer = "$site/") ?: continue
            val count = AnimeSamaSite.episodePlayers(html, page).size
            if (count > 0) return count
        }
        return 0
    }

    /** Player URLs for one episode: references resolved with the page's token, others kept; unresolved ones dropped. */
    private suspend fun resolvePlayers(players: List<String>): List<String> {
        val sessions = HashMap<String, Pair<String, String>?>()
        suspend fun session(page: String, fresh: Boolean): Pair<String, String>? {
            if (!fresh && sessions.containsKey(page)) return sessions[page]
            val answer = fetch(page, referer = page.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" })
            val token = AnimeSamaSite.token(answer?.first)
            return token?.let { it to AnimeSamaSite.cookie(answer!!.second) }.also { sessions[page] = it }
        }
        return players.mapNotNull { player ->
            val reference = AnimeSamaSite.reference(player) ?: return@mapNotNull player.takeIf(AnimeSamaSite::isPlayerUrl)
            for (attempt in 0..1) {
                val (token, cookie) = session(reference.page, attempt > 0) ?: return@mapNotNull null
                val body = fetch("${reference.origin}${AnimeSamaSite.LINK_PATH}?j=${java.net.URLEncoder.encode(token, "UTF-8")}&l=${reference.id}",
                    referer = reference.page, cookie = cookie, sameOrigin = true)?.first
                val o = body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                val url = (o?.get("u") as? JsonPrimitive)?.contentOrNull?.trim()
                if (AnimeSamaSite.isPlayerUrl(url)) return@mapNotNull url
                if ((o?.get("erreur") as? JsonPrimitive)?.contentOrNull != "expire") return@mapNotNull null
            }
            null
        }.distinct()
    }

    private fun absolute(href: String): String? = when {
        href.startsWith("http") -> href.toHttpUrlOrNull()?.let { "$site${it.encodedPath}" }
        href.startsWith("/") -> "$site$href"
        else -> null
    }?.trimEnd('/')?.plus("/")

    private suspend fun get(url: String, referer: String?): String? = fetch(url, referer)?.first

    private suspend fun post(url: String, query: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(browser(Request.Builder().url(url), "$site/").post(FormBody.Builder().add("query", query).build()).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
    }

    /** Body and Set-Cookie headers of a GET, or null when the site does not answer it. */
    private suspend fun fetch(url: String, referer: String?, cookie: String? = null, sameOrigin: Boolean = false): Pair<String, List<String>>? = withContext(Dispatchers.IO) {
        val u = url.toHttpUrlOrNull() ?: return@withContext null
        try {
            val builder = browser(Request.Builder().url(u), referer)
            if (sameOrigin) builder.header("Accept", "*/*").header("Sec-Fetch-Site", "same-origin").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Dest", "empty")
            cookie?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            client.newCall(builder.build()).execute().use { r ->
                if (!r.isSuccessful) null else (r.body?.string() ?: return@use null) to r.headers("Set-Cookie")
            }
        } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
    }

    private fun browser(builder: Request.Builder, referer: String?): Request.Builder = builder
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "fr-FR,fr;q=0.8,en;q=0.5")
        .apply { referer?.let { header("Referer", it) } }

    private companion object {
        const val MAX_WORKS = 4
        const val PARALLEL = 4
        const val CATALOGUE_MS = 30 * 60_000L
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    }
}
