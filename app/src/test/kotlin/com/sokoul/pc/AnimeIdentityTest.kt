package com.sokoul.pc

import com.sokoul.iptv.data.net.TmdbBrand
import com.sokoul.iptv.data.net.TmdbInfo
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.anime.AnimeIdentityResolver
import com.sokoul.pc.data.anime.AnimeLists
import com.sokoul.pc.data.anime.AnimeMatch
import com.sokoul.pc.data.anime.AnimeTmdb
import com.sokoul.pc.data.anime.AniTitle
import com.sokoul.pc.data.anime.MediaKind
import com.sokoul.pc.data.providers.ProviderHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The anime identity from real answers recorded on 2026-11-08, served by a fake network. */
class AnimeIdentityTest {
    private fun res(name: String) = javaClass.getResource("/anime/$name")!!.readText()

    /** Answers by host: a missing host is a server error, as when a service is down. */
    private fun http(answers: Map<String, String>): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val body = answers.entries.firstOrNull { req.url.toString().contains(it.key) }?.value
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (body == null) 500 else 200).message("x")
            .body((body ?: "").toResponseBody("application/json".toMediaType())).build()
    }).build()

    private fun provider(client: OkHttpClient): ProviderHttp {
        val store = HashMap<String, Pair<String, Long>>()
        return ProviderHttp(client, { store[it] }, { k, v, t -> store[k] = v to t })
    }

    private val onePiece = TmdbInfo(
        id = 37854, title = "One Piece", overview = null, poster = null, backdrop = null, logo = null, rating = null, year = 1999, runtimeMin = 24,
        genres = listOf("Animation"), cast = emptyList(), director = null, trailerYoutubeKey = null, originalTitle = "ワンピース",
        imdbId = "tt0388629", originalLanguage = "ja", genreIds = listOf(16),
    )
    private val liveAction = TmdbItem(111110, "ONE PIECE", "ONE PIECE", 2023, "p", null, null, null, listOf(10759, 18), "en", mediaType = "tv")

    private fun tmdb(info: TmdbInfo? = onePiece) = object : AnimeTmdb {
        override suspend fun details(kind: String, id: Int): TmdbInfo? = when (id) {
            37854 -> info
            111110 -> onePiece.copy(id = 111110, title = "ONE PIECE", year = 2023, originalLanguage = "en", genreIds = listOf(10759), brands = listOf(TmdbBrand("Netflix", null)))
            else -> null
        }
        override suspend fun seasonSize(tvId: Int, season: Int) = listOf(61, 16, 14, 39).getOrNull(season - 1)
        override suspend fun search(query: String) = listOf(liveAction)
    }

    private fun fribbLists(dir: File): AnimeLists {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(res("fribb_sample.json")))
        server.start()
        val lists = AnimeLists(dir, OkHttpClient(), withNames = false, fribbUrl = server.url("/fribb.json").toString(), minEntries = 5)
        assertTrue(runBlocking { lists.refresh(AnimeLists.FRIBB) })
        server.shutdown()
        return lists
    }

    @Test fun `One Piece is linked by the list on the device, and knows the 2023 series`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("anime").toFile()
        val resolver = AnimeIdentityResolver(fribbLists(dir), provider(http(mapOf("kitsu.app" to res("kitsu.json")))), tmdb())
        val id = resolver.identity("tv", 37854)!!
        assertEquals(21, id.main.anilist)
        assertEquals(21, id.main.mal)
        assertEquals(12, id.main.kitsu)
        assertEquals("fribb", id.sources.first())
        val homonym = id.homonyms.single()
        assertEquals(2023, homonym.year)
        assertFalse(homonym.animated)
        assertEquals(listOf("Netflix"), homonym.networks)
        val ref = resolver.episode(id, 37854, 2, 1)
        assertEquals(62, ref.absolute)
        assertEquals(12, ref.kitsu!!.id)
        assertEquals(62, ref.kitsu!!.episode)
        dir.deleteRecursively()
    }

    @Test fun `with one id service down, the next one answers`() = runBlocking<Unit> {
        val resolver = AnimeIdentityResolver(null, provider(http(mapOf("animeapi.my.id" to res("animeapi.json")))), tmdb())
        val id = resolver.identity("tv", 37854)!!
        assertEquals(21, id.main.anilist)
        assertEquals(12, id.main.kitsu)
        assertEquals("tmdb-id", id.sources.first())
    }

    @Test fun `nothing sure links nothing`() = runBlocking<Unit> {
        val resolver = AnimeIdentityResolver(null, provider(http(emptyMap())), tmdb(onePiece.copy(imdbId = null)))
        assertNull(resolver.identity("tv", 37854))
    }

    @Test fun `a title that is no anime asks no service`() = runBlocking<Unit> {
        var asked = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { asked++; it.proceed(it.request()) }).build()
        val drama = onePiece.copy(id = 1396, title = "Breaking Bad", originalLanguage = "en", genreIds = listOf(18))
        val resolver = AnimeIdentityResolver(null, provider(client), tmdb(drama))
        assertNull(resolver.identity("tv", 37854))
        assertEquals(0, asked)
    }

    @Test fun `a broken download keeps the list in place`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("anime").toFile()
        val lists = fribbLists(dir)
        val before = File(dir, "fribb.tsv").readText()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(res("fribb_sample.json").take(800)))
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val again = AnimeLists(dir, OkHttpClient(), withNames = false, fribbUrl = server.url("/f.json").toString(), minEntries = 5)
        assertFalse(again.refresh(AnimeLists.FRIBB))
        assertFalse(again.refresh(AnimeLists.FRIBB))
        assertEquals(before, File(dir, "fribb.tsv").readText())
        assertNotNull(lists.ids()?.series(37854)?.singleOrNull())
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test fun `a name search never falls back on the first result`() {
        fun t(english: String) = AniTitle(1, MediaKind.ANIME, english, null, null, null, null, "TV", null, null, null, null, null, null, 100, emptyList(), emptyList(), null, null, null, null, null)
        assertNull(AnimeMatch.best(listOf(t("Onigiri")), "Demon Slayer"))
        assertNull(AnimeMatch.best(listOf(t("Berserk of Gluttony")), "Berserk: The Golden Age"))
        assertEquals("Berserk", AnimeMatch.best(listOf(t("Berserk of Gluttony"), t("Berserk")), "Berserk")?.english)
    }
}
