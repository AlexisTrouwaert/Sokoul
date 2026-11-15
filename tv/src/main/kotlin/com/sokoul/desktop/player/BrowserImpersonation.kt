package com.sokoul.desktop.player

import java.net.URI
import java.util.Locale
object BrowserImpersonation {
    const val TARGET = "chrome152"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    const val SEC_CH_UA =
        "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Google Chrome\";v=\"152\""
    const val SEC_CH_UA_PLATFORM = "\"Windows\""
    const val ACCEPT_ENCODING = "gzip, deflate, br, zstd"
    private val playerOrigins = listOf(
        "fsvid.lol" to "https://fsvid.lol",
        "vidzy.cc" to "https://vidzy.cc",
        "vidzy.org" to "https://vidzy.org",
    )

    private fun playerOriginFor(host: String): String? = playerOrigins.firstOrNull { (suffix, _) ->
        host == suffix || host.endsWith(".$suffix")
    }?.second
    fun requiresImpersonation(url: String): Boolean =
        ImpersonationHeaders.hostnameOf(url)?.let(::playerOriginFor) != null
    fun xhrHeaders(url: String, input: Map<String, String>, cookie: String?, range: String?): List<String> {
        val host = requireNotNull(ImpersonationHeaders.hostnameOf(url)) { "Missing host" }
        fun get(name: String) = input.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val origin = playerOriginFor(host) ?: get("Origin")?.let(::originOf)

        val lines = ArrayList<String>(16)
        lines += "sec-ch-ua-platform: $SEC_CH_UA_PLATFORM"
        lines += "user-agent: $USER_AGENT"
        lines += "sec-ch-ua: $SEC_CH_UA"
        lines += "sec-ch-ua-mobile: ?0"
        lines += "accept: */*"
        if (origin != null && origin != "https://$host") lines += "origin: $origin"
        lines += "sec-fetch-site: ${fetchSite(origin, host)}"
        lines += "sec-fetch-mode: cors"
        lines += "sec-fetch-dest: empty"
        if (origin != null) lines += "referer: $origin/"
        lines += "accept-encoding: $ACCEPT_ENCODING"
        lines += "accept-language: ${get("Accept-Language") ?: ImpersonationHeaders.ACCEPT_LANGUAGE}"
        if (!cookie.isNullOrEmpty()) lines += "cookie: $cookie"
        if (range != null) lines += "range: $range"
        lines += "priority: u=1, i"
        return lines
    }

    private fun originOf(raw: String): String? = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase(Locale.US)
        if (uri.scheme?.lowercase(Locale.US) != "https" || host.isNullOrEmpty()) null else "https://$host"
    }.getOrNull()

    private fun fetchSite(origin: String?, host: String): String {
        val originHost = origin?.let { runCatching { URI(it).host }.getOrNull() }
            ?.lowercase(Locale.US)
            ?: return "cross-site"
        return when {
            originHost == host -> "same-origin"
            registrableDomain(originHost) == registrableDomain(host) -> "same-site"
            else -> "cross-site"
        }
    }
    private fun registrableDomain(host: String): String = host.split('.').takeLast(2).joinToString(".")
}
