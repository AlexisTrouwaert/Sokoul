package com.sokoul.pc

import com.sokoul.desktop.data.*
import com.sokoul.pc.live.TvOrganization
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TvOrganizationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `adult pin controls locked groups and kids stay restricted after unlock`() {
        DesktopDb(temp.newFile("organization.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://example.test/list", null, null, null, null))
            val channel = Channel(pid, "adult-channel", 1, "Adult channel", null, "adult", null, "adult", "https://example.test/live", null, null, 0)
            val family = Channel(pid, "family-channel", 2, "Family channel", null, "family", null, "family", "https://example.test/live", null, null, 1)
            db.replaceLive(pid, listOf(Category(pid, "adult", "Adult", 0), Category(pid, "family", "Family", 1)), listOf(channel, family))
            var kids = false
            val organization = TvOrganization(db, { db.playlists().firstOrNull() }, { kids })

            assertTrue(organization.setPin("4826"))
            assertTrue(organization.setGroupLocked("adult", true))
            organization.lockSession()
            assertFalse(organization.canAccess(channel))
            assertEquals(TvOrganization.UnlockResult.WrongPin, organization.unlockPin("1111"))
            assertEquals(TvOrganization.UnlockResult.Unlocked, organization.unlockPin("4826"))
            assertTrue(organization.canAccess(channel))

            kids = true
            assertEquals(TvOrganization.UnlockResult.KidRestricted, organization.unlockPin("4826"))
            assertFalse(organization.canAccess(channel))
            assertTrue(organization.canAccess(family))
        }
    }

    @Test fun `hidden and custom ordered channels are filtered before playback`() {
        DesktopDb(temp.newFile("organization-order.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://example.test/list", null, null, null, null))
            val first = Channel(pid, "one", 1, "One", null, "family", null, "one", "https://example.test/one", null, null, 0)
            val second = Channel(pid, "two", 2, "Two", null, "family", null, "two", "https://example.test/two", null, null, 1)
            db.replaceLive(pid, listOf(Category(pid, "family", "Family", 0)), listOf(first, second))
            val organization = TvOrganization(db, { db.playlists().firstOrNull() })
            assertTrue(organization.setChannelHidden("one", true))
            assertEquals(listOf("two"), organization.filterChannels(listOf(first, second)).map { it.id })
            assertFalse(organization.canAccess(first))
        }
    }

    @Test fun `provider categories with the same id keep independent protection`() {
        DesktopDb(temp.newFile("provider-protection.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://example.test/list", null, null, null, null))
            db.replaceLive(pid, listOf(Category(pid, "1", "Family", 0)), emptyList())
            var kids = false
            val organization = TvOrganization(db, { db.playlists().firstOrNull() }, { kids })
            assertTrue(organization.setPin("4826"))
            assertTrue(organization.setCategoryLocked("1", true, "movie"))
            assertTrue(organization.setCategoryHidden("1", true, "series"))
            organization.lockSession()

            assertTrue(organization.canAccessCategory("1", "Family", "live"))
            assertFalse(organization.canAccessCategory("1", "Family movies", "movie"))
            assertFalse(organization.canAccessCategory("1", "Family series", "series"))
            assertEquals(TvOrganization.UnlockResult.Unlocked, organization.unlockPin("4826"))
            assertTrue(organization.canAccessCategory("1", "Family movies", "movie"))
            assertFalse(organization.canAccessCategory("1", "Family series", "series"))

            kids = true
            assertFalse(organization.canAccessCategory("1", "Family movies", "movie"))
            assertFalse(organization.setCategoryHidden("1", false, "series"))
        }
    }

    @Test fun `organizer can reach and reorder channels beyond its first page`() {
        DesktopDb(temp.newFile("organization-pages.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://example.test/list", null, null, null, null))
            val channels = (1..125).map { index ->
                Channel(pid, "channel-$index", index, "Channel $index", null, "family", null, "channel-$index", "https://example.test/$index", null, null, index)
            }
            db.replaceLive(pid, listOf(Category(pid, "family", "Family", 0)), channels)
            val organization = TvOrganization(db, { db.playlists().firstOrNull() })
            assertEquals(125, organization.channelCount("family"))
            val pages = (0..2).flatMap { organization.channels("family", it * TvOrganization.PAGE_SIZE) }
            assertEquals(channels.map { it.id }, pages.map { it.id })
            assertTrue(organization.moveChannel("channel-61", -1))
            assertEquals("channel-61", organization.channels("family").last().id)
            assertEquals("channel-60", organization.channels("family", TvOrganization.PAGE_SIZE).first().id)
        }
    }
}
