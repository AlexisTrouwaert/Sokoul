package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.data.net.await
import com.sokoul.iptv.parser.long
import com.sokoul.iptv.parser.str
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/**
 * The Real-Debrid account API without Android: torrents, their progress, direct links. The token is read when needed and
 * never logged. At most one call every 260 ms (Real-Debrid allows 250 a minute), and a 429 waits for `Retry-After`.
 */
class RealDebridApi(
    private val http: OkHttpClient,
    private val token: () -> String?,
    private val base: String = BASE,
    private val spacingMs: Long = MIN_SPACING_MS,
) {
    private val pace = Mutex()
    private var lastCall = 0L

    fun configured() = !token().isNullOrBlank()

    /** Adds a magnet. Nothing downloads until its files are chosen. */
    suspend fun addMagnet(magnet: String): String =
        (call("POST", "torrents/addMagnet", FormBody.Builder().add("magnet", magnet).build()) as? JsonObject)?.str("id")
            ?: throw RdException(0, "Unexpected Real-Debrid answer")

    suspend fun torrentInfo(id: String): RdTorrent =
        (call("GET", "torrents/info/$id") as? JsonObject)?.let(::parseTorrent) ?: throw RdException(0, "Unexpected Real-Debrid answer")

    suspend fun selectFiles(id: String, fileIds: List<Int>) {
        call("POST", "torrents/selectFiles/$id", FormBody.Builder().add("files", fileIds.joinToString(",").ifEmpty { "all" }).build())
    }

    /** Runs to its end even when the caller is cancelled: a test torrent must not stay in the account. */
    suspend fun deleteTorrent(id: String) {
        withContext(NonCancellable) { runCatching { call("DELETE", "torrents/delete/$id") } }
    }

    suspend fun unrestrict(link: String): RdLink {
        val o = call("POST", "unrestrict/link", FormBody.Builder().add("link", link).build()) as? JsonObject
            ?: throw RdException(0, "Unexpected Real-Debrid answer")
        return RdLink(o.str("download") ?: throw RdException(0, "No link"), o.str("filename").orEmpty(), o.long("filesize") ?: 0, o.str("mimeType"))
    }

    suspend fun torrents(limit: Int = 100, page: Int = 1): List<RdTorrent> =
        (call("GET", "torrents?limit=$limit" + (if (page > 1) "&page=$page" else "")) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseTorrent) }

    private suspend fun call(method: String, path: String, body: RequestBody? = null): JsonElement? {
        val t = token()?.takeIf { it.isNotBlank() } ?: throw RdException(401, "Real-Debrid is not configured")
        repeat(ATTEMPTS) { attempt ->
            pace.withLock {
                val wait = lastCall + spacingMs - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastCall = System.currentTimeMillis()
            }
            val request = Request.Builder().url("$base/$path").header("Authorization", "Bearer $t")
                .method(method, body ?: if (method == "POST") FormBody.Builder().build() else null).build()
            http.newCall(request).await().use { r ->
                val text = r.body?.string().orEmpty()
                when {
                    r.code == 429 && attempt < ATTEMPTS - 1 -> {
                        delay((r.header("Retry-After")?.toLongOrNull() ?: 2).coerceIn(1, MAX_RETRY_AFTER_S) * 1000)
                        return@repeat
                    }
                    r.isSuccessful -> return if (text.isBlank()) null else Json.parseToJsonElement(text)
                    else -> {
                        val error = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                        throw RdException(r.code, error?.str("error") ?: "HTTP ${r.code}", (error?.long("error_code") ?: 0).toInt())
                    }
                }
            }
        }
        throw RdException(429, "Too many requests")
    }

    companion object {
        const val BASE = "https://api.real-debrid.com/rest/1.0"
        const val MIN_SPACING_MS = 260L
        private const val ATTEMPTS = 3
        /** A start does not wait minutes on Real-Debrid: past this, the limit is reported and other sources are tried. */
        private const val MAX_RETRY_AFTER_S = 5L

        fun parseTorrent(o: JsonObject) = RdTorrent(
            id = o.str("id").orEmpty(), filename = o.str("filename").orEmpty(), hash = o.str("hash").orEmpty().lowercase(), status = o.str("status").orEmpty(),
            progress = (o.long("progress") ?: 0).toInt(), bytes = o.long("bytes") ?: 0,
            files = (o["files"] as? JsonArray).orEmpty().mapNotNull { f ->
                val fo = f as? JsonObject ?: return@mapNotNull null
                RdFile((fo.long("id") ?: return@mapNotNull null).toInt(), fo.str("path").orEmpty(), fo.long("bytes") ?: 0, fo.long("selected") == 1L)
            },
            links = (o["links"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            // "2026-10-28T12:34:56.000Z": read, the daily cleanup can tell an old torrent from a new one.
            addedAt = o.str("added")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
            speedBps = o.long("speed") ?: 0,
            seeders = (o.long("seeders") ?: 0).toInt(),
        )
    }
}
