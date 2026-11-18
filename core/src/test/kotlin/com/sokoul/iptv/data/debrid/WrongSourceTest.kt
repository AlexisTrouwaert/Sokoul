package com.sokoul.iptv.data.debrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Versions of another work or another episode, dropped before playback. Real names Torrentio returned for
 * "One Piece" S1E1 (the 1999 anime, tt0388629) on 2026-11-08: the TV played the 2023 series, then episode 319.
 */
class WrongSourceTest {
    private val onePiece = listOf("One Piece", "ワンピース")

    @Test fun `the 2023 series is not the 1999 anime`() {
        assertTrue(DebridStreams.otherYear("One Piece S01E01 2023 2160p NF WEB-DL DDP5 1 Atmos DV HDR H 265-HHWEB", onePiece, 1999, 1))
        assertTrue(DebridStreams.otherYear("ONE PIECE 2023 S01E01 Romance Dawn REPACK 1080p NF WEB-DL DDP5 1 Atmos H 26", onePiece, 1999, 1))
        assertTrue(DebridStreams.otherYear("ONE PIECE 2023 S01E01 ROMANCE DAWN 1080p NF WEB-DL DDP5 1 Atmos …", onePiece, 1999, 1))
    }

    @Test fun `versions of the anime stay`() {
        assertFalse(DebridStreams.otherYear("One Piece (1999-2000) s01e01-15 [Mkv - 1080p H264 - MultiLang Aac 2.0 - MultiSubs] [Restored]", onePiece, 1999, 1))
        assertFalse(DebridStreams.otherYear("[Feibanyama] One Piece EP0001 [IQIYI WebRip 2160p HEVC OPUS Multi-Audio Multi-Subs]", onePiece, 1999, 1))
        assertFalse(DebridStreams.otherYear("[Lia] One Piece - 0001-1000 [WEB-DL 1080p] [BATCH]", onePiece, 1999, 1))
        assertFalse(DebridStreams.otherYear("[ToonsHub] One Piece S01E01-16 1080p NF WEB-DL MULTi AAC2.0 H.264", onePiece, 1999, 1))
    }

    @Test fun `a late season may carry its year, unless right after the title`() {
        assertFalse(DebridStreams.otherYear("One Piece S21 2019 1080p WEB", onePiece, 1999, 21))
        assertTrue(DebridStreams.otherYear("Doctor Who 2005 S03E01 1080p BluRay", listOf("Doctor Who"), 1963, 3))
        assertFalse(DebridStreams.otherYear("Doctor Who 2005 S03E01 1080p BluRay", listOf("Doctor Who"), 2005, 3))
    }

    @Test fun `without the work's year or a known title, nothing is dropped`() {
        assertFalse(DebridStreams.otherYear("One Piece 2023 S01E01 1080p", onePiece, null, 1))
        assertFalse(DebridStreams.otherYear("Big Mom 2023 S01E01 1080p", onePiece, 1999, 1))
    }

    @Test fun `the wrong file of a pack is dropped`() {
        assertTrue(DebridStreams.wrongEpisode("One Piece English Dub 319.mp4", 1, 1))
        assertTrue(DebridStreams.wrongEpisode("[Erai-raws] One Piece - 0892 [1080p].mkv", 1, 1))
        assertTrue(DebridStreams.wrongEpisode("One.Piece.S01E05.1080p.NF.WEB-DL.mkv", 1, 1))
    }

    @Test fun `the right file stays, whatever its name`() {
        assertFalse(DebridStreams.wrongEpisode("[Judas] One Piece 001-206 [4x3]/[Judas] One Piece - 001.mkv", 1, 1))
        assertFalse(DebridStreams.wrongEpisode("[One Pace][1] Romance Dawn 01 [1080p][FB72C13F].mkv", 1, 1))
        assertFalse(DebridStreams.wrongEpisode("[Lia] One Piece - 0001 [1080p].mkv", 1, 1))
        assertFalse(DebridStreams.wrongEpisode("One Piece - 001 - I'm Luffy The Man Who's Gonna Be King of the Pirates!.mkv", 1, 1))
        assertFalse(DebridStreams.wrongEpisode("One.Piece.S01E01.1080p.NF.WEB-DL.MULTi.AAC2.0.H.264.mkv", 1, 1))
        assertFalse(DebridStreams.wrongEpisode("[Feibanyama] One Piece EP0001 [IQIYI WebRip 2160p HEVC OPUS]", 1, 1))
        assertFalse(DebridStreams.wrongEpisode("One Piece (1999) s01e01 Sono Luffy! Colui che diventerà il Re dei Pirati.mkv", 1, 1))
    }

    @Test fun `absolute numbering of anime`() {
        // TMDB's S02E01 is episode 62
        assertFalse(DebridStreams.wrongEpisode("[Judas] One Piece - 062.mkv", 2, 1, absolute = 62))
        assertTrue(DebridStreams.wrongEpisode("[Judas] One Piece - 061.mkv", 2, 1, absolute = 62))
        assertFalse(DebridStreams.wrongEpisode("One.Piece.S02E01.1080p.mkv", 2, 1, absolute = 62))
    }

    @Test fun `past season 1 without an absolute number, only SxxEyy is judged`() {
        assertFalse(DebridStreams.wrongEpisode("[Judas] One Piece - 062.mkv", 2, 1))
        assertTrue(DebridStreams.wrongEpisode("Show.S02E07.1080p.mkv", 2, 1))
    }

    @Test fun `without a readable number, the version is kept`() {
        assertFalse(DebridStreams.wrongEpisode("Romance Dawn [1080p].mkv", 1, 1))
        assertFalse(DebridStreams.wrongEpisode(null, 1, 1))
        assertFalse(DebridStreams.wrongEpisode("Film.2021.2160p.mkv", null, null))
    }

    // Real names Comet gave for "The Hunger Games: Sunrise on the Reaping" (2026) on 2026-11-18, while it was in cinemas.
    private val reaping = listOf("The Hunger Games: Sunrise on the Reaping", "Hunger Games : Lever de soleil sur la moisson")

    @Test fun `an audiobook is never a version`() {
        assertTrue(DebridStreams.notVideo("The Hunger Games Trilogy + Prequel (Audiobook)"))
        assertTrue(DebridStreams.notVideo("Dune.Part.Two.Original.Soundtrack.FLAC"))
        assertFalse(DebridStreams.notVideo("Lost.S01E01.1080p.BluRay.x264"))
        assertFalse(DebridStreams.notVideo("The.Host.2006.1080p.BluRay"))
    }

    @Test fun `a box set made before a new film does not hold it`() {
        assertTrue(DebridStreams.packBeforeFilm("The Hunger Games Quadrilogy.UHD BDRemux 2160p Dolby Vision", reaping, 2026, thisYear = 2026))
        // An older film can be in its franchise's box set: the file is picked by name in the pack.
        assertFalse(DebridStreams.packBeforeFilm("The Hunger Games Quadrilogy.UHD BDRemux 2160p Dolby Vision", listOf("The Hunger Games"), 2012, thisYear = 2026))
        // The word is part of the film's own title.
        assertFalse(DebridStreams.packBeforeFilm("The.Twilight.Saga.New.Moon.2026.1080p", listOf("The Twilight Saga: New Moon"), 2026, thisYear = 2026))
    }
}
