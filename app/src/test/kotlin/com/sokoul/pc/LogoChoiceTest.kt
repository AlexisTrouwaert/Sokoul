package com.sokoul.pc

import com.sokoul.pc.live.IoChannel
import com.sokoul.pc.live.IoLogo
import com.sokoul.pc.live.LogoChoice
import com.sokoul.pc.live.LogoIndex
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Channel logos readable on the dark background (spec pc-live-tv, readable channel logo). */
class LogoChoiceTest {
    private val clear = 0x00000000
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFE50914.toInt()

    /** A [w]×[h] image of [fill], with [color] in the box ([x], [y], [bw], [bh]). */
    private fun image(w: Int, h: Int, fill: Int, color: Int, x: Int, y: Int, bw: Int, bh: Int) = IntArray(w * h) { i ->
        val px = i % w
        val py = i / w
        if (px in x until x + bw && py in y until y + bh) color else fill
    }

    @Test
    fun transparent_margins_are_cut_away() {
        val look = LogoChoice.look(100, 60, image(100, 60, clear, red, 20, 10, 50, 30))
        assertEquals(LogoChoice.Look(20, 10, 50, 30, LogoChoice.Plate.None), look)
    }

    @Test
    fun a_dark_logo_gets_a_light_plate() {
        assertEquals(LogoChoice.Plate.Light, LogoChoice.look(40, 20, image(40, 20, clear, black, 5, 5, 30, 10)).plate)
    }

    @Test
    fun a_navy_logo_gets_a_light_plate_too() {
        assertEquals(LogoChoice.Plate.Light, LogoChoice.look(40, 20, image(40, 20, clear, 0xFF1E2A5A.toInt(), 5, 5, 30, 10)).plate)
    }

    @Test
    fun a_light_or_coloured_logo_needs_none() {
        assertEquals(LogoChoice.Plate.None, LogoChoice.look(40, 20, image(40, 20, clear, white, 5, 5, 30, 10)).plate)
        assertEquals(LogoChoice.Plate.None, LogoChoice.look(40, 20, image(40, 20, clear, red, 5, 5, 30, 10)).plate)
    }

    @Test
    fun an_opaque_logo_keeps_its_whole_box() {
        val look = LogoChoice.look(30, 20, IntArray(30 * 20) { white })
        assertEquals(LogoChoice.Look(0, 0, 30, 20, LogoChoice.Plate.None), look)
    }

    @Test
    fun an_empty_image_is_left_as_it_is() {
        assertEquals(LogoChoice.Look(0, 0, 10, 10, LogoChoice.Plate.None), LogoChoice.look(10, 10, IntArray(100) { clear }))
    }

    @Test
    fun the_sharpest_of_two_logos_wins() {
        assertEquals(1, LogoChoice.sharpest(listOf(100, 400)))
        assertEquals(0, LogoChoice.sharpest(listOf(400, 100)))
        assertEquals(0, LogoChoice.sharpest(listOf(200, 200)))
        assertEquals(1, LogoChoice.sharpest(listOf(null, 120)))
        assertNull(LogoChoice.sharpest(listOf(null, null)))
    }

    @Test
    fun initials_stand_in_for_a_missing_logo() {
        assertEquals("TF1", LogoChoice.initials("FR| TF1 FHD"))
        assertEquals("F2", LogoChoice.initials("FR| FRANCE 2 FHD"))
        assertEquals("BFM", LogoChoice.initials("BFMTV"))
        assertEquals("?", LogoChoice.initials("HD"))
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun a_channel_without_any_logo_shows_its_initials() = androidx.compose.ui.test.runComposeUiTest {
        com.sokoul.pc.live.ChannelLogos.index = { emptyMap() }
        setContent {
            com.sokoul.pc.live.ChannelLogo("FR| FRANCE 2 FHD", null, androidx.compose.ui.Modifier.size(120.dp, 70.dp))
        }
        waitForIdle()
        onNodeWithTag("channel-logo-initials").assertExists()
        onNodeWithTag("channel-logo").assertDoesNotExist()
    }

    @Test
    fun only_web_logos_are_loaded() {
        val logos = com.sokoul.pc.live.ChannelLogos
        assertEquals(true, logos.allowed("https://logo.test/a.png"))
        assertEquals(true, logos.allowed("http://logo.test/a.png"))
        assertEquals(false, logos.allowed("file:///C:/Users/x/secret.png"))
        assertEquals(false, logos.allowed("file:////host/share/x.png"))
        assertEquals(false, logos.allowed("C:\\logo.png"))
    }

    @Test
    fun the_index_finds_a_logo_by_guide_id_then_by_name_and_prefers_france() {
        val channels = listOf(
            IoChannel("TF1.fr", "TF1", country = "FR"),
            IoChannel("TF1.be", "TF1", country = "BE"),
            IoChannel("Closed.fr", "Old", country = "FR", closed = "2020-01-01"),
        )
        val logos = listOf(
            IoLogo("TF1.fr", url = "https://logo.test/tf1-small.png", width = 100),
            IoLogo("TF1.fr", url = "https://logo.test/tf1-big.png", width = 400),
            IoLogo("TF1.be", url = "https://logo.test/tf1-be.png", width = 800),
            IoLogo("Closed.fr", url = "https://logo.test/old.png"),
            IoLogo("TF1.fr", url = "https://logo.test/tf1.svg", width = 2000, format = "SVG"),
        )
        val index = LogoIndex.buildIndex(channels, logos)
        assertEquals("https://logo.test/tf1-big.png", LogoIndex.lookup(index, "tf1.fr", "tf1"))
        assertEquals("https://logo.test/tf1-be.png", LogoIndex.lookup(index, "tf1.be", "x"))
        // by name, the French channel wins over the Belgian one
        assertEquals("https://logo.test/tf1-big.png", LogoIndex.lookup(index, null, "tf1"))
        assertNull(LogoIndex.lookup(index, null, "old"))
    }
}
