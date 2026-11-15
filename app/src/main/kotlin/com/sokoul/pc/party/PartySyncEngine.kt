package com.sokoul.pc.party

import kotlin.math.abs

/**
 * Native counterpart of Movix wasm/watchparty-sync (CC BY-NC 4.0).
 * Clock probes, bounded rate correction and delayed commands require no WASM or player dependencies.
 */
class PartySyncEngine {
    private val offsets = ArrayDeque<Double>()
    private var master: PartyPlayback? = null
    private var lastSeek = Long.MIN_VALUE
    private var lastToggle = Long.MIN_VALUE
    private var lastRate = 1.0
    @Volatile var pro: Boolean = false
        private set
    val calibrated: Boolean get() = synchronized(this) { offsets.size >= 3 }
    val offsetMs: Double get() = synchronized(this) { if (offsets.isEmpty()) 0.0 else offsets.average() }

    @Synchronized fun setMode(mode: String) {
        if (mode !in setOf("classic", "pro") || pro == (mode == "pro")) return
        pro = mode == "pro"
        lastRate = 1.0
    }

    /** NTP's four timestamps exclude processing time; long or malformed probes never influence playback. */
    @Synchronized fun probe(clientSent: Long, serverReceived: Long, serverSent: Long, clientReceived: Long): Boolean {
        val trip = clientReceived - clientSent
        val processing = serverSent - serverReceived
        if (trip !in 0..5_000 || processing !in 0..trip) return false
        val offset = ((serverReceived.toDouble() - clientSent) + (serverSent.toDouble() - clientReceived)) / 2
        if (!offset.isFinite()) return false
        offsets.addLast(offset)
        while (offsets.size > 5) offsets.removeFirst()
        return true
    }

    @Synchronized fun ingest(room: PartyPlayback): Boolean {
        if (!room.positionSec.isFinite() || room.positionSec < 0) return false
        val old = master
        if (room.serverAtMs != null && old?.serverAtMs != null && room.serverAtMs < old.serverAtMs) return false
        master = room
        return true
    }

    @Synchronized fun schedule(action: String, position: Double, scheduledAt: Long, serverNow: Long, heardAt: Long): Boolean {
        if (!pro || action !in setOf("play", "pause", "seek") || scheduledAt - serverNow !in 0..5_000) return false
        val playing = when (action) { "pause" -> false; "play" -> true; else -> master?.playing ?: false }
        return ingest(PartyPlayback(playing, position, heardAt, scheduledAt, heardAt + (scheduledAt - serverNow)))
    }

    @Synchronized fun tick(localPlaying: Boolean, localSec: Double, nowMs: Long): SyncAction {
        val room = master ?: return SyncAction(null, null)
        if (!localSec.isFinite() || localSec < 0) return SyncAction(null, null)
        val deadline = if (calibrated && room.serverAtMs != null) (room.serverAtMs - offsetMs).toLong() else room.applyAtMs
        if (deadline != null && nowMs < deadline) return SyncAction(null, null)
        val adjusted = room.copy(clockOffsetMs = if (calibrated) offsetMs else null)
        // Servers without probes or Pro support retain the established classic behavior.
        if (!pro || !calibrated) {
            val classic = PartySync.follow(adjusted, localPlaying, localSec, nowMs)
            val playing = classic.setPlaying?.takeIf { lastToggle == Long.MIN_VALUE || nowMs - lastToggle >= 600 }
            val seek = classic.seekToSec?.takeIf { lastSeek == Long.MIN_VALUE || nowMs - lastSeek >= 1_200 }
            if (playing != null) lastToggle = nowMs
            if (seek != null) lastSeek = nowMs
            return SyncAction(playing, seek)
        }
        val target = adjusted.positionAt(nowMs)
        val drift = target - localSec
        val toggle = room.playing.takeIf { it != localPlaying && (lastToggle == Long.MIN_VALUE || nowMs - lastToggle >= 600) }
        if (toggle != null) lastToggle = nowMs
        if (abs(drift) >= 1 && (lastSeek == Long.MIN_VALUE || nowMs - lastSeek >= 1_200)) {
            lastSeek = nowMs
            lastRate = 1.0
            return SyncAction(toggle, target, 1.0)
        }
        val desired = if (!room.playing || !localPlaying || abs(drift) < .08) 1.0 else (1.0 + drift * .08).coerceIn(.95, 1.05)
        val rate = desired.takeIf { abs(it - lastRate) >= .005 }
        if (rate != null) lastRate = rate
        return SyncAction(toggle, null, rate)
    }

    @Synchronized fun resetMedia() {
        master = null
        lastSeek = Long.MIN_VALUE
        lastToggle = Long.MIN_VALUE
        lastRate = 1.0
    }

    @Synchronized fun reset() {
        resetMedia()
        offsets.clear()
        pro = false
    }
}
