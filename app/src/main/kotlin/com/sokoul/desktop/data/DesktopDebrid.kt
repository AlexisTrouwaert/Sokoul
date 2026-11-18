package com.sokoul.desktop.data

import com.sokoul.iptv.data.anime.SeaDexRelease
import com.sokoul.iptv.data.debrid.AnimeCheck
import com.sokoul.iptv.data.debrid.AnimeReleaseRules
import com.sokoul.iptv.data.debrid.DebridAddons
import com.sokoul.iptv.data.debrid.ReleaseName
import com.sokoul.iptv.data.debrid.DebridQuery
import com.sokoul.iptv.data.debrid.DebridStream
import com.sokoul.iptv.data.debrid.DebridStreams
import com.sokoul.iptv.data.debrid.RdUser
import com.sokoul.iptv.data.debrid.RipSource
import com.sokoul.iptv.data.debrid.SourceAddon
import com.sokoul.iptv.data.net.TmdbInfo
import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.iptv.domain.HdrFormat
import com.sokoul.iptv.domain.VideoQuality
import com.sokoul.iptv.parser.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import com.sokoul.iptv.data.net.await
import java.util.concurrent.ConcurrentHashMap

/** An anime's search: what versions are judged against, and the community's releases to add. */
data class AnimeSearch(val check: AnimeCheck, val seaDex: List<SeaDexRelease> = emptyList())

/** What the identity adds to a search, once known: the anime numbers of the episode, and the anime's rules. */
data class AnimeLookup(val query: DebridQuery, val search: AnimeSearch?)

/** Résultat d'une recherche de versions : les versions classées, ou la raison de l'absence. */
data class DebridResult(val streams: List<DebridStream>, val problem: String? = null, val done: Boolean = false)

/**
 * Real-Debrid sur PC : versions trouvées par Torrentio et Comet (format Stremio, jeton ajouté au moment de la requête,
 * jamais journalisé). The ranking belongs to `VersionRanking`; samples are left out when the file of a torrent is picked
 * (`ReleaseName.pickFile`). Results are kept 30 min in memory and 6 h in the database.
 */
