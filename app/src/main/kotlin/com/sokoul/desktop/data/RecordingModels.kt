package com.sokoul.desktop.data

import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

data class RecordingStorage(val id: String, val label: String, val directory: File, val removable: Boolean = false) {
    val availableBytes: Long get() = directory.usableSpace
}

enum class RecordingRepeat { NONE, DAILY, WEEKDAYS, WEEKLY }

@Serializable
data class RecordingRule(
    val id: String,
    val playlistId: Long,
    val channelId: String,
    val title: String,
    val anchorStart: Long,
    val durationMs: Long,
    val repeat: RecordingRepeat,
    val zoneId: String = ZoneId.systemDefault().id,
    val lastScheduledStart: Long = anchorStart,
) {
    /** Calendar arithmetic preserves the local clock time across DST changes. */
    fun nextStart(after: Long = lastScheduledStart): Long {
        var next = Instant.ofEpochMilli(after).atZone(ZoneId.of(zoneId))
        next = if (repeat == RecordingRepeat.WEEKLY) next.plusWeeks(1) else next.plusDays(1)
        if (repeat == RecordingRepeat.WEEKDAYS) while (next.dayOfWeek.value > 5) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
}

data class RecordingProgress(val id: Long, val bytes: Long, val startedAt: Long, val endAt: Long, val bytesPerSecond: Long) {
    fun estimatedBytesUntilEnd(now: Long): Long = bytes + bytesPerSecond * ((endAt - now).coerceAtLeast(0) / 1000)
}
