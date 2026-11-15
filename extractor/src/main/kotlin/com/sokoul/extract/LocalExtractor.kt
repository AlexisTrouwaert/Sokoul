package com.sokoul.extract

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Extract only embeds already authorized by the configured server; callers enforce account access. */
class LocalExtractor(client: OkHttpClient, private val bridge: HttpBridge? = null) : AutoCloseable {
    private val cookies = ExtractionCookies()
    private val runtime = ExtractorRuntime(
        http = bridge ?: OkHttpBridge(client.newBuilder().cookieJar(cookies).build()),
        userscript = { canonicalScript() },
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
    )
    suspend fun extract(embedUrl: String): Extraction? = try {
        runtime.extract(embedUrl).takeIf { it.ok }?.let { result ->
            val host = result.url?.toHttpUrlOrNull()?.host ?: return null
            withContext(Dispatchers.IO) { PublicNetwork.dns.lookup(host) }
            val transportHeaders = result.url?.let { (bridge as? PlaybackMetadataBridge)?.playbackHeaders(it) }.orEmpty()
            val cookie = result.url?.let(cookies::header)
            val subtitles = result.subtitles.filter { sub ->
                runCatching { withContext(Dispatchers.IO) { PublicNetwork.dns.lookup(sub.url.toHttpUrlOrNull()?.host ?: error("invalid_subtitle")) } }.isSuccess
            }
            result.copy(subtitles = subtitles, headers = result.headers + (if (cookie == null) emptyMap() else mapOf("Cookie" to cookie)) + transportHeaders)
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    override fun close() = runtime.close()
    companion object {
        internal fun canonicalScript(): String = LocalExtractor::class.java.getResourceAsStream("/sokoul-extract/canonical.js")?.use { it.readBytes().decodeToString() } ?: error("missing_core")
        private val hostPatterns by lazy {
            val source = LocalExtractor::class.java.getResourceAsStream("/sokoul-extract/hosts.json")?.use { it.readBytes().decodeToString() } ?: error("missing_host_patterns")
            Json.parseToJsonElement(source).jsonObject.values.map { Regex(it.jsonPrimitive.content, RegexOption.IGNORE_CASE) }
        }
        fun supports(embedUrl: String): Boolean {
            if (!PublicNetwork.validUrl(embedUrl)) return false
            val url = embedUrl.toHttpUrlOrNull() ?: return false
            return hostPatterns.any { it.containsMatchIn(embedUrl) } || (url.encodedPath == "/" && url.query == null && url.fragment?.matches(Regex("[A-Za-z0-9_-]{1,128}")) == true)
        }
    }
}

/** Optional platform transport metadata for IP and browser-bound sessions. */
interface PlaybackMetadataBridge : HttpBridge { fun playbackHeaders(url: String): Map<String, String> }
