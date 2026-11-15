package com.sokoul.pc

import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Category
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Playlist
import com.sokoul.desktop.data.PlaylistType
import com.sokoul.desktop.data.Program
import com.sokoul.pc.live.TvCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveCapabilityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `cancelled provider lookup can be retried immediately`() = runBlocking {
        DesktopDb(temp.newFile("cancelled-guide.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.XTREAM, "https://provider.test", "user", "pass", null, null))
            val channel = Channel(pid, "1", 1, "Test", null, "fr", "test.fr", "test", null, null, null, 0)
            var requests = 0
            val now = System.currentTimeMillis()
            val catalog = TvCatalog(db) {
                requests++
                if (requests == 1) throw kotlinx.coroutines.CancellationException("Window changed")
                listOf(Program("test.fr", now - 1000, now + 60_000, "Current", null))
            }
            try { catalog.fillShortGuide(listOf(channel)) { emptyList() } } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals("Current", catalog.fillShortGuide(listOf(channel)) { emptyList() }[channel.id]?.single()?.title)
            assertEquals(2, requests)
        }
    }

    @Test
    fun `guide grid falls back to provider when stored XMLTV only has ended programmes`() = runBlocking {
        DesktopDb(temp.newFile("ended-guide.db")).use { db ->
            val playlistId = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.XTREAM, "https://provider.test", "user", "pass", null, null))
            db.putSetting(com.sokoul.desktop.data.Settings.ACTIVE_PLAYLIST, playlistId.toString())
            val channel = Channel(playlistId, "3875", 1, "TF1", null, "fr", "tf1.fr", "tf1", null, null, null, 0, catchupDays = 2)
            db.replaceLive(playlistId, listOf(Category(playlistId, "fr", "France", 0)), listOf(channel))
            val now = System.currentTimeMillis()
            val ended = Program("tf1.fr", now - 90 * 60_000, now - 30 * 60_000, "Ended programme", null)
            val distant = Program("tf1.fr", now + 8 * 60 * 60_000, now + 9 * 60 * 60_000, "Distant programme", null)
            db.replacePrograms(playlistId, sequenceOf(ended, distant), now - 2 * 24 * 60 * 60_000)
            val upcoming = Program("tf1.fr", now - 5 * 60_000, now + 25 * 60_000, "Current programme", null)
            var requests = 0
            val catalog = TvCatalog(db) {
                requests++
                listOf(upcoming)
            }

            val stored = catalog.schedules(listOf(channel))[channel.id].orEmpty()
            val result = catalog.fillShortGuide(listOf(channel), now, { stored })

            assertEquals(1, requests)
            assertEquals("Current programme", result[channel.id]?.single()?.title)
        }
    }

    @Test
    fun `guide grid avoids provider request when XMLTV has current or upcoming programmes`() = runBlocking {
        DesktopDb(temp.newFile("current-guide.db")).use { db ->
            val playlistId = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.XTREAM, "https://provider.test", "user", "pass", null, null))
            db.putSetting(com.sokoul.desktop.data.Settings.ACTIVE_PLAYLIST, playlistId.toString())
            val channel = Channel(playlistId, "3875", 1, "TF1", null, "fr", "tf1.fr", "tf1", null, null, null, 0)
            db.replaceLive(playlistId, listOf(Category(playlistId, "fr", "France", 0)), listOf(channel))
            val now = System.currentTimeMillis()
            val current = Program("tf1.fr", now - 5 * 60_000, now + 25 * 60_000, "Current programme", null)
            db.replacePrograms(playlistId, sequenceOf(current), now - 24 * 60 * 60_000)
            var requests = 0
            val catalog = TvCatalog(db) {
                requests++
                emptyList()
            }

            val stored = catalog.schedules(listOf(channel))[channel.id].orEmpty()
            val result = catalog.fillShortGuide(listOf(channel), now, { stored })

            assertEquals(0, requests)
            assertEquals(emptyMap<String, List<Program>>(), result)
        }
    }

    @Test
    fun `live player falls back to the provider short guide and caches it`() = runBlocking {
        DesktopDb(temp.newFile("short-guide.db")).use { db ->
            val playlistId = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.XTREAM, "https://provider.test", "user", "pass", null, null))
            db.putSetting(com.sokoul.desktop.data.Settings.ACTIVE_PLAYLIST, playlistId.toString())
            val channel = Channel(playlistId, "3875", 1, "TF1", null, "fr", "tf1.fr", "tf1", null, null, null, 0)
            db.replaceLive(playlistId, listOf(Category(playlistId, "fr", "France", 0)), listOf(channel))
            val now = System.currentTimeMillis()
            var requests = 0
            val catalog = TvCatalog(db) {
                requests++
                listOf(Program("tf1.fr", now - 60_000, now + 30 * 60_000, "Journal du test", null))
            }
            val live = catalog.toLive(channel)!!

            assertEquals("Journal du test", live.program().now?.title)
            assertEquals("Journal du test", live.schedule().single().title)
            assertEquals(1, requests)
        }
    }

    @Test
    fun `catalog exposes only source-advertised replay within retention`() = runBlocking {
        DesktopDb(temp.newFile("live-capability.db")).use { db ->
            val playlistId = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://provider.test/list.m3u", null, null, null, null))
            db.putSetting(com.sokoul.desktop.data.Settings.ACTIVE_PLAYLIST, playlistId.toString())
            val channel = Channel(
                playlistId, "arte", 1, "Arte", null, "fr", "arte.fr", "arte", "https://provider.test/live.m3u8",
                null, null, 0, "default", "https://provider.test/replay?from={utc}&to={utcend}", 3,
            )
            db.replaceLive(playlistId, listOf(Category(playlistId, "fr", "France", 0)), listOf(channel))
            val now = System.currentTimeMillis()
            val inside = Program("arte.fr", now - 60 * 60_000, now - 30 * 60_000, "Earlier", null)
            db.replacePrograms(playlistId, sequenceOf(inside), 0)

            val live = TvCatalog(db).toLive(channel)!!
            assertEquals(3, live.capabilities.catchupDays)
            assertNotNull(live.replay(inside))
            assertEquals("The archive URL already contains its date; media playback starts at offset zero", 0L, live.replay(inside)!!.startMs)
            assertNull(live.replay(inside.copy(start = now - 4 * 24 * 60 * 60_000, end = now - 4 * 24 * 60 * 60_000 + 30 * 60_000)))

            val liveOnly = TvCatalog(db).toLive(channel.copy(catchupType = null, catchupSource = null, catchupDays = 0))!!
            assertEquals(false, liveOnly.capabilities.catchup)
            assertNull(liveOnly.replay(inside))
        }
    }
}
