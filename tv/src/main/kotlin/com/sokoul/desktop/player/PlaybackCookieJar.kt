package com.sokoul.desktop.player

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** A bounded, memory-only cookie store owned by one playback, shared by its HLS requests. */
internal class PlaybackCookieJar : CookieJar {
    private val cookies = ArrayList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { it.expiresAt <= now }
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now) this.cookies.add(cookie)
        }
        while (this.cookies.size > 256) this.cookies.removeAt(0)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    /** An extractor's Cookie header belongs only to the exact media host. */
    fun seed(url: HttpUrl, header: String?) {
        val parsed = header.orEmpty().split(';').take(64).mapNotNull { pair ->
            val split = pair.indexOf('=')
            if (split <= 0) return@mapNotNull null
            runCatching {
                Cookie.Builder().name(pair.substring(0, split).trim()).value(pair.substring(split + 1).trim())
                    .hostOnlyDomain(url.host).path("/").apply { if (url.isHttps) secure() }.build()
            }.getOrNull()
        }
        saveFromResponse(url, parsed)
    }
}
