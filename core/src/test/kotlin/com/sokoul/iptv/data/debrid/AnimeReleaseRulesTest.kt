package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.data.anime.Homonym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real names seen for One Piece on 2026-11-08 (Torrentio, Comet, SeaDex). */
class AnimeReleaseRulesTest {
    private val netflix = Homonym(111110, "ONE PIECE", 2023, animated = false, networks = listOf("Netflix"))
    private fun check(season: Int, episode: Int, absolute: Int?) =
        AnimeCheck(listOf("One Piece", "ワンピース"), 1999, season, episode, absolute, listOf(netflix))

    private fun v(name: String, file: String? = null, s: Int = 1, e: Int = 1, a: Int? = 1) = AnimeReleaseRules.verdict(name, file, check(s, e, a))

    @Test fun `the 2023 series is another work, with or without its year`() {
        assertEquals(Verdict.Rejected(Verdict.Reason.OtherWork), v("ONE PIECE 2023 S01E01 Romance Dawn REPACK 1080p NF WEB-DL DDP5 1 Atmos H 26"))
        assertEquals(Verdict.Rejected(Verdict.Reason.OtherWork), v("One Piece S01E01 2023 2160p NF WEB-DL DDP5 1 Atmos DV HDR H 265-HHWEB"))
        // A late season of the series names it too.
        assertEquals(Verdict.Rejected(Verdict.Reason.OtherWork), v("One Piece 2023 S02E03 1080p NF WEB-DL", s = 2, e = 3, a = 64))
        // Without its year, the platform alone makes it doubtful, not rejected.
        val noYear = v("One.Piece.S01E01.1080p.NF.WEB-DL.DDP5.1.H.264-FLUX")
        assertEquals("platform:NF", (noYear as Verdict.Unknown).doubt)
        val pack = v("One Piece S01 1080p NF WEB-DL DDP5 1 Atmos H 264")
        assertEquals("platform:NF", (pack as Verdict.Unknown).doubt)
    }

    @Test fun `a 4K release without anime marks is doubtful when a live-action series exists`() {
        // Seen for S02E01 on 2026-11-08: 8 to 9.5 GB in 4K, no year, no group.
        assertEquals("live-action", (v("ONE.PIECE.S02E01.2160p.SDR-X-Kira.mkv", s = 2, e = 1, a = 62) as Verdict.Unknown).doubt)
        assertEquals(Verdict.Confirmed, v("[Feibanyama] One Piece EP0001 [IQIYI WebRip 2160p HEVC OPUS Multi-Audio Multi-Subs]"))
        // Without a live-action work of that name, a 4K release is not suspected.
        assertEquals(Verdict.Confirmed, AnimeReleaseRules.verdict("Frieren.S01E01.2160p.WEB", null, AnimeCheck(listOf("Frieren"), 2023, 1, 1, 1)))
    }

    @Test fun `a release named by the catalog's season, where the anime's number differs, is doubtful`() {
        // Seen for S02E01 on the TV, 2026-11-09: 1.8 to 2.6 GB, the size of the live-action series' episodes.
        assertEquals("live-action", (v("One.Piece.S02E01.1080p.WEB.H265-CHiLL.mkv", s = 2, e = 1, a = 62) as Verdict.Unknown).doubt)
        // The anime's own number stays confirmed, and so does the first season, where both numbers are the same.
        assertEquals(Verdict.Confirmed, v("[Judas] One Piece - 062.mkv", s = 2, e = 1, a = 62))
        assertEquals(Verdict.Confirmed, v("One Piece", "One.Piece.S01E62.mkv", s = 2, e = 1, a = 62))
        assertEquals(Verdict.Confirmed, v("One.Piece.S01E01.MULTi.1080p.WEB.H265.mkv"))
    }

    @Test fun `anime releases on Netflix are not doubtful`() {
        val r = v("[ToonsHub] One Piece S01E01-16 1080p NF WEB-DL MULTi AAC2.0 H.264")
        assertTrue(r !is Verdict.Rejected)
        assertTrue(r is Verdict.Confirmed || (r as Verdict.Unknown).doubt == null)
    }

