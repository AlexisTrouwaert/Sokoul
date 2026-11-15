package com.sokoul.pc

import com.sokoul.pc.playback.ui.repairText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Track names that libVLC hands over as UTF-8 read as Latin-1 are repaired, and clean ones are left alone. */
class RepairTextTest {
    @Test
    fun repairsMisreadAccents() {
        val misread = String("Français 5.1 EAC3".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("FranÃ§ais 5.1 EAC3", misread)
        assertEquals("Français 5.1 EAC3", repairText(misread))
        val deutsch = String("Deutsch – Größe".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("Deutsch – Größe", repairText(deutsch))
    }

    @Test
    fun leavesCleanTextAlone() {
        assertEquals("Français", repairText("Français"))
        assertEquals("English 5.1", repairText("English 5.1"))
        // A real "Ã" that does not read back as UTF-8 stays as it is.
        assertEquals("Ã", repairText("Ã"))
    }
}
