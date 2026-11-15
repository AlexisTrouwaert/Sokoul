package com.sokoul.pc

import com.sokoul.desktop.data.Program
import com.sokoul.pc.playback.ui.LiveProgramAction
import com.sokoul.pc.playback.ui.liveProgramAction
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlayerActionsTest {
    @Test
    fun future_program_toggles_only_its_reminder() {
        val now = 2_000_000L
        val future = Program("channel", now + 60_000, now + 120_000, "Later", null)
        assertEquals(LiveProgramAction.SetReminder, liveProgramAction(future, now, catchupDays = 0, reminded = false))
        assertEquals(LiveProgramAction.RemoveReminder, liveProgramAction(future, now, catchupDays = 0, reminded = true))
    }

    @Test
    fun past_program_replay_requires_advertised_retention_and_valid_interval() {
        val now = 10L * 24 * 60 * 60_000
        val recent = Program("channel", now - 60_000, now - 30_000, "Recent", null)
        val old = recent.copy(start = now - 4 * 24 * 60 * 60_000, end = now - 4 * 24 * 60 * 60_000 + 30_000)
        assertEquals(LiveProgramAction.Replay, liveProgramAction(recent, now, catchupDays = 3, reminded = false))
        assertEquals(LiveProgramAction.None, liveProgramAction(recent, now, catchupDays = 0, reminded = false))
        assertEquals(LiveProgramAction.None, liveProgramAction(old, now, catchupDays = 3, reminded = false))
        assertEquals(LiveProgramAction.None, liveProgramAction(recent.copy(end = now + 1), now, catchupDays = 3, reminded = false))
    }

    @Test
    fun an_airing_program_is_informational_not_a_reminder_or_replay_action() {
        val now = 2_000_000L
        val current = Program("channel", now - 10_000, now + 10_000, "Now", null)
        assertEquals(LiveProgramAction.None, liveProgramAction(current, now, catchupDays = 5, reminded = false))
    }
}
