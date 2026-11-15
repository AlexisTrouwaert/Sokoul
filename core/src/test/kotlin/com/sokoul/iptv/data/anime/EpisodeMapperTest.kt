package com.sokoul.iptv.data.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeMapperTest {
    // TMDB's first seasons of One Piece (its arcs): 61, 16, 14, 39, 13, 52, 33…
    private val onePieceSizes = listOf(61, 16, 14, 39, 13, 52, 33, 35, 73, 45, 26, 14, 39, 58, 62, 49, 118, 33, 98, 14, 197, 30)
    private val onePiece = listOf(AnimeEntry(AnimeType.TV, anilist = 21, mal = 21, kitsu = 12, anidb = 69, tmdbTv = 37854))
    private val demonSlayer = listOf(
        AnimeEntry(AnimeType.TV, mal = 38000, kitsu = 41370, tmdbTv = 85937, tmdbSeason = 1),
        AnimeEntry(AnimeType.TV, mal = 49926, kitsu = 45249, tmdbTv = 85937, tmdbSeason = 2),
        AnimeEntry(AnimeType.TV, mal = 47778, kitsu = 44081, tmdbTv = 85937, tmdbSeason = 3),
        AnimeEntry(AnimeType.MOVIE, kitsu = 44388, tmdbTv = 85937, tmdbSeason = 0),
    )

    @Test fun `episode 1 of One Piece`() {
        val r = EpisodeMapper.map(1, 1, onePieceSizes, onePiece)
        assertEquals(1, r.absolute)
        assertEquals(EntryEpisode(12, 1), r.kitsu)
    }

    @Test fun `late seasons already numbered from the first episode are kept as they are`() {
        // TMDB on 2026-11-09: season 22 runs 1089 to 1155, season 23 from 1156.
        val sizes = onePieceSizes.take(21) + listOf(67, 25)
        val r = EpisodeMapper.map(23, 1170, sizes, onePiece)
        assertEquals(1170, r.absolute)
        assertEquals(EntryEpisode(12, 1170), r.kitsu)
        assertEquals(1100, EpisodeMapper.absolute(22, 1100, sizes))
        // Counted in its season, it is still added to the seasons before.
        assertEquals(1161, EpisodeMapper.absolute(23, 5, sizes))
    }

    @Test fun `TMDB S02E01 of One Piece is episode 62`() {
        val r = EpisodeMapper.map(2, 1, onePieceSizes, onePiece)
        assertEquals(62, r.absolute)
        assertEquals(EntryEpisode(12, 62), r.kitsu)
        assertEquals(EntryEpisode(21, 62), r.mal)
    }

    @Test fun `a recent episode of One Piece`() {
        val before = onePieceSizes.take(21).sum()
        val r = EpisodeMapper.map(22, 5, onePieceSizes, onePiece)
        assertEquals(before + 5, r.absolute)
        assertEquals(EntryEpisode(12, before + 5), r.kitsu)
    }

    @Test fun `a season with its own entry counts in that entry`() {
        val r = EpisodeMapper.map(3, 4, listOf(26, 18, 11), demonSlayer)
        assertEquals(48, r.absolute)
        assertEquals(EntryEpisode(47778, 4), r.mal)
        assertEquals(EntryEpisode(44081, 4), r.kitsu)
    }

    @Test fun `a second part of a season starts after its offset`() {
        val parts = listOf(
            AnimeEntry(AnimeType.TV, mal = 1, tmdbSeason = 1, tmdbOffset = 0),
            AnimeEntry(AnimeType.TV, mal = 2, tmdbSeason = 1, tmdbOffset = 12),
        )
        assertEquals(EntryEpisode(1, 12), EpisodeMapper.map(1, 12, listOf(24), parts).mal)
        assertEquals(EntryEpisode(2, 1), EpisodeMapper.map(1, 13, listOf(24), parts).mal)
    }

    @Test fun `a season of unknown size makes the number unknown`() {
        val r = EpisodeMapper.map(3, 1, listOf(61, null), onePiece)
        assertNull(r.absolute)
        assertNull(r.kitsu)
        assertNull(EpisodeMapper.absolute(2, 1, listOf(0)))
    }

    @Test fun `a season the lists do not place has no entry`() {
        assertNull(EpisodeMapper.map(4, 1, listOf(26, 18, 11, 11), demonSlayer).mal)
        // A film is never an episode.
        assertNull(EpisodeMapper.entryFor(0, 1, demonSlayer))
    }

    @Test fun `back from the absolute number`() {
        assertEquals(2 to 1, EpisodeMapper.place(62, onePieceSizes))
        assertEquals(1 to 61, EpisodeMapper.place(61, onePieceSizes))
        assertNull(EpisodeMapper.place(9999, listOf(61, 16)))
    }
}