class DesktopDebrid(
    private val db: DesktopDb,
    private val catalog: Catalog,
    /** The client of the search services; a test gives one that answers for them. */
    private val http: okhttp3.OkHttpClient = catalog.http,
) {
    /** Every advertised language is searched: the audio preference only ranks, so it is not part of the key. */
    private val cache = ConcurrentHashMap<DebridQuery, Pair<Long, List<DebridStream>>>()

    /** Forgets the searches kept in memory; "Clear the catalogue cache" empties the database copy at the same time. */
    fun clearCache() = cache.clear()

    fun token(): String? = ProviderConfig.key(db, "realdebrid")

    fun configured() = token() != null

    /** "off" leaves Torrentio to its configured address only, as before. */
    private fun fastTorrentio() = db.setting(FAST_TORRENTIO) != "off"

    /** The built-in search services in use ("TORRENTIO,COMET"); both when nothing is set, as before the setting. */
    fun services(): Set<SourceAddon> = db.setting(SERVICES)?.let(DebridAddons::parse) ?: SourceAddon.entries.toSet()

    /** Services added by hand (Stremio addresses, one per line); they receive the same paths as the built-in ones. */
    fun customServices(): String = db.setting(CUSTOM_SERVICES).orEmpty()

    /** Until when a service that answered "too many requests" (HTTP 429) is left alone, by service. */
    private val coolDown = ConcurrentHashMap<String, Long>()

    suspend fun user(): RdUser? = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext null
        runCatching {
            catalog.http.newCall(Request.Builder().url("https://api.real-debrid.com/rest/1.0/user").header("Authorization", "Bearer $t").build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val o = Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject ?: return@use null
                    RdUser(o.str("username").orEmpty(), o.str("type") == "premium", o.str("expiration")?.let(::iso) ?: 0)
                }
        }.getOrNull()
    }

    /**
     * Les versions au fil de l'eau : une réponse à chaque extension qui répond, sans attendre la plus lente. [refresh]
     * ignore ce qui a été retenu. Le classement revient au lecteur, qui connaît les préférences.
     */
    /** One step of the search, on the clock of the start: "+20300 ms torrentio imdb 286". */
    fun note(step: String) = com.sokoul.pc.playback.session.StartClock.note(step)

    /**
     * [lookup] tells, while the services are already being asked, what the anime identity adds: the Kitsu search, the
     * community's releases and the rules. Nothing is sent before it is known, so that no version is started unjudged.
     */
    fun search(q: DebridQuery, info: TmdbInfo?, refresh: Boolean, lookup: (suspend () -> AnimeLookup?)? = null): Flow<DebridResult> = channelFlow {
        note("search")
        runCatching { db.purgeCache(CACHE_PREFIX, System.currentTimeMillis() - SAVED_MS) }
        val planned = async { runCatching { lookup?.invoke() }.getOrElse { if (it is CancellationException) throw it else null } }
        val token = withContext(Dispatchers.IO) { token() }
        if (token == null) {
            send(DebridResult(emptyList(), "Real-Debrid n'est pas configuré", done = true))
            return@channelFlow
        }
        val known = cache[q]?.takeIf { !refresh && System.currentTimeMillis() - it.first < TTL_MS }
        if (known != null) {
            send(DebridResult(known.second, done = true))
            return@channelFlow
        }
        val imdb = info?.imdbId ?: catalog.imdbId(if (q.series) "tv" else "movie", q.tmdbId)
        if (imdb == null) {
            send(DebridResult(emptyList(), "Identifiant IMDb introuvable pour ce titre", done = true))
            return@channelFlow
        }
        val titles = listOfNotNull(info?.title, info?.originalTitle)
        val lock = Mutex()
        val results = ArrayList<List<DebridStream>>()
        var plan: AnimeLookup? = null
        var anime: AnimeSearch? = null
        var community: Set<String> = emptySet()
        var community0: List<DebridStream> = emptyList()
        val base0 = q
        fun kept(): List<DebridStream> {
            val q = plan?.query ?: base0
            val anime = anime
            // Anime are judged by AnimeReleaseRules, which keeps a contradicted version visible: no silent pre-filter for them.
            val combined = DebridStreams.combine(results)
            val distinct = if (anime != null) combined
                else combined.filterNot { DebridStreams.otherWork(it.filename ?: it.title.lineSequence().firstOrNull().orEmpty(), titles, q.series) }
            val judged = if (anime != null) {
                // Anime: every version is judged against the identity, and a contradicted one stays visible, with its
                // reason, but is never played on its own.
                distinct.map { s ->
                    s.copy(
                        verdict = AnimeReleaseRules.verdict(s.title, if (q.series) DebridStreams.fileOf(s) else s.filename, anime.check),
                        community = s.community || (s.infoHash != null && s.infoHash in community),
                    )
                }
            } else {
                // Same title is not the same work (One Piece 2023 for the anime of 1999), and a pack's file must be the
                // episode asked for (the TV played episode 319 for episode 1): both are dropped before anyone can play them.
                // An audiobook or a soundtrack is never played; nor a box set made before a new film came out.
                distinct.filterNot { DebridStreams.notVideo(it.title) || (it.filename?.let(DebridStreams::notVideo) == true) }
                    .filterNot { !q.series && DebridStreams.packBeforeFilm(it.title, titles, info?.year) }
                    .filterNot { if (q.series) DebridStreams.otherYear(it.title, titles, info?.year, q.season)
                    else DebridStreams.wrongMovie(it.title, titles, info?.year) ||
                        (it.filename?.let { file -> DebridStreams.wrongMovie(file, titles, info?.year) } == true) }
                    .filterNot { q.series && DebridStreams.wrongEpisode(DebridStreams.fileOf(it), q.season, q.episode, q.absolute) }
                    .map { if (q.series) it else it.copy(movieTitles = titles, movieYear = info?.year) }
            }
            val tagged = DebridStreams.untagged(judged, info?.originalLanguage)
            // Short animation episodes can be compact. Size alone never proves a sample or a wrong work.
            return tagged
        }
        // Preference belongs to ranking; discovery must retain every advertised language.
        // The services searched are a setting; a disabled one gets no call at all, its quick and Kitsu requests included.
        val services = services()
        val torrentio = SourceAddon.TORRENTIO in services
        val bases = DebridAddons.urls(services, customServices(), token, false)
        fun ask(url: String, label: String) = launch {
            val found = fetch(url, refresh)
            note(label + " " + (found?.size ?: "failed"))
            if (found == null) return@launch
            val snapshot = lock.withLock {
                // The community's releases go after the services' own lines of the same torrent, which carry a link.
                results.add(results.size - (if (community0.isNotEmpty()) 1 else 0), found)
                if (!planned.isCompleted) null else withContext(Dispatchers.Default) { kept() }
            }
            if (snapshot != null) send(DebridResult(snapshot))
        }
        // Torrentio without the token first: the same versions in under a second (the configured one takes 20 s).
        val fast = torrentio && fastTorrentio()
        val plain = DebridAddons.torrentioPlain(false)
        val jobs = (listOfNotNull(if (fast) ask("$plain/" + q.streamPath(imdb), "torrentio fast imdb") else null) +
            listOfNotNull(if (torrentio) ask("${DebridAddons.torrentioPlain(true)}/" + q.streamPath(imdb), "torrentio french imdb") else null) +
            bases.map { base -> ask("$base/" + q.streamPath(imdb), host(base) + " imdb") }).toMutableList()
        val identity = launch {
            val found = planned.await()
            note("identity " + (if (found?.search != null) "anime" else "none"))
            val snapshot = lock.withLock {
                plan = found
                anime = found?.search
                community = found?.search?.seaDex?.map { it.infoHash }?.toSet().orEmpty()
                community0 = found?.search?.let { a -> communityStreams(a, found.query) }.orEmpty()
                if (community0.isNotEmpty()) results += community0
                withContext(Dispatchers.Default) { kept() }
            }
            send(DebridResult(snapshot))
            // Comet's manifest takes Kitsu ids, but on 2026-11-08 it answered "kitsu:12:1" with other series (Blue Box,
            // Arcane, The Walking Dead): only Torrentio is asked by Kitsu id.
            val kitsu = found?.query?.kitsuPath()?.takeIf { found.search != null }
            if (kitsu != null && torrentio) {
                val kitsuBases = (if (fast) listOf(plain) else bases.filter { it.startsWith(DebridAddons.TORRENTIO) }) + DebridAddons.torrentioPlain(true)
                kitsuBases.forEach { base -> lock.withLock { jobs += ask("$base/$kitsu", (if (fast) "torrentio fast" else host(base)) + " kitsu") } }
            }
        }
        identity.join()
        lock.withLock { jobs.toList() }.joinAll()
        val all = lock.withLock { withContext(Dispatchers.Default) { kept() } }
        if (all.isNotEmpty()) cache[q] = System.currentTimeMillis() to all
        send(DebridResult(all, if (all.isEmpty()) "Aucune version trouvée sur Real-Debrid" else null, done = true))
    }

    /**
     * The SeaDex releases that hold the episode asked for (or the film), as versions with only their torrent's hash:
     * Real-Debrid fetches them like any other. A release whose files do not hold the episode is no version of it.
     */
    private fun communityStreams(anime: AnimeSearch, q: DebridQuery): List<DebridStream> = anime.seaDex.mapNotNull { r ->
        val file = if (q.series) {
            r.files.filter { (name, _) -> ReleaseName.isVideo(name) && DebridStreams.episodeMatches(name, q.season, q.episode, q.absolute) == true }.maxByOrNull { it.second }
        } else {
            r.files.filter { ReleaseName.isVideo(it.first) }.maxByOrNull { it.second }
        } ?: return@mapNotNull null
        val name = file.first.substringAfterLast('/')
        DebridStream(
            key = "${r.infoHash}:-1", title = name, url = null, infoHash = r.infoHash, fileIdx = null, filename = name,
            release = ReleaseName.parse(listOfNotNull(r.group, if (r.dualAudio) "Dual Audio" else null).joinToString(" "), name, name).let { it.copy(sizeBytes = it.sizeBytes ?: file.second) },
            community = true,
        )
    }

    /**
     * Lien direct d'une version : l'extension répond par une redirection vers le fichier Real-Debrid (le torrent est
     * ajouté au compte s'il n'y est pas). Suivi ici, pour que le lecteur ne voie jamais l'adresse portant le jeton.
     * Torrentio renvoie une courte vidéo « téléchargement en cours » quand le fichier n'est pas prêt : signalée.
     */
    suspend fun resolve(s: DebridStream): Result<String> = withContext(Dispatchers.IO) {
        val url = s.url ?: return@withContext Result.failure(IllegalStateException("Version sans lien de lecture"))
        val noRedirect = catalog.http.newBuilder().followRedirects(false).followSslRedirects(false)
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
        var current = url
        repeat(6) {
            if (onRealDebrid(current)) return@withContext Result.success(current)
            // appel annulable : fermer le lecteur pendant la résolution coupe aussi la requête
            val r = runCatching { noRedirect.newCall(Request.Builder().url(current).header("User-Agent", "SokoulTV/2.0").build()).await() }
                .getOrElse { if (it is CancellationException) throw it; return@withContext Result.failure(IllegalStateException("Real-Debrid ne répond pas")) }
            r.use {
                when {
                    it.isRedirect -> {
                        val next = it.header("Location") ?: return@withContext Result.failure(IllegalStateException("Lien indisponible"))
                        current = it.request.url.resolve(next)?.toString() ?: next
                    }
                    // l'extension répond elle-même : sa vidéo d'attente (« Not ready yet », « downloading »…), jamais le film
                    it.isSuccessful -> return@withContext Result.failure(NotReady())
                    else -> return@withContext Result.failure(IllegalStateException("Version indisponible (HTTP ${it.code})"))
                }
            }
        }
        if (onRealDebrid(current)) Result.success(current) else Result.failure(NotReady())
    }

    /** Essai (journal) : chaque étape de la résolution, sans l'adresse (elle porte le jeton) : hôte, code, type. */
    suspend fun trace(s: DebridStream): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val noRedirect = catalog.http.newBuilder().followRedirects(false).followSslRedirects(false).readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
        var current = s.url ?: return@withContext listOf("sans lien")
        repeat(6) {
            val r = runCatching { noRedirect.newCall(Request.Builder().url(current).header("User-Agent", "SokoulTV/2.0").build()).await() }.getOrElse { out += "erreur ${it.javaClass.simpleName}"; return@withContext out }
            r.use {
                out += "${it.request.url.host} → HTTP ${it.code} ${it.header("Content-Type").orEmpty()} ${it.header("Content-Length").orEmpty()}"
                if (!it.isRedirect) return@withContext out
                current = it.request.url.resolve(it.header("Location") ?: return@withContext out)?.toString() ?: return@withContext out
            }
        }
        out
    }

    /** Fichier servi par Real-Debrid (`*.download.real-debrid.com`, `real-debrid.com/d/`), et non une vidéo d'attente. */
    private fun onRealDebrid(u: String): Boolean {
        val host = u.toHttpUrlOrNull()?.host ?: return false
        return (host.endsWith(".real-debrid.com") || host == "real-debrid.com") && NOT_READY.none { u.contains(it) }
    }

    class NotReady : IllegalStateException("Cette version n'est pas encore prête sur Real-Debrid (en cours de préparation) : choisissez-en une autre ou réessayez dans quelques minutes")

    /**
     * Its own connections for the token-less Torrentio: on the shared one, its answer came only after the configured
     * call's 22 s (measured on the TV, 2026-11-08).
     */
    private val fastHttp by lazy { http.newBuilder().connectionPool(okhttp3.ConnectionPool()).build() }

    private suspend fun fetch(address: String, refresh: Boolean = false): List<com.sokoul.iptv.data.debrid.DebridStream>? = withContext(Dispatchers.IO) {
        val key = cacheKey(address)
        // An answer of the last hours is used again: a second start of the same episode lists its versions at once.
        if (!refresh) runCatching { db.tmdbGet(key) }.getOrNull()?.takeIf { System.currentTimeMillis() - it.second < SAVED_MS }?.let { return@withContext DebridStreams.parse(it.first) }
        val client = if (address.startsWith(DebridAddons.torrentioPlain(true) + "/") || address.startsWith(DebridAddons.torrentioPlain(false) + "/stream")) fastHttp else http
        // A service that asked to be left alone is not asked again before the time it gave; the others go on.
        val service = cacheKey(address).removePrefix(CACHE_PREFIX).substringBefore(':')
        val now = System.currentTimeMillis()
        if ((coolDown[service] ?: 0) > now) {
            note("$service waiting ${((coolDown[service] ?: now) - now) / 1000} s after 429")
            return@withContext null
        }
        try {
            withTimeoutOrNull(timeoutMs(address)) {
                // await: the timeout really cancels the HTTP call (a blocking execute() would ignore it)
                client.newCall(Request.Builder().url(address).header("User-Agent", "SokoulTV/2.0").build()).await().use { r ->
                    if (r.code == 429 || r.code == 503) {
                        coolDown[service] = System.currentTimeMillis() + retryAfterMs(r.header("Retry-After"))
                        note("$service answered ${r.code}")
                    }
                    if (!r.isSuccessful) return@use null
                    val text = r.body?.string().orEmpty()
                    DebridStreams.parse(text).also { found -> if (found.isNotEmpty()) runCatching { db.tmdbPut(key, text, System.currentTimeMillis()) } }
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    /**
     * How long a service is waited for, set per device in seconds by "pc.debrid.timeout.<service>" (torrentio-fast,
     * torrentio, comet). Waiting long delays nothing: playback starts on the first versions that arrive.
     */
    fun timeoutMs(address: String): Long {
        val service = cacheKey(address).removePrefix(CACHE_PREFIX).substringBefore(':')
        return db.setting(TIMEOUT_SETTING + service)?.toLongOrNull()?.takeIf { it in 1..600 }?.times(1000)
            ?: if (service == "torrentio-fast") FAST_TIMEOUT_MS else TIMEOUT_MS
    }

    companion object {
        const val TIMEOUT_SETTING = "pc.debrid.timeout."
        /** The token-less Torrentio answers in under a second; past 15 s it is not coming. */
        const val FAST_TIMEOUT_MS = 15_000L
        const val TTL_MS = 30 * 60_000L
        /** How long a service's answer is kept on the device. */
        const val SAVED_MS = 6 * 3_600_000L
        const val CACHE_PREFIX = "debrid:"

        /**
         * The cache key of an address: the service, the language and the path of the work, never the address itself, which
         * carries the Real-Debrid token (Torrentio) or the whole configuration with it (Comet).
         */
        fun cacheKey(address: String): String {
            val service = when {
                address.startsWith(DebridAddons.torrentioPlain(true) + "/") || address.startsWith(DebridAddons.TORRENTIO + "/stream") -> "torrentio-fast"
                address.startsWith(DebridAddons.TORRENTIO) -> "torrentio"
                address.startsWith(DebridAddons.COMET) -> "comet"
                else -> "addon" + address.substringBefore("/stream").hashCode().toUInt().toString(16)
            }
            val language = if (address.contains("language=french")) "fr" else "all"
            return CACHE_PREFIX + service + ":" + language + ":" + address.substringAfter("/stream/")
        }
        const val FAST_TORRENTIO = "pc.start.fastTorrentio"
        /** Built-in search services in use, "TORRENTIO,COMET". */
        const val SERVICES = "pc.debrid.services"
        /** Stremio addresses added by hand, one per line. */
        const val CUSTOM_SERVICES = "pc.debrid.custom"
        private const val RETRY_DEFAULT_MS = 60_000L
        private const val RETRY_MAX_MS = 15 * 60_000L

        /** Retry-After as seconds or as an HTTP date; one minute when absent or unreadable, a quarter of an hour at most. */
        fun retryAfterMs(header: String?, now: Long = System.currentTimeMillis()): Long {
            val value = header?.trim().orEmpty()
            val ms = value.toLongOrNull()?.times(1000)
                ?: runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }.getOrNull()
                ?: RETRY_DEFAULT_MS
            return ms.coerceIn(0, RETRY_MAX_MS)
        }
        private fun host(base: String) = if (base.startsWith(DebridAddons.TORRENTIO)) "torrentio" else if (base.startsWith(DebridAddons.COMET)) "comet" else "addon"
        // Measured from the TV on 2026-11-08 for One Piece S1E1: Torrentio 20 s, Comet 32 s (Real-Debrid checks every
        // torrent of a long anime). At 15 s both were cut and nothing was ever found.
        const val TIMEOUT_MS = 45_000L
        /** Vidéos d'attente de Torrentio (fichier pas encore prêt, erreur Real-Debrid). */
        private val NOT_READY = listOf("/videos/downloading", "/videos/failed", "/videos/access_denied", "/videos/limits_exceeded", "/videos/infringing")

        fun iso(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0)
    }
}
