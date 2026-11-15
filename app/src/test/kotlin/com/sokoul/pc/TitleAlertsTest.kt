package com.sokoul.pc

import com.sokoul.iptv.data.net.TmdbEpisode
import com.sokoul.pc.data.AlertNews
import com.sokoul.pc.data.AlertRules
import com.sokoul.pc.data.TitleAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** When a followed title is worth a notice, and when it is not (said already, not out yet, or older than told). */
class TitleAlertsTest {
    private val today = LocalDate.of(2026, 11, 9)
    private val film = TitleAlert("movie", 1, "Film")
    private val show = TitleAlert("tv", 2, "Show")

    @Test
    fun filmIsAnnouncedOnceItIsOut() {
        assertNull(AlertRules.movie(film, "2026-12-01", today))
        assertEquals(AlertNews.RELEASED, AlertRules.movie(film, "2026-11-09", today)?.key)
        assertNull(AlertRules.movie(film.copy(told = AlertNews.RELEASED), "2026-11-01", today))
        assertNull(AlertRules.movie(film, null, today))
    }

    @Test
    fun episodeIsAnnouncedOnlyWhenNewer() {
        assertEquals("S2E5", AlertRules.series(show, 2 to 5)?.key)
        assertNull(AlertRules.series(show.copy(told = "S2E5"), 2 to 5))
        assertEquals("S2E6", AlertRules.series(show.copy(told = "S2E5"), 2 to 6)?.key)
        assertEquals("S3E1", AlertRules.series(show.copy(told = "S2E10"), 3 to 1)?.key)
        // A catalog that briefly knows less never brings an old episode back.
        assertNull(AlertRules.series(show.copy(told = "S3E1"), 2 to 10))
        assertNull(AlertRules.series(show, null))
    }

    @Test
    fun latestAiredIgnoresFutureAndUndatedEpisodes() {
        val episodes = listOf(
            TmdbEpisode(2, 4, null, null, null, null, "2026-11-01"),
            TmdbEpisode(2, 5, null, null, null, null, "2026-11-09"),
            TmdbEpisode(2, 6, null, null, null, null, "2026-11-16"),
            TmdbEpisode(3, 1, null, null, null, null, null),
        )
        assertEquals(2 to 5, AlertRules.latestAired(episodes, today))
        assertNull(AlertRules.latestAired(episodes.drop(2), today))
    }
}
