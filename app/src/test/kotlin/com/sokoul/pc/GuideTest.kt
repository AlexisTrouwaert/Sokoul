package com.sokoul.pc

import com.sokoul.desktop.data.Program
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.pc.live.GuideLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GuideTest {
    private fun p(start: Long, end: Long) = Program("k", start, end, "t$start", null)
    private val list = listOf(p(0, 100), p(100, 200), p(250, 300))

    @Test
    fun the_programme_at_a_moment_or_the_next_one() {
        assertEquals(100L, GuideLogic.programAt(list, 150)?.start)
        assertEquals("a gap gives the next", 250L, GuideLogic.programAt(list, 220)?.start)
        assertNull(GuideLogic.programAt(list, 400))
    }

    @Test
    fun left_from_what_is_on_finds_nothing_so_the_drawer_opens() {
        assertNull("what is over is not offered", GuideLogic.previous(list, list[1], now = 150))
        assertEquals("from later on, back towards now", 100L, GuideLogic.previous(list, list[2], now = 150)?.start)
        assertEquals(250L, GuideLogic.next(list, list[1])?.start)
    }

    @Test
    fun row_geometry_never_changes_when_focus_moves() {
        val height = 86f
        for (focusedRow in 0..9) {
            assertEquals(height, GuideLogic.rowHeight(height), 0.01f)
            for (i in 0..9) assertEquals(i * height, GuideLogic.rowTop(i, height), 0.01f)
        }
    }

    @Test
    fun a_point_maps_to_a_fixed_row_boundary() {
        val height = 86f
        assertEquals(0, GuideLogic.rowAt(10f, 20, height))
        assertEquals(2, GuideLogic.rowAt(3 * height - 1, 20, height))
        assertEquals(3, GuideLogic.rowAt(3 * height, 20, height))
        assertNull(GuideLogic.rowAt(99999f, 20, height))
    }

    @Test
    fun scrolling_waits_until_focus_nears_the_viewport_edge() {
        val row = 86f
        val viewport = 500f
        assertEquals("no movement while comfortably visible", 0f, GuideLogic.scrollFor(3, 50, row, viewport, 0f), 0.01f)
        assertEquals("scroll one row of margin into view", 102f, GuideLogic.scrollFor(5, 50, row, viewport, 0f), 0.01f)
        assertEquals("no movement while inside margin", 102f, GuideLogic.scrollFor(4, 50, row, viewport, 102f), 0.01f)
        assertEquals("clamp at the content end", 50 * row - viewport, GuideLogic.scrollFor(49, 50, row, viewport, 50 * row - viewport), 0.01f)
        assertEquals("a short list does not scroll", 0f, GuideLogic.scrollFor(2, 3, row, viewport, 0f), 0.01f)
    }

    @Test
    fun a_five_minute_programme_remains_addressable() {
        val short = p(10 * 60_000L, 15 * 60_000L)
        val schedule = listOf(p(0, short.start), short, p(short.end, 60 * 60_000L))
        assertEquals(short, GuideLogic.programAt(schedule, short.start + 2 * 60_000L))
        assertEquals(short.end, GuideLogic.next(schedule, short)?.start)
    }

    @Test
    fun rapid_navigation_keeps_focus_inside_a_large_fixed_row_viewport() {
        val count = 1_000
        val rowHeight = 40f
        val viewport = 320f
        var scroll = 0f
        for (selected in listOf(0, 1, 5, 6, 7, 8, 24, 127, 500, 997, 999)) {
            scroll = GuideLogic.scrollFor(selected, count, rowHeight, viewport, scroll)
            val top = GuideLogic.rowTop(selected, rowHeight) - scroll
            assertTrue("selected row $selected stays in view", top >= -0.01f && top + rowHeight <= viewport + 0.01f)
            assertEquals(selected, GuideLogic.rowAt(selected * rowHeight + rowHeight / 2, count, rowHeight))
            assertTrue("scroll stays within the channel list", scroll in 0f..(count * rowHeight - viewport))
        }
    }

    @Test
    fun the_time_axis_moves_only_when_the_programme_leaves_it() {
        val now = 10_000_000L
        val hour = 3_600_000L
        assertEquals("already in view", now, GuideLogic.leftEdgeFor(now + 60_000, now + 120_000, now, now, 2 * hour))
        assertTrue(GuideLogic.leftEdgeFor(now + 5 * hour, now + 6 * hour, now, now, 2 * hour) > now)
        assertEquals("never far into the past", now - GuideLogic.PAST_SHOWN_MS, GuideLogic.leftEdgeFor(now - 5 * hour, now - 4 * hour, now, now, 2 * hour))
    }

    @Test
    fun typing_narrows_the_groups() {
        assertTrue(GuideLogic.matches("EU | FRANCE SPORTS", "fr spo"))
        assertFalse(GuideLogic.matches("EU | FRANCE SPORTS", "uk"))
        assertTrue(GuideLogic.matches("Anything", ""))
    }

    private val paris = java.time.ZoneId.of("Europe/Paris")
    private fun at(h: Int, m: Int = 0) = java.time.ZonedDateTime.of(2026, 11, 3, h, m, 0, 0, paris).toInstant().toEpochMilli()

    @Test
    fun tonight_is_prime_time_today_until_it_has_begun() {
        assertEquals(at(21), GuideLogic.tonight(at(15), paris))
        assertEquals("after nine, later tonight", at(23), GuideLogic.tonight(at(22), paris))
        assertEquals("past that, the next evening", at(21) + 24 * 3_600_000L, GuideLogic.tonight(at(23, 30), paris))
        assertEquals(at(21) + 24 * 3_600_000L, GuideLogic.tomorrowEvening(at(15), paris))
    }

    @Test
    fun a_jump_lands_on_what_begins_not_on_what_is_ending() {
        assertEquals(250L, GuideLogic.jumpTarget(list, 210))
        assertEquals("nothing known that far: the last that is", 250L, GuideLogic.jumpTarget(list, 999))
        assertEquals("no guide at all: the moment itself", 999L, GuideLogic.jumpTarget(emptyList(), 999))
    }

    @Test
    fun a_row_stands_for_a_channel_without_its_list_or_quality() {
        assertEquals("TF1", com.sokoul.pc.live.TvCatalog.shortLabel("FR| TF1 FHD"))
        assertEquals("TF1 +1", com.sokoul.pc.live.TvCatalog.shortLabel("FR| TF1 +1 FHD"))
        assertEquals("CANAL+ SPORT [BK]", com.sokoul.pc.live.TvCatalog.shortLabel("FR| CANAL+ SPORT FHD [BK]"))
        assertEquals("a name that is only a quality stays", "HD", com.sokoul.pc.live.TvCatalog.shortLabel("HD"))
    }

    @Test
    fun what_was_measured_beats_what_the_name_says() {
        val c = com.sokoul.pc.live.StreamChoice
        assertTrue("unmeasured, FHD before a UHD that often is not", c.expectedHeight("TF1 FHD", 0) > c.expectedHeight("TF1 UHD", 0))
        assertEquals(720, c.expectedHeight("TF1 FHD", 720))
        assertTrue("a real 2160 wins", c.expectedHeight("TF1 UHD", 2160) > c.expectedHeight("TF1 FHD", 720))
        assertEquals(1, c.failedLately(1000, 1000 + 60_000))
        assertEquals("forgiven after hours", 0, c.failedLately(1000, 1000 + 7 * 3_600_000L))
        assertEquals(0, c.failedLately(0, 5))
    }

    @Test
    fun a_reminder_comes_a_minute_before_and_once() {
        val r = com.sokoul.pc.live.Reminder("1", "TF1", "JT", 100_000)
        val rules = com.sokoul.pc.live.ReminderRules
        assertNull("too early", rules.due(listOf(r), emptySet(), 10_000))
        assertEquals(r, rules.due(listOf(r), emptySet(), 45_000))
        assertNull("once", rules.due(listOf(r), setOf(r), 45_000))
    }

    @Test
    fun watchlist_keeps_only_unique_future_programmes_in_start_order() {
        val first = com.sokoul.pc.live.Reminder("1", "TF1", "News", 30_000)
        val later = com.sokoul.pc.live.Reminder("2", "Arte", "Film", 90_000)
        val duplicate = first.copy(channel = "TF1 HD")

        assertEquals(
            listOf(first, later),
            com.sokoul.pc.live.ReminderRules.upcoming(listOf(later, duplicate, first), now = 10_000),
        )
        assertTrue(com.sokoul.pc.live.ReminderRules.upcoming(listOf(first), now = first.start).isEmpty())
    }

    @Test
    fun removing_a_watchlist_item_preserves_other_reminders_across_database_reload() {
        val database = DesktopDb(Files.createTempDirectory("sokoul-watchlist").resolve("test.db").toFile())
        database.use { db ->
            val reminders = com.sokoul.pc.live.Reminders(db)
            val first = com.sokoul.pc.live.Reminder("1", "TF1", "News", 30_000)
            val second = com.sokoul.pc.live.Reminder("2", "Arte", "Film", 90_000)
            reminders.toggle(first)
            reminders.toggle(second)

            reminders.remove(first)

            assertEquals(listOf(second), reminders.items)
            assertEquals(listOf(second), com.sokoul.pc.live.Reminders(db).items)
        }
    }
}
