package com.sokoul.pc

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.pc.data.CredentialValidator
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.title.TitleScreen
import com.sokoul.pc.ui.ScaledRoot
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class TitleIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun title_play_distinguishes_a_movie_and_series_with_the_same_id() {
        DesktopDb(temp.newFile("title-identity.db")).use { db ->
            val id = 12
            val backdrop = temp.newFile("backdrop.png").also {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
            }.toURI().toString()
            val titles = listOf(sampleItem(id, "movie").copy(title = "Same Film"),
                sampleItem(id, "tv").copy(title = "Same Series", backdrop = backdrop))
            val requests = mutableListOf<PlayRequest>()
            val deps = testDeps(db, CredentialValidator(OkHttpClient()), FakeCatalog())
            runComposeUiTest {
                setContent {
                    ScaledRoot {
                        TitleScreen(deps, titles, 0, null, onClose = {},
                            onPlay = { requests += it }, onPlayTrailer = { _, _ -> },
                            onOpenSimilar = { _, _, _ -> }, onOpenPerson = { _, _ -> }, onOpenSaga = { _, _ -> })
                    }
                }
                onNodeWithTag("title-play").performClick()
                onNodeWithTag("neighbor-right").performSemanticsAction(SemanticsActions.OnClick) { it() }
                onNodeWithTag("neighbor-right").assertDoesNotExist()
                onNodeWithTag("title-play").performClick()
                assertEquals(listOf("Same Film", "Same Series"), requests.map { it.title })
                assertEquals(listOf("movie", "tv"), requests.map { it.kind })
                assertEquals(listOf(null, 1), requests.map { it.episode })
            }
        }
    }
}
