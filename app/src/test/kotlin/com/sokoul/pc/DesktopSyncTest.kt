package com.sokoul.pc

import com.sokoul.desktop.data.Category
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.DesktopSync
import com.sokoul.desktop.data.Playlist
import com.sokoul.desktop.data.PlaylistType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DesktopSyncTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `xtream guide loads before the film and series catalogues`() = runBlocking {
        MockWebServer().use { server ->
            listOf("[]", "[]", "<tv/>", "[]", "[]").forEach { server.enqueue(MockResponse().setBody(it)) }
            DesktopDb(temp.newFile("guide-first.db")).use { db ->
                db.upsertPlaylist(Playlist(0, "Test", PlaylistType.XTREAM, server.url("/").toString().trimEnd('/'), "user", "pass", null, null))
                DesktopSync(db).sync(db.playlists().single())
                val paths = (1..5).map { server.takeRequest().requestUrl!! }
                assertEquals("get_live_categories", paths[0].queryParameter("action"))
                assertEquals("get_live_streams", paths[1].queryParameter("action"))
                assertEquals("/xmltv.php", paths[2].encodedPath)
                assertEquals("get_vod_streams", paths[3].queryParameter("action"))
                assertEquals("get_series", paths[4].queryParameter("action"))
            }
        }
    }

    @Test
    fun `an oversized write ahead log is reclaimed without losing committed data`() {
        val file = temp.newFile("storage.db")
        val bytes = 34 * 1024 * 1024
        DesktopDb(file).use { db ->
            java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { writer ->
                writer.createStatement().use { statement ->
                    statement.execute("PRAGMA wal_autocheckpoint=0")
                    statement.execute("CREATE TABLE storage_probe (payload BLOB NOT NULL)")
                    statement.executeUpdate("INSERT INTO storage_probe VALUES (zeroblob($bytes))")
                    val wal = java.io.File(file.absolutePath + "-wal")
                    assertTrue(wal.length() > bytes)

                    db.checkpointStorage()

                    assertEquals(0L, wal.length())
                    statement.executeQuery("SELECT length(payload) FROM storage_probe").use {
                        assertTrue(it.next())
                        assertEquals(bytes, it.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun `an interrupted large Xtream refresh preserves the published channel list`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"category_id":"fr","category_name":"France"}]"""))
            val partial = (1..1_005).joinToString(prefix = "[", postfix = ",{") { index ->
                """{"stream_id":$index,"name":"Channel $index","category_id":"fr"}"""
            }
            server.enqueue(MockResponse().setBody(partial))
            DesktopDb(temp.newFile("interrupted-refresh.db")).use { db ->
                val id = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.XTREAM, server.url("/").toString().trimEnd('/'), "user", "pass", null, null))
                val channel = Channel(id, "saved", 1, "Existing channel", null, "fr", null, "saved", null, null, null, 0)
                db.replaceLive(id, listOf(Category(id, "fr", "France", 0)), listOf(channel))

                val sync = DesktopSync(db)
                sync.sync(db.playlists().single())

                assertEquals(listOf(channel), db.channels(id, "fr"))
                assertEquals(2, server.requestCount)
                assertTrue(sync.state.value.error != null)
            }
        }
    }

    @Test
    fun `configured xmltv source populates the active channel schedule`() = runBlocking {
        MockWebServer().use { server ->
            val now = System.currentTimeMillis()
            fun xmltvTime(time: Long) = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
            val start = xmltvTime(now - 30 * 60_000)
            val stop = xmltvTime(now + 30 * 60_000)
            val pastStart = xmltvTime(now - 3 * 86_400_000L)
            val pastEnd = xmltvTime(now - 3 * 86_400_000L + 3_600_000)
            val laterStart = xmltvTime(now + 42 * 3_600_000L)
            val laterEnd = xmltvTime(now + 43 * 3_600_000L)
            server.enqueue(
                MockResponse().setBody(
                    """<?xml version="1.0" encoding="UTF-8"?><tv>
                        <channel id="tf1.fr"><display-name>TF1</display-name></channel>
                        <programme channel="tf1.fr" start="$start" stop="$stop"><title>Journal du test</title></programme>
                        <programme channel="tf1.fr" start="$pastStart" stop="$pastEnd"><title>Earlier test</title></programme>
                        <programme channel="tf1.fr" start="$laterStart" stop="$laterEnd"><title>Later test</title></programme>
                    </tv>""".trimIndent(),
                ),
            )
            DesktopDb(temp.newFile("epg-sync.db")).use { db ->
                val id = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://provider.test/playlist.m3u", null, null, server.url("/guide.xml").toString(), null))
                val playlist = db.playlists().single()
                val channel = Channel(id, "tf1", 1, "FR| TF1 FHD", null, "fr", "outdated-provider-id", "tf1", "https://provider.test/live.ts", null, null, 0)
                db.replaceLive(id, listOf(Category(id, "fr", "France", 0)), listOf(channel))

                val sync = DesktopSync(db)
                assertEquals(3, sync.syncGuide(playlist))
                assertFalse(sync.isGuideStale(playlist, now))
                assertTrue(sync.isGuideStale(playlist, now + 7 * 3_600_000L))
                assertEquals("Earlier test", db.programs(id, listOf("tf1.fr"), now - 4 * 86_400_000L, now - 2 * 86_400_000L).single().title)
                assertEquals("Journal du test", db.programs(id, listOf("tf1.fr"), now - 60_000, now + 60 * 60_000).single().title)
                val catalog = com.sokoul.pc.live.TvCatalog(db)
                assertEquals(listOf("Journal du test", "Later test"), catalog.schedules(listOf(channel))[channel.id]?.map { it.title })
                assertEquals("Journal du test", catalog.toLive(channel)?.program()?.now?.title)
            }
        }
    }
}
