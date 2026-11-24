package com.sokoul.pc

import com.sokoul.desktop.data.AnimeLookup
import com.sokoul.desktop.data.AnimeSearch
import com.sokoul.desktop.data.Catalog
import com.sokoul.desktop.data.DebridResult
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.DesktopDebrid
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.iptv.data.anime.EntryEpisode
import com.sokoul.iptv.data.debrid.AnimeCheck
import com.sokoul.iptv.data.debrid.DebridQuery
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The search asks Torrentio and Comet by IMDb id, and Torrentio by Kitsu id once the anime is known, then merges what they
 * answer. The services are played by a local server; the requests keep their paths.
 */
class MultiSourceSearchTest {
    private val server = MockWebServer()
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var db: DesktopDb
    private lateinit var debrid: DesktopDebrid
    private val info = sampleInfo(37854).copy(title = "One Piece", originalTitle = "One Piece", imdbId = "tt0388629", year = 1999)
    private val query = DebridQuery(37854, series = true, season = 1, episode = 1)

    private fun stream(hash: Char, file: String) =
        """{"name":"Torrentio\n1080p","title":"$file\n👤 10","infoHash":"${hash.toString().repeat(40)}","fileIdx":0,"behaviorHints":{"filename":"$file","videoSize":1400000000}}"""

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                val body = when {
                    "kitsu:12:1" in path -> """{"streams":[${stream('b', "[SubsPlease] One Piece - 001 (1080p).mkv")}]}"""
                    (path.startsWith("/language=french/stream/") || path.startsWith("/stream/")) -> """{"streams":[${stream('a', "One.Piece.S01E01.1080p.WEB.mkv")}]}"""
                    else -> """{"streams":[]}"""
                }
                // Comet is the slow service: it answers after its time is up.
                val slow = !path.startsWith("/language=french/") && !path.startsWith("/stream/") && !path.contains("realdebrid=")
                return MockResponse().setBody(body).apply { if (slow) setHeadersDelay(4, TimeUnit.SECONDS) }
            }
        }
        server.start()
        val base = server.url("/")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val local = (base.toString().trimEnd('/') + url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")).toHttpUrl()
            chain.proceed(chain.request().newBuilder().url(local).build())
        }.build()
        db = DesktopDb(Files.createTempDirectory("sokoul-search").toFile().resolve("t.db"))
        ProviderConfig.setKey(db, "realdebrid", "TEST-TOKEN")
        db.putSetting(DesktopDebrid.TIMEOUT_SETTING + "comet", "1")
        debrid = DesktopDebrid(db, Catalog(db), http)
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun search(lookup: AnimeLookup?): List<DebridResult> = runBlocking {
        debrid.search(query, info, refresh = true) { lookup }.toList()
    }

    @Test fun `without an identity only the IMDb requests leave`() {
        val results = search(null)
        assertTrue(paths.toString(), paths.none { "kitsu" in it })
        assertTrue(paths.toString(), paths.all { "tt0388629:1:1" in it })
        assertEquals(setOf("a".repeat(40)), results.last().streams.mapNotNull { it.infoHash }.toSet())
    }

    @Test fun `the Kitsu path is asked and merged, and a slow service holds nothing back`() {
        val anime = AnimeSearch(AnimeCheck(listOf("One Piece"), 1999, 1, 1, 1))
        val started = System.nanoTime()
        val results = search(AnimeLookup(query.copy(absolute = 1, kitsu = EntryEpisode(12, 1)), anime))
        val seconds = (System.nanoTime() - started) / 1e9

        // Only Torrentio is asked by Kitsu id: Comet answered it with other series.
        assertEquals(paths.toString(), 2, paths.count { "kitsu:12:1" in it })
        assertTrue(paths.filter { "kitsu" in it }.all { (it.startsWith("/language=french/") || it.startsWith("/stream/")) })
        val last = results.last()
        assertTrue(last.done)
        assertEquals(setOf("a".repeat(40), "b".repeat(40)), last.streams.mapNotNull { it.infoHash }.toSet())
        // Versions were sent before the end, and Comet, cut after its second, never made the search wait its 4 s.
        assertTrue(results.dropLast(1).any { it.streams.isNotEmpty() })
        assertTrue("took $seconds s", seconds < 3.5)
        assertFalse(paths.isEmpty())
    }

    @Test fun `cached results keep their resolved episode query without looking up the anime twice`() = runBlocking<Unit> {
        val resolved = query.copy(absolute = 1, kitsu = EntryEpisode(12, 1))
        val anime = AnimeSearch(AnimeCheck(listOf("One Piece"), 1999, 1, 1, 1))
        val initial = debrid.search(query, info, refresh = true) { AnimeLookup(resolved, anime) }.toList().last()
        var repeatedLookup = false
        val cached = debrid.search(query, info, refresh = false) { repeatedLookup = true; null }.toList().single()
        assertFalse(repeatedLookup)
        assertEquals(resolved, cached.query)
        assertEquals(initial.streams, cached.streams)
        assertTrue(cached.done)
    }

    @Test fun `a disabled service gets no call at all`() {
        db.putSetting(DesktopDebrid.SERVICES, "COMET")
        search(null)
        // Torrentio's quick, French and configured requests are all gone; only Comet's path is asked.
        assertTrue(paths.toString(), paths.none { it.startsWith("/stream/") || it.startsWith("/language=french/") || "realdebrid=" in it })
        assertTrue(paths.toString(), paths.isNotEmpty())
        paths.clear()
        db.putSetting(DesktopDebrid.SERVICES, "TORRENTIO")
        search(null)
        // Comet's address carries its configuration, never a Torrentio path: none was asked.
        assertTrue(paths.toString(), paths.all { it.startsWith("/stream/") || it.startsWith("/language=french/") || "realdebrid=" in it })
    }

    @Test fun `retry after is read as seconds or as a date, within bounds`() {
        assertEquals(30_000L, DesktopDebrid.retryAfterMs("30"))
        assertEquals(60_000L, DesktopDebrid.retryAfterMs(null))
        assertEquals(60_000L, DesktopDebrid.retryAfterMs("soon"))
        assertEquals(15 * 60_000L, DesktopDebrid.retryAfterMs("86400"))
        val now = java.time.ZonedDateTime.of(2026, 11, 18, 10, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(120_000L, DesktopDebrid.retryAfterMs("Wed, 18 Nov 2026 10:02:00 GMT", now))
    }

    @Test fun `a service that answered 429 is left alone and the others go on`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                val torrentio = path.startsWith("/stream/") || path.startsWith("/language=french/") || "realdebrid=" in path
                return if (torrentio) MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
                else MockResponse().setBody("""{"streams":[${stream('c', "One.Piece.S01E01.1080p.mkv")}]}""")
            }
        }
        db.putSetting(DesktopDebrid.TIMEOUT_SETTING + "comet", "10")
        search(null)
        val first = paths.count { it.startsWith("/stream/") || it.startsWith("/language=french/") || "realdebrid=" in it }
        assertTrue(first > 0)
        paths.clear()
        val results = search(null)
        // Torrentio asked to wait two minutes: no call to it, while Comet still answers.
        assertEquals(paths.toString(), 0, paths.count { it.startsWith("/stream/") || it.startsWith("/language=french/") || "realdebrid=" in it })
        assertEquals(setOf("c".repeat(40)), results.last().streams.mapNotNull { it.infoHash }.toSet())
    }

    @Test fun `a service's time is set per device`() {
        assertEquals(1_000L, debrid.timeoutMs("https://comet.elfhosted.com/abc/stream/series/tt1:1:1.json"))
        assertEquals(DesktopDebrid.FAST_TIMEOUT_MS, debrid.timeoutMs("https://torrentio.strem.fun/language=french/stream/series/tt1:1:1.json"))
        assertEquals(DesktopDebrid.TIMEOUT_MS, debrid.timeoutMs("https://torrentio.strem.fun/realdebrid=x/stream/series/tt1:1:1.json"))
    }
}
