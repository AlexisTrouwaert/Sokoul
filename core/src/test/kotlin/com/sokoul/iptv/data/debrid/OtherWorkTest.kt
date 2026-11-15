package com.sokoul.iptv.data.debrid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A release is dropped as another work only when its name adds words to the title, never for an episode number. */
class OtherWorkTest {
    private val onePiece = listOf("One Piece")

    @Test fun `an anime file numbered after the title is the same series`() {
        assertFalse(DebridStreams.otherWork("[SubsPlease] One Piece - 001 (1080p).mkv", onePiece, series = true))
        assertFalse(DebridStreams.otherWork("[Erai-raws] One Piece - 1071 [1080p][Multiple Subtitle].mkv", onePiece, series = true))
        assertFalse(DebridStreams.otherWork("One.Piece.S01E01.1080p.WEB.mkv", onePiece, series = true))
    }

    @Test fun `added words still mean another work`() {
        assertTrue(DebridStreams.otherWork("One Piece Film Red 2022 1080p.mkv", onePiece, series = true))
        assertTrue(DebridStreams.otherWork("One Piece Fan Letter 1080p.mkv", onePiece, series = true))
        // A film's number is a sequel.
        assertTrue(DebridStreams.otherWork("Dune 2 1080p.mkv", listOf("Dune"), series = false))
    }
}
