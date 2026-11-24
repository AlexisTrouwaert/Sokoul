package com.sokoul.pc

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.sokoul.desktop.data.PlaybackPrefs
import com.sokoul.desktop.data.StartMode
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.iptv.domain.VideoQuality
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.pc.playback.session.PlaybackSession
import com.sokoul.pc.playback.session.Readiness
import com.sokoul.pc.playback.session.VersionOrigin
import com.sokoul.pc.playback.ui.VersionsPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The versions panel keeps composition bounded for hundreds of candidates and intersects provider/account filters.
 */
class VersionsPanelTest {
    private fun version(i: Int, readiness: Readiness, origin: VersionOrigin = VersionOrigin.Debrid) =
        PlayVersion("k$i", origin, "name $i", readiness = readiness) { _, _ -> StreamRequest("u$i", null, null, "t", live = false) }

    private fun session(versions: List<PlayVersion>) = PlaybackSession(
        FakeEngine(), finder(*versions.toTypedArray()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        prefs = { PlaybackPrefs(startMode = StartMode.Ask) }, frenchSystem = { true },
    ).also { it.play(PlayRequest("movie", 1, null, null, "Title", null, null, chooseFirst = true)) }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.rowTags() =
        onAllNodes(SemanticsMatcher("a version row") { it.config.getOrNull(SemanticsProperties.TestTag).let { tag -> tag != null && tag.startsWith("version-") && tag.last().isDigit() } })
            .fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) to it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.ComposeUiTest.rows() = rowTags().size

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun opensOnYourLanguagesAndAllShowsEveryVersion() = runComposeUiTest {
        com.sokoul.pc.i18n.Strings.applyLanguage("en")
        val versions = listOf("a", "b", "c").mapIndexed { index, key ->
            PlayVersion(key, VersionOrigin.Debrid, "name $index", readiness = Readiness.Ready) { _, _ ->
                StreamRequest("u$index", null, null, "t", live = false)
            }
        }
        setContent { com.sokoul.pc.playback.ui.SourceBrowserContent(versions, null, false, wanted = { it.key == "a" }) }
        waitForIdle()
        // "For you" first: only the version in the wanted languages, the others counted in the footer.
        assertEquals(1, rows())
        onNodeWithText("2 more outside your languages", substring = true).assertExists()
        onNodeWithTag("versions-language-all").performClick()
        waitForIdle()
        assertEquals(3, rows())
        onNodeWithTag("versions-language-foryou").performClick()
        waitForIdle()
        assertEquals(1, rows())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun nothingInYourLanguagesOpensOnAll() = runComposeUiTest {
        com.sokoul.pc.i18n.Strings.applyLanguage("en")
        val versions = (0 until 3).map { version(it, Readiness.Ready) }
        setContent { com.sokoul.pc.playback.ui.SourceBrowserContent(versions, null, false, wanted = { false }) }
        waitForIdle()
        assertEquals(3, rows())
        onNodeWithTag("versions-language-foryou").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shows4KAvailabilityAcrossLanguages() = runComposeUiTest {
        com.sokoul.pc.i18n.Strings.applyLanguage("en")
        val french = PlayVersion("hd", VersionOrigin.Web, "French edition",
            language = AudioLanguage.VF, quality = VideoQuality.FHD_1080) { _, _ -> StreamRequest("hd", null, null, "t", live = false) }
        val original = PlayVersion("uhd", VersionOrigin.Debrid, "Original UHD edition",
            language = AudioLanguage.VO, quality = VideoQuality.UHD_4K) { _, _ -> StreamRequest("uhd", null, null, "t", live = false) }
        setContent { com.sokoul.pc.playback.ui.SourceBrowserContent(listOf(french, original), null, false, wanted = { it.key == "hd" }) }
        waitForIdle()
        assertEquals(1, rows())
        onNodeWithText("4K · 1").assertExists()
        onNodeWithTag("versions-quality-4k").performClick()
        waitForIdle()
        assertEquals(1, rows())
        onNodeWithTag("version-0-audio", useUnmergedTree = true).assertTextEquals("Original version")
        onNodeWithTag("versions-origin-web").performClick()
        onNodeWithText("No 4K version from these sources").assertExists()
        onNodeWithTag("versions-origin-debrid").performClick()
        assertEquals(1, rows())
        onNodeWithTag("versions-origin-all").performClick()
        onNodeWithTag("versions-language-all").performClick()
        waitForIdle()
        assertEquals(2, rows())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun lazyVersionsStayBounded() = runComposeUiTest {
        com.sokoul.pc.i18n.Strings.applyLanguage("en")
        val all = (0 until 300).map { version(it, if (it % 20 == 0) Readiness.Ready else Readiness.Unknown) } +
            (0 until 2).map { version(1000 + it, Readiness.Unknown, VersionOrigin.Iptv) }
        val session = session(all)
        try {
            // Populate the large result set before measuring the lazily composed rows.
            waitUntil(timeoutMillis = 8_000) { session.versions.size == all.size }
            setContent { VersionsPanel(session, onPicked = {}) }
            waitForIdle()
            org.junit.Assert.assertTrue(rowTags().toString(), rows() in 1..24)
            onNodeWithText("302 versions", substring = true).assertExists()
        } finally {
            session.close()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theFooterOpensTheExcludedVersionsAndComesBack() = runComposeUiTest {
        com.sokoul.pc.i18n.Strings.applyLanguage("en")
        val good = (0 until 2).map { version(it, Readiness.Ready) }
        val wrong = PlayVersion("bad", VersionOrigin.Debrid, "bad", readiness = Readiness.Ready, rejected = com.sokoul.pc.playback.session.Doubt.WrongLength) { _, _ -> StreamRequest("x", null, null, "t", live = false) }
        setContent { com.sokoul.pc.playback.ui.SourceBrowserContent(good + wrong, null, false) }
        waitForIdle()
        assertEquals(2, rows())
        onNodeWithText("1 excluded", substring = true).assertExists()
        onNodeWithTag("versions-more").performClick()
        waitForIdle()
        assertEquals(1, rows())
        onNodeWithTag("versions-more").performClick()
        waitForIdle()
        assertEquals(2, rows())
    }
}
