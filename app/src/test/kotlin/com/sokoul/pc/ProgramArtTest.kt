package com.sokoul.pc

import com.sokoul.desktop.data.Program
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.live.ProgramArt
import com.sokoul.pc.live.ProgramArtCache
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A programme's picture never shows another work (spec pc-live-tv, programme picture). */
class ProgramArtTest {
    private fun item(id: Int, title: String, original: String? = null, backdrop: String? = "https://img.test/$id.jpg", type: String = "movie") =
        TmdbItem(id, title, original, null, null, backdrop, null, null, mediaType = type)

    @Test
    fun one_film_of_that_title_gives_its_picture() {
        val found = ProgramArt.pick("Le Fabuleux Destin d'Amélie Poulain", listOf(item(194, "Le Fabuleux Destin d'Amélie Poulain"), item(9, "Amélie et moi")))
        assertEquals(194, found?.id)
    }

    @Test
    fun the_original_title_counts_too() {
        assertEquals(1, ProgramArt.pick("Inception", listOf(item(1, "Origine", original = "Inception")))?.id)
    }

    @Test
    fun generic_titles_never_get_a_picture() {
        for (t in listOf("Journal", "Météo", "JT", "Téléachat", "Info")) {
            assertNull(t, ProgramArt.pick(t, listOf(item(1, t))))
        }
    }

    @Test
    fun two_works_of_that_title_give_none() {
        assertNull(ProgramArt.pick("Dune", listOf(item(1, "Dune"), item(2, "Dune", type = "tv"))))
    }

    @Test
    fun no_matching_work_or_no_picture_gives_none() {
        assertNull(ProgramArt.pick("Les Feux de l'amour", listOf(item(1, "Feux"))))
        assertNull(ProgramArt.pick("Les Feux de l'amour", listOf(item(1, "Les Feux de l'amour", backdrop = null))))
    }

    @Test
    fun the_guide_picture_comes_first_and_searches_are_kept_per_title() = runBlocking {
        var searches = 0
        val cache = ProgramArtCache { searches++; listOf(item(5, "Titanic")) }
        assertEquals("https://guide.test/a.jpg", cache.imageOf(Program("c", 0, 1, "Titanic", null, "https://guide.test/a.jpg")))
        assertEquals(0, searches)
        assertEquals("https://img.test/5.jpg", cache.imageOf(Program("c", 0, 1, "Titanic", null)))
        assertEquals("https://img.test/5.jpg", cache.imageOf(Program("d", 5, 6, "TITANIC", null)))
        assertEquals(1, searches)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun the_box_shows_the_picture_else_the_logo() = androidx.compose.ui.test.runComposeUiTest {
        var program by androidx.compose.runtime.mutableStateOf<Program?>(Program("c", 0, 1, "Journal", null, "https://guide.test/j.jpg"))
        var fails = false
        val cache = ProgramArtCache { if (fails) error("offline") else emptyList() }
        com.sokoul.pc.live.ChannelLogos.index = { null }
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.sokoul.pc.live.LocalProgramArt provides cache) {
                com.sokoul.pc.live.ProgramPicture(program, "Test", "https://logo.test/c.png", androidx.compose.ui.Modifier.size(320.dp, 180.dp))
            }
        }
        // The guide's picture
        onNodeWithTag("program-picture").assertExists()
        // No picture anywhere: the logo
        program = Program("c", 0, 1, "Une émission locale", null)
        waitForIdle()
        onNodeWithTag("program-picture").assertDoesNotExist()
        onNodeWithTag("program-picture-logo").assertExists()
        // The search fails: the logo still
        fails = true
        program = Program("c", 2, 3, "Un autre titre", null)
        waitForIdle()
        onNodeWithTag("program-picture").assertDoesNotExist()
        onNodeWithTag("program-picture-logo").assertExists()
    }

    @Test
    fun a_tmdb_picture_is_asked_at_780_pixels() = runBlocking {
        val cache = ProgramArtCache { listOf(item(3, "Titanic", backdrop = "https://image.tmdb.org/t/p/w1280/abc.jpg")) }
        assertEquals("https://image.tmdb.org/t/p/w780/abc.jpg", cache.imageOf(Program("c", 0, 1, "Titanic", null)))
    }

    @Test
    fun a_failed_search_is_asked_again() = runBlocking {
        var calls = 0
        val cache = ProgramArtCache { calls++; if (calls == 1) error("offline") else listOf(item(7, "Gladiator")) }
        assertNull(cache.imageOf(Program("c", 0, 1, "Gladiator", null)))
        assertEquals("https://img.test/7.jpg", cache.imageOf(Program("c", 0, 1, "Gladiator", null)))
    }
}
