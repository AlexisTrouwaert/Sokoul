package com.sokoul.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioChoiceOriginalTest {
    @Test
    fun `MULTI with French first picks the original track`() {
        val tracks = listOf(1 to "Français - [French]", 2 to "English - [English]")
        assertEquals(2, AudioChoice.pickOriginal(tracks))
    }

    @Test
    fun `original track in another language than English`() {
        val tracks = listOf(3 to "Track 1 - [VFF]", 4 to "Track 2 - [Japanese]")
        assertEquals(4, AudioChoice.pickOriginal(tracks))
    }

    @Test
    fun `French only keeps libVLC's choice`() {
        assertNull(AudioChoice.pickOriginal(listOf(1 to "Français - [French]")))
    }

    @Test
    fun `no French track keeps libVLC's choice`() {
        assertNull(AudioChoice.pickOriginal(listOf(1 to "English - [English]", 2 to "Spanish - [Spanish]")))
    }
}
