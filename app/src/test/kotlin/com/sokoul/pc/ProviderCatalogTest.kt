package com.sokoul.pc

import com.sokoul.desktop.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderCatalogTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `provider browser paginates one category and treats wildcard characters literally`() {
        DesktopDb(temp.newFile("categories.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://example.test/list", null, null, null, null))
            db.replaceVod(pid, listOf(
                Movie(pid, "1", "100% Film", 2020, null, null, "fr", "mp4", null, null),
                Movie(pid, "2", "Second Film", 2021, null, null, "fr", "mp4", null, null),
                Movie(pid, "3", "Other", 2022, null, null, "en", "mp4", null, null),
            ), emptyList())
            db.replaceProviderCategories(pid, ProviderKind.Movies, listOf(ProviderCategory("fr", "French"), ProviderCategory("en", "English")))
            val catalog = ProviderCatalog(db)
            val first = catalog.page(ProviderKind.Movies, "fr", limit = 1)
            assertEquals(2, first.total)
            assertEquals("1", first.items.single().id)
            assertTrue(first.hasMore)
            val second = catalog.page(ProviderKind.Movies, "fr", offset = 1, limit = 1)
            assertEquals("2", second.items.single().id)
            assertFalse(second.hasMore)
            assertEquals(listOf("1"), catalog.page(ProviderKind.Movies, query = "%").items.map { it.id })
            assertEquals(2, db.providerCategories(pid, ProviderKind.Movies).first { it.id == "fr" }.count)
        }
    }

    @Test fun `provider page reads remain available while the primary database monitor is held`() {
        DesktopDb(temp.newFile("provider-reader.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://example.test/list", null, null, null, null))
            db.replaceVod(pid, listOf(Movie(pid, "1", "Available", 2020, null, null, "fr", "mp4", null, null)), emptyList())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writer = Thread {
                synchronized(db) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }.apply { start() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val page = ProviderCatalog(db).page(pid, ProviderKind.Movies, categoryId = "fr", exactCount = false)
                assertEquals(listOf("1"), page.items.map { it.id })
                assertTrue("The simulated guide writer should still hold the primary DB monitor", writer.isAlive)
            } finally {
                release.countDown()
                writer.join(2_000)
            }
        }
    }

    @Test fun `guide offset shifts stored programmes only by its delta and preserves adjacent rows`() {
        DesktopDb(temp.newFile("offset.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://example.test/list", null, null, null, null))
            val start = 1_800_000_000_000L
            db.replacePrograms(pid, sequenceOf(Program("one", start, start + 3_600_000, "First", null), Program("one", start + 3_600_000, start + 7_200_000, "Second", null)), 0)
            val catalog = ProviderCatalog(db)
            catalog.setGuideOffsetMinutes(60)
            assertEquals(listOf(start + 3_600_000, start + 7_200_000), db.programs(pid, listOf("one"), 0, Long.MAX_VALUE).map { it.start })
            catalog.setGuideOffsetMinutes(60)
            assertEquals(2, db.programCount(pid))
            assertEquals(start + 3_600_000, db.programs(pid, listOf("one"), 0, Long.MAX_VALUE).first().start)
            catalog.setGuideOffsetMinutes(0)
            assertEquals(start, db.programs(pid, listOf("one"), 0, Long.MAX_VALUE).first().start)
        }
    }

    @Test fun `account snapshot retains only safe metadata and is reused during cache window`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"user_info":{"auth":1,"status":"Active","username":"private-user","password":"private-pass","max_connections":"1","active_cons":"0","exp_date":"1900000000","allowed_output_formats":["ts","m3u8"]},"server_info":{"timezone":"UTC"}}"""))
            DesktopDb(temp.newFile("account.db")).use { db ->
                val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.XTREAM, server.url("/").toString(), "private-user", "private-pass", null, null))
                val catalog = ProviderCatalog(db)
                val snapshot = catalog.account()!!
                assertTrue(snapshot.active)
                assertEquals(listOf("ts", "m3u8"), snapshot.formats)
                assertEquals(1_900_000_000_000L, requireNotNull(snapshot.expiresAt))
                assertEquals(snapshot, catalog.account())
                assertEquals(1, server.requestCount)
                val persisted = db.setting("provider_account_$pid")!!
                assertFalse(persisted.contains("private-user"))
                assertFalse(persisted.contains("private-pass"))
                catalog.setOutputFormat("m3u8")
                assertEquals("m3u8", catalog.outputFormat())
            }
        }
    }
}
