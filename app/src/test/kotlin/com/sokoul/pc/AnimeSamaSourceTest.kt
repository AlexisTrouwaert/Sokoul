package com.sokoul.pc

import com.sokoul.pc.playback.session.*
import com.sokoul.pc.playback.sources.*
import com.sokoul.pc.playback.ui.*
import com.sokoul.iptv.domain.AudioLanguage
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*

class AnimeSamaSourceTest {
    private val fixture = """[{"name":"Naruto","seasons":[{"name":"OAV","episodes":[{}]},{"name":"Saison 1","episodes":[{},{}]},{"name":"Saison 2","episodes":[{"streaming_links":[{"language":"vf1","players":["https://video.sibnet.ru/shell.php?videoid=1"]},{"language":"vostfr","players":["https://vidmoly.to/embed-1.html"]}],"m3u8ByPlayer":{"https://video.sibnet.ru/shell.php?videoid=1":"https://cdn.test/vf.mp4","https://vidmoly.to/embed-1.html":"https://cdn.test/sub.m3u8"}}]}]}]"""
    @Test fun absoluteEpisodeSkipsSpecialsAndKeepsServerRanks() {
        val root = Json.parseToJsonElement(fixture) as JsonArray
        val target = AnimeSamaParse.target(root, listOf("Naruto"), false, 2, 1, 3)!!
        assertEquals(AnimeSamaParse.Target(1, 3, 1), target)
        val links = AnimeSamaParse.links(root, target)
        assertEquals(listOf(AudioLanguage.VF, AudioLanguage.VOSTFR), links.map { it.language })
        assertNull(AnimeSamaParse.target(root, listOf("Boruto"), false, 2, 1, 3))
        assertEquals(AudioLanguage.VF, WebParse.languageOf("vf2"))
        assertEquals(AudioLanguage.VO, WebParse.languageOf("VOENG"))
        assertNull(WebParse.languageOf("default"))
    }
    @Test fun sagaNumbersMapAbsoluteEpisodesWithoutRenumberingSpecials() {
        val first = List(61) { "{}" }.joinToString(",")
        val second = List(82) { "{}" }.joinToString(",")
        val saga = Json.parseToJsonElement("""[{"name":"One Piece","seasons":[{"name":"Saga 1 East Blue [Episode 1 a 61]","episodes":[$first]},{"name":"Saga 2 Alabasta [Episode 62 a 143]","episodes":[$second]}]}]""") as JsonArray
        assertEquals(AnimeSamaParse.Target(1, 2, 1), AnimeSamaParse.target(saga, listOf("One Piece"), false, 2, 1, 62))
        val explicit = Json.parseToJsonElement("""[{"name":"One Piece","seasons":[{"name":"OAV","number":1,"episodes":[{}]},{"name":"East Blue","number":1,"episodes":[$first]},{"name":"East Blue alternate","number":1,"episodes":[$first]},{"name":"Films","number":2,"episodes":[{}]},{"name":"Alabasta","number":2,"episodes":[$second]}]}]""") as JsonArray
        assertEquals(AnimeSamaParse.Target(1, 5, 1), AnimeSamaParse.target(explicit, listOf("One Piece"), false, 2, 1, 62))
        assertEquals(AnimeSamaParse.Target(1, 5, 1), AnimeSamaParse.target(explicit, listOf("One Piece"), false, 2, 1, null))
    }
    @Test fun aSeasonSplitInPartsKeepsEveryPartInTheCount() {
        val twelve = List(12) { "{}" }.joinToString(",")
        val root = Json.parseToJsonElement("""[{"name":"Frieren","seasons":[{"name":"Saison 1","episodes":[$twelve]},{"name":"Saison 1 Partie 2","episodes":[$twelve]},{"name":"Saison 2","episodes":[$twelve]}]}]""") as JsonArray
        // Absolute 14 is the second part's 2nd episode; absolute 25 the first of season 2.
        assertEquals(AnimeSamaParse.Target(1, 2, 2), AnimeSamaParse.target(root, listOf("Frieren"), false, 1, 14, 14))
        assertEquals(AnimeSamaParse.Target(1, 3, 1), AnimeSamaParse.target(root, listOf("Frieren"), false, 2, 1, 25))
        // Without an absolute number, the season's own numbering runs across its parts.
        assertEquals(AnimeSamaParse.Target(1, 2, 2), AnimeSamaParse.target(root, listOf("Frieren"), false, 1, 14, null))
    }
        @Test fun pendingCatalogueIsPolledOnlyForAnExactlyMatchedWork() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""[{"name":"Naruto","cataloguePending":true,"seasons":[]}]"""))
            server.enqueue(MockResponse().setBody(fixture))
            server.enqueue(MockResponse().setBody(fixture))
            val http = OkHttpClient()
            val source = AnimeSamaSource(http, { server.url("/").toString() }, { "test-key" }, StreamProbe(http))
            val request = PlayRequest("tv", 1, 2, 1, "Naruto", null, null)
            assertEquals(2, source.find(request, listOf("Naruto"), 3).versions.size)
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setBody("""[{"name":"Other Anime","cataloguePending":true,"seasons":[]}]"""))
            assertTrue(source.find(request, listOf("Naruto"), 3).versions.isEmpty())
            assertEquals(4, server.requestCount)
            server.enqueue(MockResponse().setBody(fixture))
            assertTrue(source.find(request, listOf("Naruto"), 1000).versions.isEmpty())
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun filmsRequireAnIdentifiableEpisodeAndMissingMapsYieldNoLinks() {
        val root = Json.parseToJsonElement("""[{"name":"Example","seasons":[{"name":"Films","episodes":[{"name":"Film A"},{"name":"Film B"}]}]}]""") as JsonArray
        assertNull(AnimeSamaParse.target(root, listOf("Example"), true, null, null, null))
        assertEquals(AnimeSamaParse.Target(1, 1, 2), AnimeSamaParse.target(root, listOf("Example", "Film B"), true, null, null, null))
        assertTrue(AnimeSamaParse.links(root, AnimeSamaParse.Target(1, 1, 2)).isEmpty())
    }
    @Test fun signedAnimeRelayUsesPerPlayerFormatAndRejectsUntrustedOrigins() {
        val signature = "a".repeat(43)
        val relay = "http://localhost:25569/native-proxy?id=1&exp=2000000000&sig=$signature"
        val root = Json.parseToJsonElement("""[{"name":"Anime","seasons":[{"name":"Saison 1","episodes":[{"streaming_links":[{"language":"vf","players":["https://vidmoly.example/embed-1.html"]}],"m3u8ByPlayer":{"https://vidmoly.example/embed-1.html":"$relay"},"mediaByPlayer":{"https://vidmoly.example/embed-1.html":{"url":"$relay","format":"hls"}}}]}]}]""") as JsonArray
        val target = AnimeSamaParse.Target(1, 1, 1)
        assertEquals("hls", AnimeSamaParse.links(root, target, "http://localhost:25565").single().mediaType)
        assertTrue(AnimeSamaParse.links(root, target, "http://another.example:25565").isEmpty())
        assertTrue(AnimeSamaParse.links(root, target).isEmpty())
    }
    @Test fun numberedFrenchDubPagesAreFrenchAudioAndVostfrIsNot() {
        val signature = "a".repeat(43)
        fun relay(id: Int) = "http://localhost:25569/native-proxy?sid=$id&exp=2000000000&sig=$signature"
        val players = listOf("vostfr" to "https://ansembed.net/embed-1.html", "vf" to "https://ansembed.net/embed-2.html", "vf2" to "https://sendvid.com/embed/3")
        val links = players.joinToString(",") { (lang, url) -> """{"language":"$lang","players":["$url"]}""" }
        val maps = players.withIndex().joinToString(",") { (i, p) -> "\"${p.second}\":\"${relay(i)}\"" }
        val root = Json.parseToJsonElement("""[{"name":"One Piece","seasons":[{"name":"Saga 1","number":1,"episodes":[{"streaming_links":[$links],"m3u8ByPlayer":{$maps}}]}]}]""") as JsonArray
        val found = AnimeSamaParse.links(root, AnimeSamaParse.Target(1, 1, 1), "http://localhost:25565").associate { it.url to it.language }
        assertEquals(AudioLanguage.VOSTFR, found[relay(0)])
        assertEquals(AudioLanguage.VF, found[relay(1)])
        assertEquals(AudioLanguage.VF, found[relay(2)])
    }
    @Test fun catalogueTransportFailureIsDifferentFromAnEmptyCatalogue() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"code":"ANIME_CATALOGUE_UNAVAILABLE"}"""))
            server.enqueue(MockResponse().setBody("[]"))
            val http = OkHttpClient()
            val source = AnimeSamaSource(http, { server.url("/").toString() }, { "test-key" }, StreamProbe(http))
            val request = PlayRequest("tv", 1, 1, 1, "Naruto", null, null)
            assertEquals(listOf(SourceNotice.AnimeSamaUnavailable), source.find(request, listOf("Naruto"), 1).notices)
            assertTrue(source.find(request, listOf("Naruto"), 1).notices.isEmpty())
        } finally { server.shutdown() }
    }
    @Test fun openingResolvedMediaPreservesFormatHeadersAndSubtitles() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val signature = "a".repeat(43)
            val relay = server.url("/native-proxy?sid=fixture&exp=2000000000&sig=$signature").toString()
            val payload = """[{"name":"Naruto","seasons":[{"name":"Saison 1","episodes":[{"streaming_links":[{"language":"vf","players":["https://vidmoly.example/embed-1.html"]}],"m3u8ByPlayer":{"https://vidmoly.example/embed-1.html":"$relay"},"mediaByPlayer":{"https://vidmoly.example/embed-1.html":{"url":"$relay","format":"mp4","headers":{"Referer":"https://vidmoly.example/","User-Agent":"Fixture agent"},"subtitles":[{"url":"https://cdn.example/fr.vtt","format":"vtt","lang":"fr"}]}}}]}]}]"""
            server.enqueue(MockResponse().setBody(payload))
            server.enqueue(MockResponse().setBody(payload))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/1000000").setBody("x"))
            val http = OkHttpClient()
            val source = AnimeSamaSource(http, { server.url("/").toString() }, { "test-key" }, StreamProbe(http))
            val result = source.find(PlayRequest("tv", 1, 1, 1, "Naruto", null, null), listOf("Naruto"), 1)
            val stream = result.versions.single().open(false) {}
            assertEquals("mp4", stream.mediaType)
            assertEquals("Fixture agent", stream.userAgent)
            assertEquals("https://vidmoly.example/", stream.referrer)
            assertEquals("fr", stream.externalSubtitles.single().language)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun vipDenialIsDistinctFromEmptyExtraction() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(fixture))
            server.enqueue(MockResponse().setBody(fixture.replace("\"name\":\"Naruto\"", "\"extractionStatus\":\"vip_required\",\"name\":\"Naruto\"")))
            val http = OkHttpClient()
            val source = AnimeSamaSource(http, { server.url("/").toString() }, { "test-key" }, StreamProbe(http))
            val result = source.find(PlayRequest("tv", 1, 2, 1, "Naruto", null, null), listOf("Naruto"), 3)
            assertTrue(result.versions.isEmpty())
            assertEquals(listOf(SourceNotice.AnimeSamaVipRequired), result.notices)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("3", request.requestUrl!!.queryParameter("season"))
            assertEquals("test-key", request.getHeader("x-access-key"))
        } finally { server.shutdown() }
    }
}
