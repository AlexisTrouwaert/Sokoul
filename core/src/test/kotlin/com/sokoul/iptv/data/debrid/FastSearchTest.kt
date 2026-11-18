package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.domain.AudioLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Torrentio asked without the token, then with it: one list, with the best of both answers. */
class FastSearchTest {
    @Test fun `the fast address carries no token`() {
        val plain = DebridAddons.torrentioPlain(french = true)
        assertFalse(plain.contains("realdebrid"))
        assertEquals("https://torrentio.strem.fun/language=french", plain)
        assertEquals("https://torrentio.strem.fun", DebridAddons.torrentioPlain(french = false))
        assertTrue(DebridAddons.torrentio("SECRET").contains("realdebrid=SECRET"))
    }

    private val hash = "3e3219582bc614cf52fa921209001343c361f318"

    @Test fun `the fast answer is read with its hash, file and name`() {
        val fast = DebridStreams.parse(
            """{"streams":[{"name":"Torrentio\n1080p","title":"[Anime Time] One Piece (0001-1071)\nSeason 01 - East Blue/[Anime Time] One Piece - 0062.mkv\n👤 138 💾 419.27 MB","infoHash":"$hash","fileIdx":499,"behaviorHints":{"filename":"[Anime Time] One Piece - 0062.mkv"}}]}""",
        ).single()
        assertEquals("$hash:499", fast.key)
        assertEquals(null, fast.url)
        assertEquals("[Anime Time] One Piece - 0062.mkv", fast.filename)
    }

    @Test fun `the slow answer enriches the version the fast one gave`() {
        val fast = DebridStream("$hash:499", "One Piece - 0062.mkv", null, hash, 499, "One Piece - 0062.mkv", Release(sizeBytes = 419_000_000))
        val slow = DebridStream("$hash:499", "One Piece - 0062.mkv", "https://torrentio.strem.fun/resolve/realdebrid/x/$hash/null/499/f.mkv", hash, 499, null, Release(cached = true, seeders = 138))
        val merged = DebridStreams.combine(listOf(listOf(fast), listOf(slow))).single()
        assertEquals(slow.url, merged.url)
        assertEquals(true, merged.release.cached)
        assertEquals(138, merged.release.seeders)
        assertEquals(419_000_000L, merged.release.sizeBytes)
        assertEquals("One Piece - 0062.mkv", merged.filename)
    }

    @Test fun `a later duplicate can supply the missing French language label`() {
        val fast = DebridStream("$hash:499", "Film.mkv", null, hash, 499, "Film.mkv", Release(sizeBytes = 419_000_000))
        val slow = DebridStream("$hash:499", "Film FRENCH.mkv", "https://torrentio.strem.fun/resolve/realdebrid/x/$hash/null/499/f.mkv", hash, 499, null,
            Release(language = AudioLanguage.VF, frenchVariant = "TRUEFRENCH", audio = listOf("FR"), cached = true))
        val merged = DebridStreams.combine(listOf(listOf(fast), listOf(slow))).single()
        assertEquals(AudioLanguage.VF, merged.release.language)
        assertEquals("TRUEFRENCH", merged.release.frenchVariant)
        assertEquals(listOf("FR"), merged.release.audio)

        val englishLabel = slow.copy(release = Release(language = AudioLanguage.VO, audio = listOf("EN")))
        val multi = DebridStreams.combine(listOf(listOf(fast), listOf(slow), listOf(englishLabel))).single()
        assertEquals(AudioLanguage.MULTI, multi.release.language)
        assertEquals(listOf("FR", "EN"), multi.release.audio)
    }

    @Test fun `the merge of one file reads the same whichever answer came first`() {
        val dual = DebridStream("$hash:7", "Film.Dual.mkv", null, hash, 7, "Film.Dual.mkv", Release(dub = true, audio = listOf("ES", "EN")))
        val french = DebridStream("$hash:7", "Film.MULTi.mkv", "https://torrentio.strem.fun/resolve/realdebrid/x/$hash/null/7/f.mkv", hash, 7, null,
            Release(language = AudioLanguage.MULTI, audio = listOf("FR", "EN")))
        val one = DebridStreams.combine(listOf(listOf(dual), listOf(french))).single().release
        val other = DebridStreams.combine(listOf(listOf(french), listOf(dual))).single().release
        assertEquals(one.language, other.language)
        assertEquals(one.audio, other.audio)
        assertEquals(one.dub, other.dub)
        assertEquals(listOf("FR", "EN", "ES"), one.audio)
        assertFalse("French announced for this file: not a foreign dub", one.dub)
        // Without French on either side, "Dual" from either answer marks the dub.
        val plain = french.copy(release = Release(audio = listOf("EN")))
        assertTrue(DebridStreams.combine(listOf(listOf(plain), listOf(dual))).single().release.dub)
        assertTrue(DebridStreams.combine(listOf(listOf(dual), listOf(plain))).single().release.dub)
    }

    @Test fun `different files of one torrent stay apart`() {
        val a = DebridStream("$hash:1", "a", null, hash, 1, "a.mkv", Release())
        val b = DebridStream("$hash:2", "b", null, hash, 2, "b.mkv", Release())
        assertEquals(2, DebridStreams.combine(listOf(listOf(a), listOf(b))).size)
    }

    @Test fun `same filename and size do not prove identical content`() {
        val a = DebridStream("$hash:1", "Episode.mkv", null, hash, 1, "Episode.mkv", Release(sizeBytes = 20_000_000))
        val b = a.copy(key = "different:1", infoHash = "different", release = a.release.copy(language = AudioLanguage.VF))
        assertEquals(2, DebridStreams.combine(listOf(listOf(a), listOf(b))).size)
    }
}
