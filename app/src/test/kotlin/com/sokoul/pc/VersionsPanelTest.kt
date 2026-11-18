package com.sokoul.pc

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.sokoul.desktop.data.PlaybackPrefs
import com.sokoul.desktop.data.StartMode
import com.sokoul.desktop.player.StreamRequest
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
    fun yourLanguagesFilterKeepsOnlyWantedVersions() = runComposeUiTest {
        com.sokoul.pc.i18n.Strings.applyLanguage("en")
        val versions = listOf("a", "b", "c").mapIndexed { index, key ->
            PlayVersion(key, VersionOrigin.Debrid, "name $index", readiness = Readiness.Ready) { _, _ ->
                StreamRequest("u$index", null, null, "t", live = false)
            }
        }
        setContent {
            com.sokoul.pc.playback.ui.SourceBrowserContent(
                versions, null, false, wanted = { it.key == "a" },
            )
        }
        waitForIdle()
        assertEquals(3, rows())
        onNodeWithTag("versions-language-wanted").performClick()
        waitForIdle()
        assertEquals(1, rows())
        onNodeWithText("name 0", substring = true).assertExists()
        onNodeWithText("name 1", substring = true).assertDoesNotExist()
        onNodeWithText("name 2", substring = true).assertDoesNotExist()
        onNodeWithTag("versions-language-wanted").performClick()
        waitForIdle()
        assertEquals(3, rows())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun lazyVersionsStayBoundedAndAccountReadyCombinesWithProviderFilter() = runComposeUiTest {
        com.sokoul.pc.i18n.Strings.applyLanguage("en")
        // 300 versions, only every twentieth one ready: 15 of them.
        val all = (0 until 300).map { version(it, if (it % 20 == 0) Readiness.Ready else Readiness.Unknown) } +
            (0 until 2).map { version(1000 + it, Readiness.Unknown, VersionOrigin.Iptv) }
        val session = session(all)
        setContent { VersionsPanel(session, onPicked = {}) }
        waitForIdle()

        // Lazy rows stay bounded while the full candidate count remains available.
        org.junit.Assert.assertTrue(rows() in 1..24)
        onNodeWithTag("versions-more").assertDoesNotExist()
        onNodeWithTag("versions-filter-ready").performClick()
        waitForIdle()
        onNodeWithText("15 of 302 versions", substring = true).assertExists()
        // The chips scroll sideways: "Reset filters" has appeared, so IPTV may now sit past the panel's edge.
        onNodeWithTag("versions-filter-iptv").performScrollTo().performClick()
        waitForIdle()
        assertEquals(rowTags().toString(), 0, rows())
        onNodeWithTag("versions-filter-ready").performScrollTo().performClick()
        waitForIdle()
        assertEquals(2, rows())
        session.close()
    }
}
