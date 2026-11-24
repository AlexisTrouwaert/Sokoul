package com.sokoul.pc

import com.sokoul.pc.playback.sources.IptvLanguage
import com.sokoul.pc.playback.sources.IptvMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvAnimeTest {
    @Test fun countryPrefixesNameTheDubLanguage() {
        assertEquals("TR", IptvLanguage.fromPrefix("TR| Jujutsu Kaisen"))
        assertEquals("DE", IptvLanguage.fromPrefix("GE| Solo Leveling"))
        assertEquals("FR", IptvLanguage.fromPrefix("FR| Frieren: Beyond Journey's End"))
        assertEquals("EN", IptvLanguage.fromPrefix("4K-EN| Dune"))
        assertNull(IptvLanguage.fromPrefix("Frieren: Beyond Journey's End [MULTI-SUB]"))
        assertNull(IptvLanguage.fromPrefix("One Piece"))
    }

    @Test fun theEnglishTitleFindsAnAnimeTheFrenchTitleMisses() {
        // Frieren is "Frieren" in French on TMDB; the provider files it under its English title.
        assertFalse(IptvMatch.matches("FR| Frieren: Beyond Journey's End", null, 2023, 209867, listOf("Frieren", "葬送のフリーレン"), 2023))
        assertTrue(IptvMatch.matches("FR| Frieren: Beyond Journey's End", null, 2023, 209867, listOf("Frieren", "Frieren: Beyond Journey's End"), 2023))
        // The live-action One Piece (2023) is not the anime (1999).
        assertFalse(IptvMatch.matches("FR| One Piece", null, 2023, 37854, listOf("One Piece"), 1999))
    }
}
