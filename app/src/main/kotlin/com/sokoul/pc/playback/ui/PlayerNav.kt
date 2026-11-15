package com.sokoul.pc.playback.ui
import com.sokoul.desktop.player.PlayerStatus

/** Audio remains an explicit player action even when the active source exposes only one track. */
fun audioActionAvailable(status: PlayerStatus?): Boolean = status?.audio?.isNotEmpty() == true

/** What the player is doing with the remote at this moment. There is only ever one. */
enum class NavMode { Watching, Controls, Panel, Seeking, Failure }

enum class NavKey { Ok, Left, Right, Up, Down, Back, PlayPause }

enum class NavAction {
    /** The key goes on to whatever has focus. */
    LetFocusMove,
    /** Nothing happens, and the key stops here. */
    None,
    PauseAndShowControls,
    TogglePause,
    SeekBack,
    SeekForward,
    OpenVersions,
    ShowControls,
    FocusPrompt,
    ZapUp,
    ZapDown,
    OpenChannels,
    OpenPrograms,
    OpenOptions,
    ShowBanner,
    Close,
    HideControls,
    ClosePanel,
    SeekerLeft,
    SeekerRight,
    SeekerConfirm,
    SeekerCancel,
}

/**
 * The one place that decides what a key does on the player. Two levels: with the controls away, keys are immediate
 * shortcuts; with them on screen, the arrows move between them and OK presses the one that has focus. Pure and tested.
 */
object PlayerNav {
    fun decide(mode: NavMode, key: NavKey, live: Boolean, playing: Boolean, banner: Boolean = false, prompt: Boolean = false, replay: Boolean = false, toolbarNavigation: Boolean = false): NavAction = when (mode) {
        NavMode.Failure -> if (key == NavKey.Back) NavAction.Close else if (key == NavKey.PlayPause) NavAction.None else NavAction.LetFocusMove
        NavMode.Panel -> when (key) {
            NavKey.Back -> NavAction.ClosePanel
            NavKey.PlayPause -> if (playing) NavAction.TogglePause else NavAction.None
            else -> NavAction.LetFocusMove
        }
        NavMode.Seeking -> when (key) {
            NavKey.Left -> NavAction.SeekerLeft
            NavKey.Right -> NavAction.SeekerRight
            NavKey.Ok -> NavAction.SeekerConfirm
            NavKey.Back -> NavAction.SeekerCancel
            else -> NavAction.None
        }
        NavMode.Controls -> when (key) {
            NavKey.Back -> NavAction.HideControls
            NavKey.Left -> if (live && !replay && !toolbarNavigation) NavAction.OpenChannels else NavAction.LetFocusMove
            NavKey.Right -> if (live && !replay && !toolbarNavigation) NavAction.OpenPrograms else NavAction.LetFocusMove
            NavKey.Down -> if (live && !replay && !toolbarNavigation) NavAction.OpenOptions else NavAction.LetFocusMove
            NavKey.PlayPause -> if (playing) NavAction.TogglePause else NavAction.None
            else -> NavAction.LetFocusMove
        }
        NavMode.Watching -> when {
            live && replay -> watchingReplay(key, playing, banner)
            live -> watchingLive(key, playing, banner)
            else -> watchingFilm(key, playing, prompt)
        }
    }

    private fun watchingFilm(key: NavKey, playing: Boolean, prompt: Boolean) = when (key) {
        NavKey.Back -> NavAction.Close
        NavKey.Up -> if (prompt) NavAction.FocusPrompt else NavAction.OpenVersions
        NavKey.Ok -> if (playing) NavAction.PauseAndShowControls else NavAction.None
        NavKey.Down -> if (playing) NavAction.ShowControls else NavAction.None
        NavKey.Left -> if (playing) NavAction.SeekBack else NavAction.None
        NavKey.Right -> if (playing) NavAction.SeekForward else NavAction.None
        NavKey.PlayPause -> if (playing) NavAction.TogglePause else NavAction.None
    }

    private fun watchingLive(key: NavKey, playing: Boolean, banner: Boolean) = when (key) {
        NavKey.Back -> NavAction.Close
        NavKey.Up -> NavAction.ZapUp
        NavKey.Down -> NavAction.ZapDown
        NavKey.Left -> NavAction.OpenChannels
        NavKey.Right -> NavAction.OpenPrograms
        // First what is on, then the controls: pausing a channel by accident is worse than one more press.
        NavKey.Ok -> if (banner && playing) NavAction.ShowControls else NavAction.ShowBanner
        NavKey.PlayPause -> if (playing) NavAction.TogglePause else NavAction.None
    }

    private fun watchingReplay(key: NavKey, playing: Boolean, banner: Boolean) = when (key) {
        NavKey.Back -> NavAction.Close
        NavKey.Up -> NavAction.ZapUp
        NavKey.Down -> NavAction.ZapDown
        NavKey.Left -> if (playing) NavAction.SeekBack else NavAction.None
        NavKey.Right -> if (playing) NavAction.SeekForward else NavAction.None
        NavKey.Ok -> if (banner && playing) NavAction.ShowControls else NavAction.ShowBanner
        NavKey.PlayPause -> if (playing) NavAction.TogglePause else NavAction.None
    }

    /** The longer the key stays down on the timeline, the further each step goes. */
    fun seekStep(streak: Int): Long = when {
        streak < STREAK_30 -> STEP_10
        streak < STREAK_60 -> STEP_30
        streak < STREAK_180 -> STEP_60
        else -> STEP_180
    }

    private const val STREAK_30 = 4
    private const val STREAK_60 = 12
    private const val STREAK_180 = 24
    private const val STEP_10 = 10_000L
    private const val STEP_30 = 30_000L
    private const val STEP_60 = 60_000L
    private const val STEP_180 = 180_000L
}
