package com.sokoul.desktop.player

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PlaybackCookieJarTest {
    private val media = "https://cdn.example.test/path/master.m3u8".toHttpUrl()

    @Test fun extractedCookiesStayOnTheExactMediaHost() {
        val jar = PlaybackCookieJar()
        jar.seed(media, "token=one; gate=two")
        assertEquals(listOf("token", "gate"), jar.loadForRequest(media).map { it.name })
        assertEquals(2, jar.loadForRequest("https://cdn.example.test/segment.ts".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("https://child.cdn.example.test/segment.ts".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://another.test/segment.ts".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://cdn.example.test/segment.ts".toHttpUrl()).isEmpty())
    }

    @Test fun renewedCookiesReplaceOldValuesAndExpiredCookiesDisappear() {
        val jar = PlaybackCookieJar()
        jar.seed(media, "gate=old")
        jar.saveFromResponse(media, listOf(Cookie.parse(media, "gate=new; Path=/; Secure")!!))
        assertEquals("new", jar.loadForRequest(media).single().value)
        jar.saveFromResponse(media, listOf(Cookie.parse(media, "gate=; Path=/; Max-Age=0")!!))
        assertTrue(jar.loadForRequest(media).isEmpty())
    }

    @Test fun storageIsBoundedAndFreshPlaybackHasNoCookies() {
        val jar = PlaybackCookieJar()
        repeat(300) { n -> jar.seed(media, "cookie$n=value") }
        assertEquals(256, jar.loadForRequest(media).size)
        assertEquals("cookie44", jar.loadForRequest(media).first().name)
        assertTrue(PlaybackCookieJar().loadForRequest(media).isEmpty())
    }
}
