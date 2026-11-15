package com.sokoul.pc

import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Program
import com.sokoul.desktop.data.guideImage
import com.sokoul.iptv.parser.XmltvParser
import com.sokoul.iptv.parser.XmltvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager

/** The programme's picture from the guide: stored with it, kept on an older base, https only (spec pc-live-tv). */
class GuideImageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun an_older_base_keeps_its_guide_and_takes_pictures_after_the_next_sync() {
        val file = File(tmp.root, "old.db")
        // The schema of the first release: no icon column.
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """CREATE TABLE programs(playlist_id INTEGER NOT NULL, channel_key TEXT NOT NULL, start INTEGER NOT NULL,
                    stop INTEGER NOT NULL, title TEXT NOT NULL, description TEXT, PRIMARY KEY(playlist_id, channel_key, start))""",
                )
                st.execute("INSERT INTO programs VALUES(1, 'tf1', 1000, 2000, 'Journal', NULL)")
            }
        }
        DesktopDb(file).use { db ->
            val old = db.programs(1, listOf("tf1"), 0, 10_000)
            assertEquals(listOf("Journal"), old.map { it.title })
            assertNull(old.single().icon)

            db.replacePrograms(1, sequenceOf(Program("tf1", 1000, 2000, "Journal", null, "https://img.test/journal.jpg")), 0)
            assertEquals("https://img.test/journal.jpg", db.programs(1, listOf("tf1"), 0, 10_000).single().icon)        }
    }

    @Test
    fun only_https_pictures_are_kept() {
        assertEquals("https://img.test/a.jpg", guideImage(" https://img.test/a.jpg "))
        assertNull(guideImage("http://img.test/a.jpg"))
        assertNull(guideImage("file:///etc/passwd"))
        assertNull(guideImage("https://"))
        assertNull(guideImage("https://img.test/a b.jpg"))
        assertNull(guideImage(null))
    }

    @Test
    fun an_empty_refresh_preserves_the_last_stored_guide() {
        DesktopDb(File(tmp.root, "empty-refresh.db")).use { db ->
            val previous = Program("tf1", 1_000, 2_000, "Journal", null)
            db.replacePrograms(1, sequenceOf(previous), 0)

            assertEquals(0, db.replacePrograms(1, emptySequence(), 0))
            assertEquals(listOf(previous), db.programs(1, listOf("tf1"), 0, 10_000))
        }
    }

    @Test
    fun an_older_channel_table_migrates_and_preserves_advertised_catchup_metadata() {
        val file = File(tmp.root, "old-channels.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """CREATE TABLE channels(playlist_id INTEGER NOT NULL, id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL,
                    logo TEXT, category_id TEXT NOT NULL, epg_key TEXT, name_key TEXT NOT NULL, url TEXT, user_agent TEXT, referrer TEXT,
                    position INTEGER NOT NULL, PRIMARY KEY(playlist_id, id))""",
                )
            }
        }
        DesktopDb(file).use { db ->
            val channel = com.sokoul.desktop.data.Channel(
                1, "arte", 7, "Arte", null, "fr", "arte.fr", "arte", "https://tv.test/live", null, null, 0,
                catchupType = "default", catchupSource = "?utc={utc}", catchupDays = 3,
            )
            db.replaceLive(1, listOf(com.sokoul.desktop.data.Category(1, "fr", "France", 0)), listOf(channel))
            val restored = db.channels(1, "fr").single()
            assertEquals("default", restored.catchupType)
            assertEquals("?utc={utc}", restored.catchupSource)
            assertEquals(3, restored.catchupDays)
        }
    }

    @Test
    fun the_guide_gives_the_picture_of_a_programme_when_it_has_one() {
        val xml = """
            <tv>
              <programme start="20261108200000 +0000" stop="20261108210000 +0000" channel="tf1">
                <title>Journal</title><icon src="https://img.test/journal.jpg"/>
              </programme>
              <programme start="20261108210000 +0000" stop="20261108220000 +0000" channel="tf1">
                <title>Film</title>
              </programme>
            </tv>
        """.trimIndent()
        val found = mutableListOf<XmltvProgramme>()
        val parser = org.kxml2.io.KXmlParser().apply { setInput(xml.reader()) }
        XmltvParser.parse(parser, onChannel = {}, onProgramme = { found += it })
        assertEquals(listOf("https://img.test/journal.jpg", null), found.map { guideImage(it.icon) })
    }
}
