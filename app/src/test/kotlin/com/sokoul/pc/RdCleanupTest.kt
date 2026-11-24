package com.sokoul.pc

import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Resume
import com.sokoul.iptv.data.debrid.RdTorrent
import com.sokoul.pc.playback.sources.RdCleanup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RdCleanupTest {
    private val day = 24 * 3_600_000L
    private val now = 400 * day
    private fun t(status: String = "downloaded", progress: Int = 100, age: Long = 2 * day, name: String = "Film.2024.MULTi.2160p.mkv") =
        RdTorrent("id", name, "h", status, progress, 0, emptyList(), emptyList(), now - age)
    private fun watched(finished: Boolean, daysAgo: Long) = Resume("k", "movie", 1, null, null, "Film", null, null, 0, 1, now - daysAgo * day, finished)
    private fun played(daysAgo: Long) = DesktopDb.RdKnown(now - 100 * day, "k", now - daysAgo * day)
    private fun cut(t: RdTorrent, known: DesktopDb.RdKnown?, w: Resume?) = RdCleanup.useless(t, known, w, now)

    @Test fun brokenOrNeverStartedGoes() {
        assertTrue(cut(t("error", 0), null, null))
        assertTrue(cut(t("magnet_error", 0), null, null))
        assertTrue(cut(t("downloading", 0), null, null))
        assertFalse(cut(t("downloading", 70), null, null))
        assertFalse(cut(t("downloading", 0, 3_600_000L), null, null))
    }

    @Test fun aFinishedFilmGoesAWeekLaterAndOneInProgressStays() {
        assertFalse(cut(t(), played(3), watched(finished = true, daysAgo = 3)))
        assertTrue(cut(t(), played(8), watched(finished = true, daysAgo = 8)))
        assertFalse(cut(t(), played(40), watched(finished = false, daysAgo = 40)))
        assertTrue(cut(t(), played(100), watched(finished = false, daysAgo = 100)))
    }

    @Test fun aSeasonPackStaysWhileItsEpisodesArePlayed() {
        val pack = t(name = "Breaking.Bad.S03.MULTi.1080p.BluRay")
        assertTrue(RdCleanup.isPack(pack))
        assertFalse(RdCleanup.isPack(t(name = "Breaking.Bad.S03E07.MULTi.1080p.mkv")))
        assertFalse(cut(pack, played(10), watched(finished = true, daysAgo = 10)))
        assertTrue(cut(pack, played(31), watched(finished = true, daysAgo = 31)))
    }

    @Test fun neverPlayedAndUnknownTorrents() {
        // Added by Sokoul, never played: two days.
        assertFalse(cut(t(), DesktopDb.RdKnown(now - day, null, null), null))
        assertTrue(cut(t(), DesktopDb.RdKnown(now - 3 * day, null, null), null))
        // Unknown to Sokoul (added before it kept track): thirty days.
        assertFalse(cut(t(age = 20 * day), null, null))
        assertTrue(cut(t(age = 40 * day), null, null))
    }
}
