package com.sokoul.iptv.data.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/** Real answers of each id service, recorded on 2026-11-08 (`src/test/resources/anime`). */
class AnimeIdsTest {
    private fun res(name: String) = javaClass.getResource("/anime/$name")!!.readText()
    private fun stream(name: String) = javaClass.getResourceAsStream("/anime/$name")!!

    private fun fribbIndex(): AnimeIndex {
        val out = StringWriter()
        val n = AnimeIndex.writeFribb(stream("fribb_sample.json"), out)
        assertEquals(19, n)
        return AnimeIndex.read(out.toString().reader().buffered())
    }

    @Test fun `Fribb links One Piece from its TMDB id`() {
        val op = fribbIndex().series(37854).single()
        assertEquals(21, op.anilist)
        assertEquals(21, op.mal)
        assertEquals(12, op.kitsu)
        assertEquals(69, op.anidb)
        assertEquals(listOf("tt0388629"), op.imdb)
        assertNull(op.tmdbSeason)
    }

    @Test fun `Fribb places each Demon Slayer arc in its TMDB season`() {
        val entries = fribbIndex().series(85937)
        val s3 = entries.single { it.type == AnimeType.TV && it.tmdbSeason == 3 }
        assertEquals(47778, s3.mal)
        assertEquals(44081, s3.kitsu)
        assertTrue(entries.any { it.type == AnimeType.MOVIE && it.tmdbSeason == 0 })
    }

    @Test fun `Fribb films keep their TMDB movie id`() {
        assertEquals(164, fribbIndex().movie(128).single().anilist)
    }

    @Test fun `manami is read as a stream, names and all`() {
        val out = StringWriter()
        assertEquals(3, AnimeIndex.writeManami(stream("manami_sample.json"), out))
        val index = AnimeIndex.read(out.toString().reader().buffered())
        val op = index.anilist(21)!!
        assertEquals(12, op.kitsu)
        assertEquals(1999, op.year)
        assertEquals(op, index.named("ONE PIECE").single())
        // A title made of punctuation does not break the stream that follows it.
        assertEquals(101922, index.named("Kimetsu no Yaiba").single().anilist)
    }

    @Test fun `ARM, animeapi and Kitsu give the same ids`() {
        val arm = AnimeIdsParse.arm(res("arm.json")).single()
        val api = AnimeIdsParse.animeApi(res("animeapi.json")).single()
        val kitsu = AnimeIdsParse.kitsuMappings(12, res("kitsu.json"))!!
        assertEquals(21, arm.anilist)
        assertEquals(37854, arm.tmdbTv)
        assertEquals(21, api.mal)
        assertEquals(21, kitsu.mal)
        assertTrue(arm.agreesWith(api))
        assertTrue(api.agreesWith(kitsu))
        assertEquals(24, AnimeIdsParse.kitsuEpisodeMinutes(res("kitsu_ep.json")))
    }

    @Test fun `a broken answer is no entry`() {
        assertTrue(AnimeIdsParse.arm("<html>").isEmpty())
        assertTrue(AnimeIdsParse.animeApi("""{"code":404,"error":"Not found"}""").isEmpty())
        assertNull(AnimeIdsParse.kitsuMappings(1, "{}"))
        val out = StringWriter()
        runCatching { AnimeIndex.writeFribb("[{\"type\":\"TV\"".byteInputStream(), out) }
        assertTrue(AnimeIndex.read(out.toString().reader().buffered()).size == 0)
    }

    @Test fun `SeaDex keeps public torrents with their files`() {
        val r = AnimeExtrasParse.seaDex(res("seadex.json"))
        assertEquals(3, r.size)
        assertTrue(r.all { it.infoHash.length == 40 && it.files.isNotEmpty() })
        assertTrue(r.first().best)
        assertTrue(AnimeExtrasParse.seaDex(res("seadex_none.json")).isEmpty())
    }

    @Test fun `AniSkip intervals only for a file of the same length`() {
        val all = AnimeExtrasParse.aniSkip(res("aniskip.json"))
        assertEquals(2, all.size)
        assertEquals(2, AnimeExtrasParse.fitting(all, 1500.0).size)
        assertTrue(AnimeExtrasParse.fitting(all, 1300.0).isEmpty())
        assertTrue(AnimeExtrasParse.aniSkip("""{"found":false,"results":[]}""").isEmpty())
    }
}
