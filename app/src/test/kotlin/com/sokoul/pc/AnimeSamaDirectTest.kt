package com.sokoul.pc

import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.sources.AnimeSamaDirect
import com.sokoul.pc.playback.sources.AnimeSamaDns
import com.sokoul.pc.playback.sources.AnimeSamaSite
import com.sokoul.pc.playback.sources.AnimeSamaSource
import com.sokoul.pc.playback.sources.NativeEmbedResolver
import com.sokoul.pc.playback.sources.StreamProbe
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anime-Sama read by the app (the server's parsing rules and fixtures), and used only when the Sokoul server is off. */
class AnimeSamaDirectTest {
    private fun fixture(name: String) = AnimeSamaDirectTest::class.java.getResource("/animesama/$name")!!.readText()
    private val page = "https://anime-sama.to/catalogue/one-piece/saison1/vf/"

    @Test fun seasonCardsExcludeCommentedAndTemplateCardsAndNumberOnlyPlainSeasons() {
        val panels = AnimeSamaSite.seasonPanels(fixture("catalogue.html"), "https://anime-sama.to/catalogue/one-piece/")
        assertEquals(
            listOf(
                Triple("Saga 1 East Blue [Episode 1 à 61]", "saison1", 1),
                Triple("Saga 2 Alabasta [Episode 62 à 143]", "saison2", 2),
                Triple("Films", "film", null),
                Triple("One Piece Heroines", "saison2hs", null),
                Triple("Kai - Saga 1 (East Blue)", "kai", null),
            ),
            panels.map { Triple(it.name, it.url.removePrefix("https://anime-sama.to/catalogue/one-piece/"), it.number) },
        )
    }

    @Test fun declaredLanguagesAreReadFromTheSeasonPage() {
        assertEquals(listOf("vostfr", "vf", "vf2"), AnimeSamaSite.availableLanguages(fixture("season-vf.html")))
        assertNull(AnimeSamaSite.availableLanguages("<html></html>"))
    }

    @Test fun referencesKeepEpisodePositionsSkipBlanksAndPutRemovedVideosLast() {
        val episodes = AnimeSamaSite.episodePlayers(fixture("season-vf.html"), page)
        assertEquals(3, episodes.size)
        assertEquals(listOf("272886", "109465"), episodes[0].map { AnimeSamaSite.reference(it)!!.id })
        assertEquals(listOf("109468"), episodes[1].map { AnimeSamaSite.reference(it)!!.id })
        assertEquals(listOf("109471", "272887"), episodes[2].map { AnimeSamaSite.reference(it)!!.id })
        val reference = episodes[1][0].toHttpUrl()
        assertNull("the expiring token is never stored", reference.queryParameter("j"))
        assertEquals("ansembed.net", reference.queryParameter("h"))
        assertEquals(page, AnimeSamaSite.reference(episodes[1][0])!!.page)
    }

    @Test fun legacyPlayerUrlsRemainSupported() {
        val legacy = "var eps1 = ['https://vidmoly.to/embed-a.html', 'https://proxy.liyao.space/------https://video.sibnet.ru/shell.php?videoid=1'];\nvar eps2 = ['https://sendvid.com/embed/x'];"
        assertEquals(
            listOf(listOf("https://vidmoly.to/embed-a.html", "https://sendvid.com/embed/x"), listOf("https://video.sibnet.ru/shell.php?videoid=1")),
            AnimeSamaSite.episodePlayers(legacy, page),
        )
    }

    @Test fun referencesOutsideCataloguePagesAreRejected() {
        assertNull(AnimeSamaSite.reference("https://evil.test/api/lien.php?l=1&p=/catalogue/x/saison1/vf/"))
        assertNull(AnimeSamaSite.reference("https://anime-sama.to/api/lien.php?l=1&p=https://evil.test/"))
        assertNull(AnimeSamaSite.reference("https://anime-sama.to/api/lien.php?l=abc&p=/catalogue/x/saison1/vf/"))
    }

    @Test fun secureLookupKeepsPublicAnswersOnlyAndOnlyForTheCatalogue() {
        val answer = AnimeSamaDns.parse("""{"Status":0,"Answer":[{"type":1,"data":"104.26.13.154","TTL":2},{"type":1,"data":"127.0.0.1","TTL":2}]}""")!!
        assertEquals(listOf("104.26.13.154"), answer.first.map { it.hostAddress })
        assertEquals(60L, answer.second)
        assertNull(AnimeSamaDns.parse("""{"Status":0,"Answer":[{"type":1,"data":"192.168.0.1","TTL":60}]}"""))
        assertNull(AnimeSamaDns.parse("""{"Status":3}"""))
        assertTrue(AnimeSamaDns.CATALOGUE.matches("anime-sama.to"))
        assertTrue(!AnimeSamaDns.CATALOGUE.matches("anime-sama.to.evil.test") && !AnimeSamaDns.CATALOGUE.matches("api.themoviedb.org"))
    }

    /** A small Anime-Sama: one work, one season in VOSTFR and VF, players given directly. */
    private fun site(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/template-php/defaut/fetch.php" -> MockResponse().setBody(
                    """<a class="asn-search-result" href="/catalogue/frieren/"><h3>Frieren</h3><p class="asn-search-result-subtitle">Sousou no Frieren</p></a>""")
                "/catalogue/frieren/" -> MockResponse().setBody("""<script>panneauAnime("Saison 1", "saison1/vostfr");</script>""")
                "/catalogue/frieren/saison1/vostfr/" -> MockResponse().setBody("""<script>var languesDispo = ["vostfr","vf"]; var eps1 = ["https://vidmoly.to/embed-a.html"];</script>""")
                "/catalogue/frieren/saison1/vf/" -> MockResponse().setBody("""<script>var eps1 = ["https://video.sibnet.ru/shell.php?videoid=7"];</script>""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    @Test fun withTheServerOffTheSiteIsReadAndItsPlayersGoToTheLocalExtractor() = runBlocking {
        val site = site()
        try {
            val http = OkHttpClient()
            // Port 9 on the loopback: nothing listens, as when Docker is off.
            val source = AnimeSamaSource(http, { "http://127.0.0.1:9" }, { null }, StreamProbe(http), NativeEmbedResolver(http),
                AnimeSamaDirect(http, site.url("/").toString().trimEnd('/')))
            val result = source.find(PlayRequest("tv", 209867, 1, 1, "Frieren", null, null), listOf("Frieren"), 1)
            assertEquals(setOf(AudioLanguage.VF, AudioLanguage.VOSTFR), result.versions.map { it.language }.toSet())
            assertEquals(setOf("video.sibnet.ru", "vidmoly.to"), result.versions.map { it.providerHost }.toSet())
        } finally {
            site.shutdown()
        }
    }

    @Test fun withTheServerOnTheSiteIsNotAsked() = runBlocking {
        val site = site()
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""[{"name":"Frieren","seasons":[{"name":"Saison 1","episodes":[{}]}]}]"""))
            enqueue(MockResponse().setBody("""[{"name":"Frieren","extractionStatus":"resolved","seasons":[{"name":"Saison 1","episodes":[{"streaming_links":[]}]}]}]"""))
            start()
        }
        try {
            val http = OkHttpClient()
            val source = AnimeSamaSource(http, { server.url("/").toString() }, { null }, StreamProbe(http), NativeEmbedResolver(http),
                AnimeSamaDirect(http, site.url("/").toString().trimEnd('/')))
            source.find(PlayRequest("tv", 209867, 1, 1, "Frieren", null, null), listOf("Frieren"), 1)
            assertEquals(2, server.requestCount)
            assertEquals(0, site.requestCount)
        } finally {
            site.shutdown()
            server.shutdown()
        }
    }
}
