package com.sokoul.extract

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BridgeRequest(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?, val timeoutMs: Long, val redirect: String = "follow")
data class BridgeResponse(val status: Int, val statusText: String, val headers: List<Pair<String, String>>, val finalUrl: String, val body: ByteArray) {
    val contentType: String? get() = headers.firstOrNull { it.first.equals("content-type", true) }?.second
}
fun interface HttpBridge { suspend fun execute(request: BridgeRequest): BridgeResponse }

/** Refuse credentials, local networks and public names resolving to private destinations. */
object PublicNetwork {
    fun validUrl(raw: String): Boolean = raw.length <= 16_384 && raw.none { it.isWhitespace() || it.code == 0 } && raw.toHttpUrlOrNull()?.let {
        it.username.isEmpty() && it.password.isEmpty() && it.host != "localhost" && !it.host.endsWith(".localhost") &&
            (!(it.host.contains(':') || it.host.matches(Regex("[0-9.]+"))) || runCatching { isPublic(InetAddress.getByName(it.host)) }.getOrDefault(false))
    } == true
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) return b[0] !in setOf(0, 127) && !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 169 && b[1] == 254) && !(b[0] == 198 && b[1] in 18..19) && b[0] < 224
        return b.size == 16 && b[0] in 0x20..0x3f
    }
    val dns: Dns = object : Dns { override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw java.net.UnknownHostException("non_public_destination")
    } }
}

/** Small, session-local jar. Credentials never cross a cookie domain boundary. */
internal class ExtractionCookies : CookieJar {
    private val cookies = LinkedHashMap<String, Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) {
        values.forEach { c -> cookies["${c.domain}|${c.path}|${c.name}"] = c }
        while (cookies.size > 128) cookies.remove(cookies.keys.first())
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.entries.removeAll { it.value.expiresAt < System.currentTimeMillis() }
        return cookies.values.filter { it.matches(url) }
    }
    fun header(url: String): String? = url.toHttpUrlOrNull()?.let { loadForRequest(it).joinToString("; ") { c -> "${c.name}=${c.value}" }.ifEmpty { null } }
}

/** Redirects and reads remain bounded; range probes close media bodies immediately. */
class OkHttpBridge(client: OkHttpClient) : HttpBridge {
    private val client = client.newBuilder().dns(PublicNetwork.dns).proxy(java.net.Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).build()
    override suspend fun execute(request: BridgeRequest): BridgeResponse {
        var url = request.url.toHttpUrlOrNull()?.takeIf { PublicNetwork.validUrl(request.url) } ?: throw IOException("invalid_url")
        var method = request.method.uppercase()
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) throw IOException("invalid_method")
        var body = request.body
        var headers = request.headers.filterKeys { it.lowercase() !in setOf("host", "connection", "content-length", "authorization", "proxy-authorization") }
        repeat(6) { hop ->
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> if (value.length <= 4096 && value.none { it.code in setOf(0,10,13) }) builder.header(name, value) }
            builder.method(method, if (body != null || method in setOf("POST", "PUT", "PATCH", "DELETE")) (body ?: ByteArray(0)).toRequestBody() else null)
            val call = client.newBuilder().callTimeout(request.timeoutMs.coerceIn(1, 25_000), TimeUnit.MILLISECONDS).build().newCall(builder.build())
            call.await().use { response ->
                val location = response.header("Location")
                if (response.code in setOf(301,302,303,307,308) && location != null && request.redirect != "manual") {
                    if (request.redirect == "error" || hop == 5) throw IOException("redirect_limit")
                    val next = url.resolve(location)?.takeIf { PublicNetwork.validUrl(it.toString()) } ?: throw IOException("invalid_redirect")
                    if (next.host != url.host || next.scheme != url.scheme || next.port != url.port) headers = headers.filterKeys { it.lowercase() !in setOf("cookie", "origin", "referer") }
                    if (response.code == 303 || (response.code in setOf(301,302) && method == "POST")) { method = "GET"; body = null }
                    url = next
                } else {
                    val probe = headers.any { it.key.equals("Range", true) && it.value == "bytes=0-0" }
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val limit = if (probe) 1L else 4L * 1024 * 1024
                    val responseBody = response.body
                    if (!probe && (responseBody?.contentLength() ?: 0) > limit) throw IOException("body_limit")
                    val bytes = if (method == "HEAD" || responseBody == null) ByteArray(0) else {
                        if (!probe && (contentType.startsWith("video/") || contentType.startsWith("audio/"))) throw IOException("unexpected_media_body")
                        val source = responseBody.source()
                        if (probe) { source.request(1); source.readByteArray(minOf(1L, source.buffer.size)) }
                        else { source.request(limit + 1); if (source.buffer.size > limit) throw IOException("body_limit"); source.readByteArray() }
                    }
                    return BridgeResponse(response.code, response.message, (0 until response.headers.size).map { response.headers.name(it) to response.headers.value(it) }, url.toString(), bytes)
                }
            }
        }
        throw IOException("redirect_limit")
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("network_error")) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, _, _ -> response.close() } }
    })
    continuation.invokeOnCancellation { cancel() }
}
