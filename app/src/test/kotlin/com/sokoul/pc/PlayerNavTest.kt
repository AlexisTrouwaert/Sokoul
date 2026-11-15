package com.sokoul.pc

import com.sokoul.pc.playback.ui.NavAction
import com.sokoul.pc.playback.ui.NavKey
import com.sokoul.pc.playback.ui.NavMode
import com.sokoul.pc.playback.ui.PlayerNav
import com.sokoul.pc.playback.session.Phase
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.pc.playback.session.VersionOrigin
import com.sokoul.pc.playback.ui.audioActionAvailable
import com.sokoul.pc.playback.ui.playbackRemainingMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNavTest {
    @Test
    fun unknown_duration_has_no_remaining_time_and_stale_positions_are_clamped() {
        assertEquals(null, playbackRemainingMs(30_000, 0))
        assertEquals(null, playbackRemainingMs(30_000, -1))
        assertEquals(60_000L, playbackRemainingMs(-500, 60_000))
        assertEquals(30_000L, playbackRemainingMs(30_000, 60_000))
        assertEquals(0L, playbackRemainingMs(90_000, 60_000))
    }

    @Test
    fun audio_action_is_available_for_a_single_detected_track() {
        assertEquals(false, audioActionAvailable(null))
        assertEquals(false, audioActionAvailable(com.sokoul.desktop.player.PlayerStatus()))
        assertEquals(true, audioActionAvailable(com.sokoul.desktop.player.PlayerStatus(audio = listOf(3 to "Français · Stéréo"), audioId = 3)))
    }

    @Test
    fun focused_toolbar_keeps_all_actions_reachable() {
        assertEquals(NavAction.LetFocusMove, PlayerNav.decide(NavMode.Controls, NavKey.Right, live = true, playing = true, toolbarNavigation = true))
        assertEquals(NavAction.LetFocusMove, PlayerNav.decide(NavMode.Controls, NavKey.Left, live = true, playing = true, toolbarNavigation = true))
    }
    @Test
    fun live_side_panels_open_without_waiting_for_controls_to_hide() {
        assertEquals(NavAction.OpenChannels, PlayerNav.decide(NavMode.Controls, NavKey.Left, live = true, playing = true))
        assertEquals(NavAction.OpenPrograms, PlayerNav.decide(NavMode.Controls, NavKey.Right, live = true, playing = true))
        assertEquals(NavAction.HideControls, PlayerNav.decide(NavMode.Controls, NavKey.Back, live = true, playing = true))
    }
    private fun film(mode: NavMode, key: NavKey, playing: Boolean = true, prompt: Boolean = false) = PlayerNav.decide(mode, key, live = false, playing = playing, prompt = prompt)
    private fun channel(key: NavKey, banner: Boolean = false) = PlayerNav.decide(NavMode.Watching, key, live = true, playing = true, banner = banner)

    @Test
    fun with_the_controls_away_keys_are_shortcuts() {
        assertEquals(NavAction.PauseAndShowControls, film(NavMode.Watching, NavKey.Ok))
        assertEquals(NavAction.SeekBack, film(NavMode.Watching, NavKey.Left))
        assertEquals(NavAction.SeekForward, film(NavMode.Watching, NavKey.Right))
        assertEquals(NavAction.OpenVersions, film(NavMode.Watching, NavKey.Up))
        assertEquals(NavAction.ShowControls, film(NavMode.Watching, NavKey.Down))
        assertEquals(NavAction.Close, film(NavMode.Watching, NavKey.Back))
    }

    @Test
    fun with_the_controls_on_screen_the_keys_belong_to_them() {
        listOf(NavKey.Ok, NavKey.Left, NavKey.Right, NavKey.Up, NavKey.Down).forEach { assertEquals(NavAction.LetFocusMove, film(NavMode.Controls, it)) }
        assertEquals(NavAction.HideControls, film(NavMode.Controls, NavKey.Back))
    }

    @Test
    fun a_panel_closes_before_the_player_does() {
        assertEquals(NavAction.ClosePanel, film(NavMode.Panel, NavKey.Back))
        assertEquals(NavAction.LetFocusMove, film(NavMode.Panel, NavKey.Down))
    }

    @Test
    fun the_play_key_works_wherever_focus_is() {
        listOf(NavMode.Watching, NavMode.Controls, NavMode.Panel).forEach { assertEquals(NavAction.TogglePause, film(it, NavKey.PlayPause)) }
    }

    @Test
    fun the_timeline_keeps_the_keys_until_it_is_confirmed_or_cancelled() {
        assertEquals(NavAction.SeekerLeft, film(NavMode.Seeking, NavKey.Left))
        assertEquals(NavAction.SeekerRight, film(NavMode.Seeking, NavKey.Right))
        assertEquals(NavAction.SeekerConfirm, film(NavMode.Seeking, NavKey.Ok))
        assertEquals("back cancels, it does not leave", NavAction.SeekerCancel, film(NavMode.Seeking, NavKey.Back))
        assertEquals(NavAction.None, film(NavMode.Seeking, NavKey.Up))
    }

    @Test
    fun a_failure_is_navigated_and_back_leaves() {
        assertEquals(NavAction.LetFocusMove, film(NavMode.Failure, NavKey.Ok))
        assertEquals(NavAction.Close, film(NavMode.Failure, NavKey.Back))
    }

    @Test
    fun an_offer_on_screen_takes_up_before_the_versions() {
        assertEquals(NavAction.FocusPrompt, film(NavMode.Watching, NavKey.Up, prompt = true))
    }

    @Test
    fun nothing_can_be_paused_or_moved_before_the_film_plays() {
        assertEquals(NavAction.None, film(NavMode.Watching, NavKey.Ok, playing = false))
        assertEquals(NavAction.None, film(NavMode.Watching, NavKey.Left, playing = false))
        assertEquals("the versions stay open to choice while loading", NavAction.OpenVersions, film(NavMode.Watching, NavKey.Up, playing = false))
    }

    @Test
    fun a_visible_video_does_not_keep_showing_a_stale_loading_line() {
        val version = PlayVersion("test", VersionOrigin.Web, "Test") { _, _ ->
            error("not opened in this test")
        }
        val phase = Phase.Opening(version, position = 1, total = 1)
        assertEquals(null, com.sokoul.pc.playback.ui.phaseLine(phase, bufferPercent = 0, videoReady = true))
        assertTrue(com.sokoul.pc.playback.ui.phaseLine(phase, bufferPercent = 0, videoReady = false) != null)
    }

    @Test
    fun a_channel_says_what_it_is_before_anything_else() {
        assertEquals(NavAction.ShowBanner, channel(NavKey.Ok))
        assertEquals(NavAction.ShowControls, channel(NavKey.Ok, banner = true))
        assertEquals(NavAction.ZapUp, channel(NavKey.Up))
        assertEquals(NavAction.ZapDown, channel(NavKey.Down))
        assertEquals(NavAction.OpenChannels, channel(NavKey.Left))
        assertEquals(NavAction.OpenPrograms, channel(NavKey.Right))
    }

    @Test
    fun replay_uses_vod_seeking_and_keeps_live_channel_zapping() {
        fun replay(key: NavKey) = PlayerNav.decide(NavMode.Watching, key, live = true, playing = true, replay = true)
        assertEquals(NavAction.SeekBack, replay(NavKey.Left))
        assertEquals(NavAction.SeekForward, replay(NavKey.Right))
        assertEquals(NavAction.ZapUp, replay(NavKey.Up))
        assertEquals(NavAction.ZapDown, replay(NavKey.Down))
    }

    @Test
    fun holding_the_key_on_the_timeline_goes_further_each_step() {
        assertEquals(10_000L, PlayerNav.seekStep(0))
        assertEquals(30_000L, PlayerNav.seekStep(5))
        assertEquals(60_000L, PlayerNav.seekStep(15))
        assertEquals(180_000L, PlayerNav.seekStep(40))
        assertTrue((1..50).map(PlayerNav::seekStep).zipWithNext().all { (a, b) -> a <= b })
    }
}
