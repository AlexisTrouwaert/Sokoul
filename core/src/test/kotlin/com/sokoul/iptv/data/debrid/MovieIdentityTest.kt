package com.sokoul.iptv.data.debrid

import org.junit.Assert.*
import org.junit.Test

class MovieIdentityTest {
    @Test fun `numeric movie titles are not interpreted as release years`() {
        assertFalse(DebridStreams.wrongMovie("1917.2019.2160p.mkv", listOf("1917"), 2019))
        assertTrue(DebridStreams.movieTitleMatches("1917.2019.2160p.mkv", listOf("1917")))
    }
    private val titles = listOf("Resident Evil: Extinction", "Resident Evil Extinction")
    @Test fun `reject another sequel year`() {
        assertTrue(DebridStreams.wrongMovie("Resident.Evil.Apocalypse.2004.MULTI.2160p.BluRay.mkv", titles, 2007))
        assertFalse(DebridStreams.wrongMovie("Resident.Evil.Extinction.2007.MULTI.2160p.BluRay.mkv", titles, 2007))
        assertFalse(DebridStreams.wrongMovie("Resident Evil Extinction MULTI UHD.mkv", titles, 2007))
    }
    @Test fun `select requested movie in a pack instead of largest`() {
        val wrong = RdFile(1, "/Resident Evil/Resident Evil Apocalypse 2004 2160p.mkv", 40_000_000_000L, true)
        val right = RdFile(2, "/Resident Evil/Resident Evil Extinction 2007 1080p.mkv", 8_000_000_000L, true)
        assertEquals(right, ReleaseName.pickFile(listOf(wrong, right), null, null, movieTitles = titles, movieYear = 2007))
        assertNull(ReleaseName.pickFile(listOf(wrong), null, null, movieTitles = titles, movieYear = 2007))
    }
    @Test fun `ambiguous packs have no largest file fallback`() {
        val files = listOf(RdFile(1, "/film-a.mkv", 4_000L, true), RdFile(2, "/film-b.mkv", 8_000L, true))
        assertNull(ReleaseName.pickFile(files, null, null, movieTitles = titles, movieYear = 2007))
    }
}
