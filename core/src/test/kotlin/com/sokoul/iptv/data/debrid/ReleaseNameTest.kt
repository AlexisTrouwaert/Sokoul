package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.iptv.domain.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Langue et définition lues dans de vrais noms de versions (spec `release-name-parsing`). */
class ReleaseNameTest {
    @Test
    fun a_foreign_dub_beside_the_original_is_marked() {
        // Torrentio titles seen for The Handmaid's Tale S04E05 (2026-11-17).
        assertTrue(ReleaseName.parse("The.Handmaids.Tale.2017.s01.a.s06.1080p.WEB-DL.H.264.Dual.YG⭐\nS04/S04E005_Episode 5.mkv\n👤 43 💾 223.61 MB ⚙️ ThePirateBay\nDual Audio").dub)
        assertTrue(ReleaseName.parse("El cuento de la criada (2017) [tmdbid-69478]\nSeason 04/El cuento de la criada - S04E05 - Chicago - [WEBRip-1080p] - [spa, eng] - LasCositas.mkv\n👤 1 💾 2.27 GB ⚙️ EXT\n🇬🇧 / 🇪🇸").dub)
        assertFalse("plain release", ReleaseName.parse("The.Handmaids.Tale.S04E05.1080p.WEB.H264-GGWP").dub)
        assertFalse("French with the original", ReleaseName.parse("The.Handmaids.Tale.S04E05.MULTi.1080p.WEB.DDP5.1.H264-FRATERNiTY\nMulti Audio / 🇫🇷 / 🇬🇧").dub)
        assertFalse("one other language may be the original", ReleaseName.parse("Squid.Game.S01E01.1080p.WEB\n🇰🇷").dub)
    }

    @Test fun `DUAL sans francais - langue inconnue (version turque et anglaise)`() {
        // nom réel classé « MULTI 4K » sur le PC : le film démarrait en turc
        val r = ReleaseName.parse("Inception.2010.UHD.Bluray.1080p.DUAL.x264-HDM.mkv")
        assertNull(r.language)
        assertEquals(VideoQuality.FHD_1080, r.quality)
    }

    @Test fun `episode numbers followed by an underscore are read`() {
        assertEquals(4 to 6, ReleaseName.episodeTag("S04E006_Episode 6.mkv"))
        assertEquals(4 to 5, ReleaseName.episodeTag("The.Handmaids.Tale.S04E05.1080p.mkv"))
        assertEquals(4 to 5, ReleaseName.episodeTag("The Handmaid's Tale - 4X05 - Chicago.mp4"))
        assertNull("a resolution is no episode", ReleaseName.episodeTag("Film.1920x1080.mkv"))
    }

    @Test fun `DUAL avec le francais - MULTI`() {
        assertEquals(AudioLanguage.MULTI, ReleaseName.parse("Film.2020.1080p.DUAL.TRUEFRENCH.x264.mkv").language)
    }

    @Test fun `resolution ecrite avant UHD, UHD seul vaut 4K`() {
        assertEquals(VideoQuality.FHD_1080, ReleaseName.parse("Film.2020.UHD.BluRay.1080p.x265.mkv").quality)
        assertEquals(VideoQuality.UHD_4K, ReleaseName.parse("Film.2020.MULTi.UHD.BluRay.x265.mkv").quality)
        assertEquals(VideoQuality.UHD_4K, ReleaseName.parse("Dune.2021.MULTi.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.mkv").quality)
        assertEquals(VideoQuality.HD_720, ReleaseName.parse("Film.2020.4K.Upscale.720p.WEB.mkv").quality)
    }

    @Test fun `FRE et FRA comptent comme du francais`() {
        assertEquals(
            AudioLanguage.MULTI,
            ReleaseName.parse("Inception.2010.1080p.BluRay.ENG.LATINO.CASTELLANO.HINDI.ITA.GER.FRE.x265.mkv").language,
        )
        assertEquals(AudioLanguage.VF, ReleaseName.parse("Film.2020.FRA.1080p.WEB.x264.mkv").language)
        // vraies MULTI et VF : inchangées
        assertEquals(AudioLanguage.MULTI, ReleaseName.parse("Inception.2010.MULTi.VFF.2160p.BluRay.HDR10.x265-QTZ.mkv").language)
        assertEquals(AudioLanguage.VF, ReleaseName.parse("Film.2020.TRUEFRENCH.1080p.BluRay.x264.mkv").language)
    }

    @Test fun `French in the title is not mistaken for a French audio tag`() {
        val titleOnly = ReleaseName.parse("The.French.Dispatch.2021.1080p.WEB-DL.x264.mkv")
        assertNull(titleOnly.language)
        assertEquals(emptyList<String>(), titleOnly.audio)
        assertEquals(AudioLanguage.VF, ReleaseName.parse("[FRENCH] Film.2020.1080p.WEB-DL.x264.mkv").language)
        assertEquals(AudioLanguage.VF, ReleaseName.parse("FR| The Amazing World of Gumball").language)
    }

    @Test fun `subtitles in several languages are not several audio tracks`() {
        // Seen for One Piece on the TV (2026-11-09): Japanese and English, played first as if French were in it.
        assertEquals(null, ReleaseName.parse("[AnimeRG] One Piece - 001 [1080p] [Dual-Audio] [Multi-Sub] [x265] [pseudo].mkv").language)
        assertEquals(null, ReleaseName.parse("[ToonsHub] One Piece EP1170 1080p NF WEB-DL AAC2.0 H.264 (Multi-Subs)", "Multi Subs / 🇬🇧 / 🇪🇸").language)
        assertEquals(AudioLanguage.MULTI, ReleaseName.parse("One.Piece.S01E01.MULTi.1080p.WEB.x264.mkv").language)
        assertEquals(AudioLanguage.MULTI, ReleaseName.parse("Film 2020 Multi-Audio 1080p").language)
    }

    @Test fun `French flags on a subtitle line do not announce French audio`() {
        val release = ReleaseName.parse("[AnimeRG] One Piece - 001 [1080p] [Dual-Audio] [Multi-Sub].mkv", "Multi Subs / 🇫🇷 / 🇬🇧")
        assertEquals(AudioLanguage.VOSTFR, release.language)
        assertEquals(false, "FR" in release.audio)
        assertEquals(AudioLanguage.VF, ReleaseName.parse("Film.2020.1080p.mkv", "Dubbed / 🇫🇷").language)
    }
}