    @Test fun `the wrong file of a pack`() {
        assertEquals(Verdict.Rejected(Verdict.Reason.OtherEpisode), v("One Piece English Dub", "One Piece English Dub 319.mp4"))
        assertEquals(
            Verdict.Rejected(Verdict.Reason.OtherEpisode),
            v("[Anime Time] One Piece (0001-1071+Movies+Specials)", "Season 01 - East Blue/[Anime Time] One Piece - 0061 - x.mkv", s = 2, e = 1, a = 62),
        )
    }

    @Test fun `the first episode of a late season is not episode 1`() {
        // SeaDex's release of episodes 337 to 381, seen offered for episode 1 on the TV (2026-11-08).
        assertEquals(Verdict.Rejected(Verdict.Reason.OtherEpisode), v("[A&C] One Piece", "[A&C] One Piece S10E01 (0337) (WEB HEVC 1080p) [Multi-Audio-Subs] [F161A3B0].mkv"))
        assertEquals(Verdict.Confirmed, v("[A&C] One Piece", "[A&C] One Piece S01E01 (0001) (DVD HEVC 480p) [Multi-Audio-Subs] [43DA846C].mkv"))
        assertEquals(Verdict.Confirmed, v("One Piece", "One.Piece.S01E62.mkv", s = 2, e = 1, a = 62))
    }

    @Test fun `episode 62 by its absolute number`() {
        assertEquals(
            Verdict.Confirmed,
            v("[Anime Time] One Piece (0001-1071+Movies+Specials)", "Season 01 - East Blue/[Anime Time] One Piece - 0062 - The First Line of Defense.mkv", s = 2, e = 1, a = 62),
        )
        assertEquals(Verdict.Confirmed, v("[A&C] One Piece", "[A&C] One Piece S10E01 (0337) (WEB HEVC 1080p) [Multi-Audio-Subs] [F161A3B0].mkv", s = 9, e = 1, a = 337))
    }

    @Test fun `a release without any of the work's names is another work`() {
        // Comet answered "kitsu:12:1" with these on 2026-11-08.
        assertEquals(Verdict.Rejected(Verdict.Reason.OtherWork), v("[Trix] Blue Box S01E01 [BD 1080p AV1 Opus] (Dual Audio, Multi Subs)"))
        assertEquals(Verdict.Rejected(Verdict.Reason.OtherWork), v("The.Walking.Dead.S01E01.FRENCH.1080p.x265-FLOP"))
        // The name may be in the file only.
        assertEquals(Verdict.Confirmed, v("[Judas] Batch 001-206 [1080p]", "[Judas] One Piece - 001.mkv"))
        // Without a name in Latin letters, nothing is decided on names.
        assertTrue(AnimeReleaseRules.verdict("Blue Box S01E01", null, AnimeCheck(listOf("ワンピース"), 1999, 1, 1, 1)) !is Verdict.Rejected)
    }

    @Test fun `a film is not an episode`() {
        assertEquals(Verdict.Rejected(Verdict.Reason.NotAnEpisode), v("One Piece Film Red 2022 1080p BluRay", "One Piece Film Red.mkv"))
    }

    @Test fun `an unreadable name stays, unconfirmed`() {
        val r = v("One Piece Romance Dawn [1080p]")
        assertTrue(r is Verdict.Unknown)
        assertNull((r as Verdict.Unknown).doubt)
    }

    @Test fun `without homonyms, the old rules still judge`() {
        val plain = AnimeCheck(listOf("Ted Lasso"), 2020, 1, 1, null)
        assertEquals(Verdict.Confirmed, AnimeReleaseRules.verdict("Ted.Lasso.S01E01.1080p.ATVP.WEB-DL", null, plain))
        assertEquals(Verdict.Rejected(Verdict.Reason.OtherEpisode), AnimeReleaseRules.verdict("Ted Lasso S01 pack", "Ted.Lasso.S01E05.mkv", plain))
    }

    @Test fun `a pack file is picked by its absolute number`() {
        val files = (1..206).map { RdFile(it, "[Judas] One Piece 001-206/[Judas] One Piece - %03d.mkv".format(it), 400_000_000L + it, true) }
        assertEquals(62, ReleaseName.pickFile(files, 2, 1, 62)!!.id)
        assertNull(ReleaseName.pickFile(files.filter { it.id != 62 }, 2, 1, 62))
        assertNotNull(ReleaseName.pickFile(listOf(RdFile(1, "Romance Dawn.mkv", 1, true)), 1, 1, 1))
    }
}
