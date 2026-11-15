package com.sokoul.pc.playback.sources
import com.sokoul.pc.playback.ui.label
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class HostDebridProvider(val id: String, val label: String) {
    DEEPBRID("deepbrid", "Deepbrid"), REALDEBRID("realdebrid", "Real-Debrid"),
    BESTDEBRID("bestdebrid", "BestDebrid"), DEBRIDR("debridr", "DebridR"),
}
enum class DebridToolFailure { Configuration, InvalidLink, VipRequired, Unavailable, ProviderRefused }
class DebridToolException(val reason: DebridToolFailure) : Exception(reason.name)
data class DebridToolResult(val link: String, val filename: String, val filesize: Long, val host: String, val provider: HostDebridProvider) {
    val playable: Boolean get() = listOf(filename, link.substringBefore('?')).any {
        it.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "m3u8", "avi", "mov", "ts")
    }
}

/** The reference host-link utility. It does not create torrent-library accounts or infer title identities. */
class DebridToolsService(http: OkHttpClient, private val baseUrl: () -> String?, private val accessKey: () -> String?) {
    // URLs can contain short-lived signatures or the BestDebrid key. No request logger receives these calls.
    private val client = http.newBuilder().apply { interceptors().clear(); networkInterceptors().clear() }
        .followRedirects(false).followSslRedirects(false).callTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private var enabled: Set<HostDebridProvider> = emptySet()

    suspend fun providers(): List<HostDebridProvider> {
        val payload = call(server("/api/media/debrid/providers")) as? JsonObject ?: fail(DebridToolFailure.Unavailable)
        if (payload.text("status") != "success") fail(DebridToolFailure.Unavailable)
        val ids = (payload["providers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return HostDebridProvider.entries.filter { it.id in ids }.also { enabled = it.toSet() }
    }

    suspend fun unlock(link: String, provider: HostDebridProvider, password: String = ""): DebridToolResult {
        val input = link.trim()
        if (!publicLink(input)) fail(DebridToolFailure.InvalidLink)
        if (provider !in enabled) fail(DebridToolFailure.ProviderRefused)
        if (password.length > 1024) fail(DebridToolFailure.InvalidLink)
        if (provider == HostDebridProvider.BESTDEBRID) {
            val keyPayload = call(server("/api/debrid/bestdebrid-key")) as? JsonObject ?: fail(DebridToolFailure.Unavailable)
            val key = keyPayload.text("apiKey")?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: fail(DebridToolFailure.Unavailable)
            val url = "https://bestdebrid.com/api/v1/generateLink".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("auth", key).addQueryParameter("link", input).build()
            val candidate = bestCandidate(call(Request.Builder().url(url).header("Cache-Control", "no-store").build()))
                ?: fail(DebridToolFailure.ProviderRefused)
            val original = candidate.text("original_link")
            val direct = candidate.text("link")?.takeUnless { it == input || it == original }
                ?: candidate.text("download") ?: candidate.text("streammp4") ?: fail(DebridToolFailure.ProviderRefused)
            return result(candidate, direct, provider)
        }
        val body = buildJsonObject { put("link", input); put("provider", provider.id); put("password", password) }
        val request = server("/api/media/debrid/unlock").newBuilder()
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val payload = call(request) as? JsonObject ?: fail(DebridToolFailure.Unavailable)
        if (payload.text("status") != "success") fail(DebridToolFailure.ProviderRefused)
        val data = payload["data"] as? JsonObject ?: fail(DebridToolFailure.ProviderRefused)
        return result(data, data.text("link") ?: fail(DebridToolFailure.ProviderRefused), provider)
    }

    private fun result(data: JsonObject, direct: String, provider: HostDebridProvider): DebridToolResult {
        if (!publicLink(direct)) fail(DebridToolFailure.ProviderRefused)
        val filename = (data.text("filename") ?: direct.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty())
            .filterNot(Char::isISOControl).take(512)
        return DebridToolResult(direct, filename, (data["filesize"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0,
            (data.text("host") ?: data.text("hoster") ?: direct.toHttpUrlOrNull()?.host.orEmpty()).take(128), provider)
    }

    private fun server(path: String): Request {
        val base = baseUrl()?.trim()?.trimEnd('/')?.toHttpUrlOrNull() ?: fail(DebridToolFailure.Configuration)
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null) fail(DebridToolFailure.Configuration)
        val key = accessKey()?.takeIf { it.isNotBlank() && it.none { c -> c == '\r' || c == '\n' } } ?: fail(DebridToolFailure.Configuration)
        return Request.Builder().url(base.toString().trimEnd('/') + path).header("x-access-key", key).header("Cache-Control", "no-store").build()
    }

    private suspend fun call(request: Request): JsonElement {
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(DebridToolException(DebridToolFailure.Unavailable)) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, _, _ -> response.close() } }
            })
        }
        return withContext(Dispatchers.IO) { response.use {
            if (it.code == 401 || it.code == 403) fail(DebridToolFailure.VipRequired)
            if (!it.isSuccessful) fail(if (it.code in 400..499) DebridToolFailure.ProviderRefused else DebridToolFailure.Unavailable)
            val bytes = it.body?.byteStream()?.use { body ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2 * 1024 * 1024) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: fail(DebridToolFailure.Unavailable)
            if (bytes.size > 2 * 1024 * 1024) fail(DebridToolFailure.Unavailable)
            try { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { fail(DebridToolFailure.Unavailable) }
        } }
    }

    companion object {
        internal fun bestCandidate(payload: JsonElement): JsonObject? = when (payload) {
            is JsonArray -> payload.filterIsInstance<JsonObject>().firstOrNull()
            is JsonObject -> when (val data = payload["data"]) {
                is JsonArray -> data.filterIsInstance<JsonObject>().firstOrNull()
                is JsonObject -> data
                else -> payload
            }
            else -> null
        }
        private fun fail(reason: DebridToolFailure): Nothing = throw DebridToolException(reason)
        internal fun publicLink(value: String): Boolean {
            if (value.length > 8192 || value.any { it.isWhitespace() || it.isISOControl() }) return false
            val url = value.toHttpUrlOrNull() ?: return false
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
            // Reject explicit local targets without performing speculative network requests from the form.
            val host = url.host.lowercase()
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || !host.contains('.') && !host.contains(':')) return false
            if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
                val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
                if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return false
                val bytes = address.address.map { it.toInt() and 255 }
                if (bytes.size == 16) return bytes[0] in 0x20..0x3f
                if (bytes[0] >= 224 || bytes[0] == 0 || bytes[0] == 100 && bytes[1] in 64..127 || bytes[0] == 198 && bytes[1] in 18..19) return false
            }
            return true
        }
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
