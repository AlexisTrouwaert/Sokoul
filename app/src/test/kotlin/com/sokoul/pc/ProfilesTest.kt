package com.sokoul.pc

import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Resume
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.MyList
import com.sokoul.pc.data.Profiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Local profiles: what one watches and keeps is not another's, and the first profile keeps what existed before. */
class ProfilesTest {
    private lateinit var file: File
    private lateinit var db: DesktopDb

    @Before
    fun open() {
        file = File.createTempFile("profiles", ".db")
        db = DesktopDb(file)
    }

    @After
    fun close() {
        db.close()
        file.delete()
    }

    private fun watched(id: Int, at: Long, position: Long = 60_000) =
        Resume(Resume.key("movie", id, null, null), "movie", id, null, null, "Film $id", null, null, position, 6_000_000, at, false)

    @Test
    fun aProfileCodeIsCheckedKeptAsADigestAndSurvivesARestart() {
        val profiles = Profiles(db)
        val kid = profiles.add("Lou", kids = true)
        val parent = profiles.add("Sam", kids = false)
        profiles.setPin(parent, "4821")
        val locked = profiles.all.first { it.id == parent.id }
        assertTrue(locked.locked)
        // Only the digest is kept: the code itself is not in the profile.
        assertFalse(locked.pinHash!!.contains("4821"))
        assertTrue(profiles.pinMatches(locked, "4821"))
        assertFalse(profiles.pinMatches(locked, "1234"))
        // A profile without a code opens with anything.
        assertTrue(profiles.pinMatches(profiles.all.first { it.id == kid.id }, "0000"))
        // The same code on another profile does not give the same digest.
        profiles.setPin(kid, "4821")
        assertTrue(profiles.all.first { it.id == kid.id }.pinHash != locked.pinHash)
        // Read again from the database, as at the next start.
        val again = Profiles(db)
        assertTrue(again.pinMatches(again.all.first { it.id == parent.id }, "4821"))
        assertFalse(again.pinMatches(again.all.first { it.id == parent.id }, "4822"))
        again.setPin(again.all.first { it.id == parent.id }, null)
        assertFalse(again.all.first { it.id == parent.id }.locked)
    }

    @Test
    fun aLockedCurrentProfileIsAskedForAtStart() {
        val profiles = Profiles(db)
        assertFalse(profiles.asksAtStart)
        profiles.setPin(profiles.current, "1111")
        assertTrue(Profiles(db).asksAtStart)
    }

    @Test
    fun resumePointsBelongToTheirProfile() {
        // Written before any profile existed: it stays the first profile's.
        db.saveResume(watched(1, 1))
        val profiles = Profiles(db)
        val anna = profiles.add("Anna", kids = false)

        profiles.switchTo(anna)
        assertTrue(db.resumeList().isEmpty())
        db.saveResume(watched(1, 2, position = 120_000))
        db.saveResume(watched(2, 3))
        assertEquals(listOf(2, 1), db.resumeList().map { it.tmdbId })
        assertEquals(120_000, db.resume(Resume.key("movie", 1, null, null))!!.positionMs)
        // The key read back is the plain one, so the rest of the app can use it as before.
        assertEquals(Resume.key("movie", 2, null, null), db.history().first().key)

        profiles.switchTo(profiles.all.first())
        assertEquals(listOf(1), db.resumeList().map { it.tmdbId })
        assertEquals(60_000, db.resume(Resume.key("movie", 1, null, null))!!.positionMs)

        // Removing a profile removes what it watched, and only that.
        profiles.remove(anna)
        assertEquals(1, profiles.all.size)
        db.profile = anna.id
        assertTrue(db.history().isEmpty())
        db.profile = Profiles.MAIN
        assertNotNull(db.resume(Resume.key("movie", 1, null, null)))
    }

    @Test
    fun eachProfileHasItsList() {
        val profiles = Profiles(db)
        val list = MyList(db) { profiles.key(MyList.SETTING) }
        val film = TmdbItem(7, "Seven", null, 1995, null, null, null, null, mediaType = "movie")
        list.toggle(film)
        val leo = profiles.add("Léo", kids = true)
        profiles.switchTo(leo)
        list.reload()
        assertFalse(list.contains(film))
        profiles.switchTo(profiles.all.first())
        list.reload()
        assertTrue(list.contains(film))
    }

    @Test
    fun theChoiceIsKeptAndAskedOnlyWithSeveralProfiles() {
        val profiles = Profiles(db)
        assertFalse(profiles.asksAtStart)
        val anna = profiles.add("Anna", kids = false)
        profiles.switchTo(anna)
        val again = Profiles(db)
        assertTrue(again.asksAtStart)
        assertEquals("Anna", again.current.name)
        assertEquals(anna.id, db.profile)
    }

    @Test
    fun kidsCannotStartAdultRatings() {
        assertTrue(Profiles.allowedForKids("12"))
        assertTrue(Profiles.allowedForKids(null))
        assertTrue(Profiles.allowedForKids("PG-13"))
        assertFalse(Profiles.allowedForKids("16"))
        assertFalse(Profiles.allowedForKids("18"))
        assertFalse(Profiles.allowedForKids("tv-ma"))
        assertNull(Profiles(db).all.firstOrNull { it.kids })
    }

    @Test
    fun editingCurrentProfileRestrictionsInvalidatesTheSession() {
        val profiles = Profiles(db)
        var invalidations = 0
        profiles.onSwitch = { invalidations++ }
        profiles.update(profiles.current.copy(name = "Renamed"))
        assertEquals(0, invalidations)
        profiles.update(profiles.current.copy(kids = true))
        assertTrue(profiles.current.kids)
        assertEquals(1, invalidations)
        val other = profiles.add("Other", kids = false)
        profiles.update(other.copy(kids = true))
        assertEquals(1, invalidations)
        profiles.update(profiles.current.copy(kids = false))
        assertEquals(2, invalidations)
    }
}
