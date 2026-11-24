package com.sokoul.pc

import com.sokoul.desktop.data.*
import com.sokoul.pc.live.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TvPersonalizationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun channel(pid: Long, id: String, name: String = "FR| News HD", key: String = "news", epg: String? = "news.fr", group: String = "a") =
        Channel(pid, id, 1, name, null, group, epg, key, "https://example.test/live", null, null, 0)
    private fun source(db: DesktopDb, name: String = "one") = db.upsertPlaylist(
        Playlist(0, name, PlaylistType.M3U, "https://example.test/$name", null, null, null, null))
    private fun fill(db: DesktopDb, pid: Long, vararg channels: Channel) = db.replaceLive(pid,
        listOf(Category(pid, "a", "News", 0), Category(pid, "b", "Sports", 1)), channels.toList())

    @Test fun `collections persist in source and profile namespaces without duplicating quality variants`() {
        val file = temp.newFile("collections.db")
        var collectionId = ""
        DesktopDb(file).use { db ->
            val pid = source(db)
            val c = channel(pid, "1")
            fill(db, pid, c, c.copy(id = "2", name = "FR| News FHD"))
            var profile = ""
            val tv = TvCatalog(db)
            val repo = TvPersonalization(db, tv, { profile })
            collectionId = repo.create("Evening")
            repo.add(collectionId, c)
            repo.add(collectionId, c.copy(id = "2", name = "FR| News FHD"))
            repo.activate(collectionId)
            assertEquals(1, repo.state().collections.single().members.size)
            profile = "child"
            assertTrue(repo.state().collections.isEmpty())
            profile = ""
            assertEquals(collectionId, repo.state().activeCollectionId)
            val second = source(db, "two")
            db.putSetting(Settings.ACTIVE_PLAYLIST, second.toString())
            assertTrue(repo.state().collections.isEmpty())
            db.putSetting(Settings.ACTIVE_PLAYLIST, pid.toString())
        }
        DesktopDb(file).use { db ->
            val repo = TvPersonalization(db, TvCatalog(db), { "" })
            assertEquals(collectionId, repo.state().activeCollectionId)
            assertEquals("Evening", repo.state().collections.single().name)
        }
    }

    @Test fun `adding a group saves eligible channel families once and preserves their order`() {
        DesktopDb(temp.newFile("batch.db")).use { db ->
            val pid = source(db)
            val other = source(db, "other")
            db.putSetting(Settings.ACTIVE_PLAYLIST, pid.toString())
            val first = channel(pid, "1", group = "a")
            val sport = channel(pid, "2", "FR| Sport HD", "sport", "sport.fr", "b")
            val culture = channel(pid, "3", "FR| Culture HD", "culture", "culture.fr", "b")
            val variant = sport.copy(id = "4", name = "FR| Sport FHD")
            val restricted = channel(pid, "5", "FR| Locked", "locked", null, "a")
            fill(db, pid, first, sport, culture, variant, restricted)
            var kids = false
            val tv = TvCatalog(db).apply { kidsProfile = { kids } }
            val repo = TvPersonalization(db, tv, { "" }, { kids })
            val id = repo.create("Mine", listOf(first))
            assertTrue(tv.organization.setPin("1234"))
            assertTrue(tv.organization.setGroupLocked("a", true))
            kids = true
            val revision = repo.revision.value
            val selection = listOf(first, sport, variant, culture, restricted, culture.copy(playlistId = other, id = "foreign"))
            assertEquals(2, repo.addAll(id, selection))
            assertEquals(revision + 1, repo.revision.value)
            assertEquals(listOf("1", "2", "3"), repo.state().collections.single().members.map { it.channelId })
            assertEquals(0, repo.addAll(id, selection))
            assertEquals(revision + 1, repo.revision.value)

            val newId = repo.create("Sports et culture", selection)
            assertEquals(revision + 2, repo.revision.value)
            assertEquals(listOf("2", "3"), repo.state().collections.single { it.id == newId }.members.map { it.channelId })
        }
    }

    @Test fun `refresh resolves renamed ids and safe families but retains missing and ambiguous members`() {
        DesktopDb(temp.newFile("refresh.db")).use { db ->
            val pid = source(db)
            val c = channel(pid, "1")
            fill(db, pid, c)
            val repo = TvPersonalization(db, TvCatalog(db), { "" })
            val id = repo.create("My channels")
            repo.add(id, c)
            fill(db, pid, c.copy(name = "FR| News renamed"))
            assertEquals("FR| News renamed", repo.channels(id).single().name)
            fill(db, pid, c.copy(id = "new"))
            assertEquals("new", repo.channels(id).single().id)
            fill(db, pid, c.copy(id = "other", nameKey = "other"), c.copy(id = "ambiguous", nameKey = "ambiguous"))
            assertNull(repo.members(id).single().channel)
            assertEquals("1", repo.members(id).single().reference.channelId)
            fill(db, pid, c.copy(id = "foreign", name = "UK| News HD"))
            assertTrue(repo.channels(id).isEmpty())
        }
    }

    @Test fun `reorder is atomic preserves unavailable members and deletion resets active collection`() {
        DesktopDb(temp.newFile("order.db")).use { db ->
            val pid = source(db)
            val a = channel(pid, "1")
            val b = channel(pid, "2", "FR| Sport HD", "sport", "sport.fr", "b")
            fill(db, pid, a, b)
            val repo = TvPersonalization(db, TvCatalog(db), { "" })
            val id = repo.create("Mine")
            repo.add(id, a); repo.add(id, b)
            val members = repo.state().collections.single().members.map { it.id }
            assertFalse(repo.reorder(id, listOf(members.first(), members.first())))
            assertEquals(members, repo.state().collections.single().members.map { it.id })
            fill(db, pid, a)
            assertTrue(repo.reorder(id, members.reversed()))
            assertEquals(members.reversed(), repo.state().collections.single().members.map { it.id })
            repo.activate(id); repo.delete(id)
            assertNull(repo.state().activeCollectionId)
            assertTrue(repo.state().collections.isEmpty())
        }
    }

    @Test fun `restricted and unresolved channels do not leak into kids views`() {
        DesktopDb(temp.newFile("access.db")).use { db ->
            val pid = source(db)
            val a = channel(pid, "1")
            fill(db, pid, a)
            var kids = false
            val tv = TvCatalog(db).apply { kidsProfile = { kids } }
            val repo = TvPersonalization(db, tv, { "" }, { kids })
            val id = repo.create("Mine")
            repo.add(id, a)
            assertTrue(tv.organization.setPin("1234"))
            assertTrue(tv.organization.setGroupLocked("a", true))
            kids = true
            assertTrue(repo.members(id).isEmpty())
            repo.add(id, a)
            assertEquals(1, repo.state().collections.single().members.size)
            fill(db, pid)
            assertTrue(repo.members(id).isEmpty())
        }
    }
}
