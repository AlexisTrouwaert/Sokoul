package com.sokoul.pc.playback.session
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.sokoul.desktop.player.DesktopPlayer
import com.sokoul.pc.party.PartyMedia
import com.sokoul.pc.party.PartyState
import com.sokoul.pc.party.PartySync
import com.sokoul.pc.party.WatchParty
import kotlinx.coroutines.delay

private const val ECHO_MS = 1_500L
private const val HEARTBEAT_MS = 5_000L

/**
 * Keeps the local player and the watch party room on the same moment: the room's changes are applied here, and what
 * the viewer does here is told to the room. Changes this effect made itself are not sent back (no echo).
 */
@Composable
internal fun PartySyncEffect(party: WatchParty, player: DesktopPlayer, session: PlaybackSession) {
    val inRoom = party.state as? PartyState.In ?: return
    val status by player.status.collectAsState()
    // Mutable holders, not state: they only guard against echoes and must not cause redraws.
    val quietUntil = remember { longArrayOf(0L) }
    val last = remember { doubleArrayOf(-1.0, 0.0, 0.0) } // playing (1/0), seconds, sampled at (ms)
    val manualRate = remember(party, inRoom.code) { player.status.value.rate }
    DisposableEffect(party, inRoom.code) {
        onDispose { player.setRate(manualRate) }
    }

    // Room to player.
    LaunchedEffect(party, inRoom.code, party.syncMode) {
        if (party.syncMode == "classic") player.setRate(manualRate)
        var lastProbe = 0L
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { while (true) {
            val st = player.status.value
            val now = System.currentTimeMillis()
            if (lastProbe == 0L || now - lastProbe >= 2_000L) {
                party.probeClock()
                lastProbe = now
            }
            if (st.lengthMs > 0 && !st.buffering) {
                val action = party.follow(!st.paused, st.timeMs / 1000.0, now)
                if (!action.none) {
                    quietUntil[0] = now + ECHO_MS
                    action.seekToSec?.let { player.seekTo((it * 1000 / st.lengthMs).toFloat().coerceIn(0f, 1f)) }
                    action.setPlaying?.let { wanted -> if (st.paused == wanted) player.togglePause() }
                    action.playbackRate?.let { player.setRate(it.toFloat()) }
                }
            }
            delay(250)
        } }
    }

    // Player to room: play, pause and jumps, as they happen.
    LaunchedEffect(status.paused, status.timeMs / 1000) {
        val st = player.status.value
        if (st.lengthMs <= 0) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val playing = !st.paused
        val sec = st.timeMs / 1000.0
        if (last[0] >= 0) {
            val reason = PartySync.localChange(last[0] == 1.0, last[1], last[2].toLong(), playing, sec, now)
            if (reason != null && now > quietUntil[0]) party.tell(playing, sec, reason)
        }
        last[0] = if (playing) 1.0 else 0.0
        last[1] = sec
        last[2] = now.toDouble()
    }

    // The host's heartbeat lets late or drifting guests catch up.
    if (inRoom.host) {
        LaunchedEffect(inRoom) {
            while (true) {
                delay(HEARTBEAT_MS)
                val st = player.status.value
                if (st.lengthMs > 0) party.tell(!st.paused, st.timeMs / 1000.0, "heartbeat")
            }
        }
        // Next episode or another title: the room follows the host.
        LaunchedEffect(session.request) { session.request?.let { party.changeMedia(PartyMedia.of(it)) } }
    }
}
