package com.sokoul.desktop.player

import android.content.Context
import com.sokoul.extract.BridgeRequest
import com.sokoul.extract.BridgeResponse
import com.sokoul.extract.HttpBridge
import com.sokoul.extract.PlaybackMetadataBridge
import com.sokoul.extract.PublicNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException

/** One session uses the same native browser profile for extraction and direct media requests. */
class CurlHttpBridge(private val context: Context, private val fallback: HttpBridge) : PlaybackMetadataBridge {
    private val cookies = PlaybackCookieJar()
    override suspend fun execute(request: BridgeRequest): BridgeResponse {
        val native = BrowserImpersonation.requiresImpersonation(request.url)
        if (!native) {
            val url = request.url.toHttpUrlOrNull() ?: throw IOException("invalid_url")
            val cookie = cookies.loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
            val response = fallback.execute(request.copy(headers = request.headers + if (cookie.isEmpty()) emptyMap() else mapOf("Cookie" to cookie)))
            response.urlCookies()
            return response
        }
        if (!CurlImpersonate.available) throw IOException("browser_transport_unavailable")
        if (request.method.uppercase() !in setOf("GET", "HEAD") || request.body != null) throw IOException("unsupported_browser_method")
        return runInterruptible(Dispatchers.IO) {
            var url = request.url.toHttpUrlOrNull()?.takeIf { PublicNetwork.validUrl(request.url) } ?: throw IOException("invalid_url")
            var input = request.headers
            repeat(6) { hop ->
                val cookie = cookies.loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
                val range = input.entries.firstOrNull { it.key.equals("Range", true) }?.value
                val lines = BrowserImpersonation.xhrHeaders(url.toString(), input, cookie, range)
                val transfer = CurlImpersonate.start(context, url.toString(), request.method.equals("HEAD", true), lines)
                try {
                    val head = transfer.awaitHead(12)
                    cookies.saveFromResponse(url, head.fields.filter { it.first.equals("Set-Cookie", true) }.mapNotNull { Cookie.parse(url, it.second) })
                    val location = head.header("Location")
                    if (head.statusCode in setOf(301,302,303,307,308) && location != null && request.redirect != "manual") {
                        if (request.redirect == "error" || hop == 5) throw IOException("redirect_limit")
                        val next = url.resolve(location)?.takeIf { PublicNetwork.validUrl(it.toString()) } ?: throw IOException("invalid_redirect")
                        if (next.host != url.host || next.port != url.port || next.scheme != url.scheme) input = input.filterKeys { it.lowercase() !in setOf("cookie", "origin", "referer") }
                        url = next
                    } else {
                        val out = ByteArrayOutputStream()
                        val limit = if (range == "bytes=0-0") 1 else 4 * 1024 * 1024
                        if (!request.method.equals("HEAD", true)) {
                            val buffer = ByteArray(16 * 1024)
                            while (out.size() <= limit) {
                                val length = transfer.body.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                                if (length < 0) break
                                out.write(buffer, 0, length)
                                if (limit == 1 || out.size() > limit) break
                            }
                        }
                        if (out.size() > limit && limit != 1) throw IOException("body_limit")
                        val headers = decodedCurlResponseHeaders(head.headerMap()).flatMap { (key, values) -> values.map { key to it } }
                        return@runInterruptible BridgeResponse(head.statusCode, head.statusMessage, headers, url.toString(), out.toByteArray().take(limit).toByteArray())
                    }
                } finally { transfer.cancel() }
            }
            throw IOException("redirect_limit")
        }
    }
    private fun BridgeResponse.urlCookies() {
        val url = finalUrl.toHttpUrlOrNull() ?: return
        cookies.saveFromResponse(url, headers.filter { it.first.equals("Set-Cookie", true) }.mapNotNull { Cookie.parse(url, it.second) })
    }
    override fun playbackHeaders(url: String): Map<String, String> {
        val target = url.toHttpUrlOrNull() ?: return emptyMap()
        val cookie = cookies.loadForRequest(target).joinToString("; ") { "${it.name}=${it.value}" }
        return (if (cookie.isEmpty()) emptyMap() else mapOf("Cookie" to cookie)) +
            if (BrowserImpersonation.requiresImpersonation(url)) mapOf("User-Agent" to BrowserImpersonation.USER_AGENT) else emptyMap()
    }
}
