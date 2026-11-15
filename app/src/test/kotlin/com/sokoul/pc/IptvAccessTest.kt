package com.sokoul.pc

import com.sokoul.desktop.data.*
import com.sokoul.pc.live.TvOrganization
import com.sokoul.pc.playback.sources.canOpenIptvCategory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IptvAccessTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `cached VOD sources honor lock expiry profile switches and category visibility`() {
        DesktopDb(temp.newFile("vod-access.db")).use { db ->
            val id = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://example.test/list", null, null, null, null))
            val p = db.playlists().single()
            db.replaceProviderCategories(id, ProviderKind.Movies, listOf(ProviderCategory("family", "Family"), ProviderCategory("adult", "Adult")))
            val organization = TvOrganization(db, { p })
            fun allowed(category: String?, kids: Boolean = false, title: String = "Film") =
                canOpenIptvCategory(db, p, category, "movie", title, kids)

            assertTrue(organization.setPin("4826"))
            assertTrue(organization.setCategoryLocked("family", true, "movie"))
            assertTrue(allowed("family"))
            organization.lockSession()
            assertFalse(allowed("family"))
            assertEquals(TvOrganization.UnlockResult.Unlocked, organization.unlockPin("4826"))
            assertTrue(allowed("family"))
            assertFalse(allowed("family", kids = true))
            assertFalse(allowed("adult", kids = true))
            assertFalse(allowed(null, kids = true))
            assertFalse(allowed("missing-metadata", kids = true))
            assertFalse(allowed("family", kids = true, title = "XXX feature"))
            assertTrue(organization.setCategoryHidden("family", true, "movie"))
            assertFalse(allowed("family"))
            assertTrue(allowed(null))
        }
    }
}
