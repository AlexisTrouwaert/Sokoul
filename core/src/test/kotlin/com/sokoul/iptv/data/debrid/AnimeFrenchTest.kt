package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.domain.AudioLanguage
import org.junit.Test
import org.junit.Assert.*

class AnimeFrenchTest {
    @Test fun frenchAudioSurvivesSubtitleLabels() {
        assertEquals(AudioLanguage.VF, ReleaseName.parse("Dragon Ball Super E001 FRENCH 720p").language)
        assertEquals(AudioLanguage.VF, ReleaseName.parse("Naruto Shippuden 001 FRENCH 720p").language)
        assertEquals(AudioLanguage.VF, ReleaseName.parse("Naruto Shippuden 01 FRENCH 720p").language)
        assertEquals(AudioLanguage.MULTI, ReleaseName.parse("MULTi VF VOSTFR").language)
        assertEquals(AudioLanguage.MULTI, ReleaseName.parse("VOSTFR-VF").language)
        assertEquals(AudioLanguage.VF, ReleaseName.parse("1100 VF Multi-Sub").language)
        assertEquals(AudioLanguage.VF, ReleaseName.parse("1100 [Multi-Sub] VF").language)
        assertFalse(DebridStreams.otherWork("Naruto E1 VF 1080p", listOf("Naruto"), true))
        assertFalse(DebridStreams.otherWork("One Piece 1100 VF 1080p", listOf("One Piece"), true))
        assertFalse(DebridStreams.otherWork("Naruto Shippuden 001 VF", listOf("Naruto Shippuden"), true))
    }
    @Test fun frenchEpisodePairsAreNotAbsoluteNumbers() {
        assertEquals(true, DebridStreams.episodeMatches("Saison 2 Episode 5", 2, 5))
        assertEquals(true, DebridStreams.episodeMatches("Saison 2 - 5", 2, 5))
        assertEquals(false, DebridStreams.episodeMatches("Saison 2 Episode 6", 2, 5))
    }
    @Test fun frenchAnimeDoesNotAcquirePlatformDoubt() {
        val verdict = AnimeReleaseRules.verdict("One.Piece.S22E05.MULTi", null, AnimeCheck(listOf("One Piece"), 1999, 22, 5, null))
        assertEquals(Verdict.Confirmed, verdict)
    }
}
