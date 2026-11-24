package com.sokoul.pc

import com.sokoul.pc.library.settings.SettingsCategory
import com.sokoul.pc.library.settings.SettingsFamily
import com.sokoul.pc.library.settings.SettingsNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** openspec/changes/settings-redesign/specs/settings-navigation: families, stable ids, one stack, breadcrumbs. */
class SettingsModelTest {
    @Test
    fun `eight categories in two families, in reading order`() {
        assertEquals(8, SettingsCategory.entries.size)
        assertEquals(listOf("profile", "playback", "languages", "display"), SettingsCategory.entries.filter { it.family == SettingsFamily.Profile }.map { it.id })
        assertEquals(listOf("sources", "livetv", "language", "tools"), SettingsCategory.entries.filter { it.family == SettingsFamily.Device }.map { it.id })
        assertEquals(SettingsCategory.entries.size, SettingsCategory.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `former pane ids still open the right category`() {
        assertEquals(SettingsCategory.PROFILE, SettingsCategory.of("accounts"))
        assertEquals(SettingsCategory.LANGUAGES, SettingsCategory.of("subtitles"))
        assertEquals(SettingsCategory.DISPLAY, SettingsCategory.of("home"))
        assertEquals(SettingsCategory.DISPLAY, SettingsCategory.of("appearance"))
        assertEquals(SettingsCategory.DISPLAY, SettingsCategory.of("menu"))
        assertEquals(SettingsCategory.TOOLS, SettingsCategory.of("about"))
        assertEquals(SettingsCategory.INTERFACE_LANGUAGE, SettingsCategory.of("language"))
        assertEquals(SettingsCategory.PROFILE, SettingsCategory.of("nonsense"))
    }

    @Test
    fun `back pops one level then leaves at the root`() {
        val nav = SettingsNavigation(SettingsCategory.LANGUAGES)
        nav.open("audio-languages")
        assertEquals("audio-languages", nav.current.subPage)
        val crumb = nav.breadcrumb { "Langues audio" }
        assertEquals(3, crumb.size)
        assertEquals(SettingsCategory.LANGUAGES.label.text(), crumb[1])
        assertEquals("Langues audio", crumb[2])
        assertTrue(nav.back())
        assertNull(nav.current.subPage)
        assertEquals(SettingsCategory.LANGUAGES, nav.current.category)
        assertFalse(nav.back())
    }

    @Test
    fun `choosing a category starts the stack again and unavailable ones are ignored`() {
        val nav = SettingsNavigation(SettingsCategory.PLAYBACK, SettingsCategory.entries.filter { it != SettingsCategory.LIVE_TV })
        nav.open("quality")
        nav.select(SettingsCategory.DISPLAY)
        assertEquals(1, nav.depth)
        nav.select(SettingsCategory.LIVE_TV)
        assertEquals(SettingsCategory.DISPLAY, nav.current.category)
    }

    @Test
    fun `each page remembers its last focused row`() {
        val nav = SettingsNavigation(SettingsCategory.PLAYBACK)
        nav.remember("max-quality")
        nav.select(SettingsCategory.DISPLAY)
        assertNull(nav.lastRow())
        nav.select(SettingsCategory.PLAYBACK)
        assertEquals("max-quality", nav.lastRow())
    }
}
