package com.sokoul.pc

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.sokoul.desktop.data.AccentColor
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.desktop.data.SubtitleBackground
import com.sokoul.desktop.data.SubtitleColor
import com.sokoul.desktop.data.SubtitleFont
import com.sokoul.desktop.data.SubtitleSize
import com.sokoul.pc.data.HomeLayout
import com.sokoul.pc.data.PageKind
import com.sokoul.pc.data.PagePlans
import com.sokoul.pc.library.HomeRowsScreen
import com.sokoul.pc.library.ServiceInfo
import com.sokoul.pc.library.SettingsHooks
import com.sokoul.pc.library.SettingsScreen
import com.sokoul.pc.theme.AccentTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The settings page used as a person would: pick a category, click a row, and the choice is kept. */
class SettingsTest {
    private lateinit var file: File
    private lateinit var db: DesktopDb
    private lateinit var prefs: DesktopPrefs
    private var changes = 0
    private var cleared = 0
    private var erased = 0
    private var reset = 0
    private val menu = listOf("home" to "Home", "library" to "My list", "movies" to "Movies")

    private fun hooks() = SettingsHooks(
        menu, { changes++ },
        services = { listOf(ServiceInfo("TMDB", true, "Built-in key"), ServiceInfo("OMDb", false, null)) },
        onClearCache = { cleared++ }, onClearHistory = { erased++ }, onResetDisplay = { reset++ },
    )

    @Before
    fun open() {
        file = File.createTempFile("settings", ".db")
        db = DesktopDb(file)
        prefs = DesktopPrefs(db)
        changes = 0
    }

    @After
    fun close() {
        AccentTheme.color = androidx.compose.ui.graphics.Color.White
        db.close()
        file.delete()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.page() {
        setContent { com.sokoul.pc.ui.ScaledRoot { SettingsScreen(FocusRequester(), prefs, onCredentials = {}, hooks = hooks()) } }
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pane(id: String) {
        onNodeWithTag("settings-pane-$id").performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun accountsShowEveryServiceAndTheWayToChangeThem() = runComposeUiTest {
        page()
        onNodeWithTag("service-TMDB").assertExists()
        onNodeWithTag("service-OMDb").assertExists()
        onNodeWithTag("settings-credentials").assertExists()
        onNodeWithTag("settings-title").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun accentIsPickedFromSwatchesAndKept() = runComposeUiTest {
        page()
        pane("appearance")
        click("accent-red")
        assertEquals(AccentColor.RED, prefs.accent)
        assertEquals(AccentColor.RED.argb.toInt(), AccentTheme.color.toArgb())
        assertTrue(changes > 0)
        click("accent-blue")
        assertEquals(AccentColor.BLUE, prefs.accent)
        assertEquals(AccentColor.BLUE.argb.toInt(), AccentTheme.color.toArgb())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun subtitleStyleIsChosenAndReset() = runComposeUiTest {
        page()
        pane("subtitles")
        click("pref-sub-size")
        click("pref-sub-color")
        click("pref-sub-background")
        click("pref-sub-font")
        assertEquals(SubtitleSize.LARGE, prefs.subtitleSize)
        assertEquals(SubtitleColor.YELLOW, prefs.subtitleColor)
        assertEquals(SubtitleBackground.BOX, prefs.subtitleBackground)
        assertEquals(SubtitleFont.ATKINSON, prefs.subtitleFont)

        click("pref-sub-reset")
        assertEquals(SubtitleSize.NORMAL, prefs.subtitleSize)
        assertEquals(SubtitleColor.WHITE, prefs.subtitleColor)
        assertEquals(SubtitleBackground.OUTLINE, prefs.subtitleBackground)
        assertEquals(SubtitleFont.STANDARD, prefs.subtitleFont)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun menuEntriesCanBeHiddenButNotAll() = runComposeUiTest {
        page()
        pane("menu")
        click("pref-menu-library")
        click("pref-menu-movies")
        assertEquals(setOf("library", "movies"), prefs.railHidden)
        // The last one stays, whatever is clicked.
        click("pref-menu-home")
        assertEquals(setOf("library", "movies"), prefs.railHidden)
        // And a hidden one comes back.
        click("pref-menu-library")
        assertFalse("library" in prefs.railHidden)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun maintenanceActionsAskTwiceBeforeDoingAnything() = runComposeUiTest {
        page()
        pane("about")
        // The test clock would run the four seconds the confirmation waits for while a click settles: it is held still.
        mainClock.autoAdvance = false
        fun press(tag: String) {
            onNodeWithTag(tag).performScrollTo().performClick()
            mainClock.advanceTimeBy(300)
        }
        press("about-clear-history")
        assertEquals("one press only arms the action", 0, erased)
        press("about-clear-history")
        assertEquals(1, erased)
        press("about-clear-cache")
        press("about-clear-cache")
        assertEquals(1, cleared)
        press("about-reset-display")
        assertEquals(0, reset)
        press("about-reset-display")
        assertEquals(1, reset)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theHomeRowsAreHiddenMovedAndRestored() = runComposeUiTest {
        val plan = PagePlans.of(PageKind.Home)
        val ids = plan.map { it.id }
        setContent { com.sokoul.pc.ui.ScaledRoot { HomeRowsScreen(prefs, onChanged = { changes++ }, onBack = {}) } }
        waitForIdle()

        click("home-row-${ids[0]}")
        assertEquals(setOf(ids[0]), HomeLayout.decode(prefs.homeRowsJson).hidden)

        click("home-row-down-${ids[1]}")
        val shown = HomeLayout.decode(prefs.homeRowsJson).apply(plan).map { it.id }
        assertEquals(listOf(ids[2], ids[1]), shown.take(2))

        click("home-rows-reset")
        assertTrue(HomeLayout.decode(prefs.homeRowsJson).isDefault)
        assertTrue(changes >= 3)
    }

    @Test
    fun resetDisplayGivesBackTheFirstLook() {
        prefs.accent = AccentColor.PINK
        prefs.railHidden = setOf("tv")
        prefs.homeRowsJson = HomeLayout().toggle("moods").encode()
        prefs.subtitleSize = SubtitleSize.HUGE
        prefs.screensaverMinutes = 30
        prefs.resetDisplay()
        assertEquals(AccentColor.WHITE, prefs.accent)
        assertTrue(prefs.railHidden.isEmpty())
        assertEquals(null, prefs.homeRowsJson)
        assertEquals(SubtitleSize.NORMAL, prefs.subtitleSize)
        assertEquals(5, prefs.screensaverMinutes)
    }

    @Test
    fun theLanguageChoiceAndPromptAreKept() {
        assertEquals("auto", prefs.language)
        prefs.language = "fr"
        assertEquals("fr", prefs.language)
        prefs.language = "auto"
        assertEquals("auto", prefs.language)
        assertTrue(prefs.profilePrompt)
        prefs.profilePrompt = false
        assertFalse(DesktopPrefs(db).profilePrompt)
    }
}
