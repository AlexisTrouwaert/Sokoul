package com.sokoul.pc.live

import com.sokoul.desktop.data.Program

/** How much the guide shows: the picture large with few hours, or everything with the picture reduced. */
enum class GuideLevel { Panorama, Guide }

/** The rules of the guide, without any drawing. Pure and tested. */
object GuideLogic {
    /** The programme on at [t], or the first one after it. */
    fun programAt(list: List<Program>, t: Long): Program? =
        list.firstOrNull { it.start <= t && it.end > t } ?: list.firstOrNull { it.start > t }

    fun next(list: List<Program>, current: Program): Program? = list.firstOrNull { it.start >= current.end }

    /** The programme before, unless it is over: there is nothing to watch back there, and Left opens the drawer instead. */
    fun previous(list: List<Program>, current: Program, now: Long): Program? =
        list.lastOrNull { it.end <= current.start }?.takeIf { it.end > now }

    /** Fixed row geometry means moving focus cannot move any programme or channel. */
    fun rowHeight(height: Float): Float = height
    fun rowTop(i: Int, height: Float): Float = i * height

    fun rowAt(y: Float, count: Int, height: Float): Int? {
        if (y < 0 || count == 0 || height <= 0f) return null
        return (y / height).toInt().takeIf { it in 0 until count }
    }

    /** Keep focus and one row of context visible; scroll only as focus nears a viewport edge. */
    fun scrollFor(row: Int, count: Int, rowHeight: Float, viewport: Float, current: Float): Float {
        if (count <= 0 || rowHeight <= 0f || viewport <= 0f) return 0f
        val maxScroll = (count * rowHeight - viewport).coerceAtLeast(0f)
        val top = row * rowHeight
        val bottom = top + rowHeight
        val margin = minOf(rowHeight, viewport / 3f)
        val target = when {
            top < current + margin && current > 0f -> top - margin
            bottom > current + viewport - margin -> bottom - viewport + margin
            else -> current
        }
        return target.coerceIn(0f, maxScroll)
    }

    /** Where the left edge of the time axis should be for this programme to be seen. It never goes far into the past. */
    fun leftEdgeFor(start: Long, end: Long, current: Long, now: Long, visibleMs: Long): Long {
        val earliest = now - PAST_SHOWN_MS
        val edge = when {
            start < current -> start - MARGIN_MS
            minOf(end, start + visibleMs - MARGIN_MS) > current + visibleMs -> start - MARGIN_MS
            else -> current
        }
        return edge.coerceAtLeast(earliest)
    }

    /** Typing filters the groups: "fr spo" finds "EU | FRANCE SPORTS". */
    fun matches(name: String, query: String): Boolean {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val text = name.lowercase()
        return words.all { it in text }
    }

    /** Where a jump in time lands: the first programme to begin from then on, not the end of the one still on. */
    fun jumpTarget(list: List<Program>, t: Long): Long = list.firstOrNull { it.start >= t }?.start ?: list.lastOrNull()?.start ?: t

    /**
     * Tonight: nine o'clock until then, late evening once that has begun. Only past that does it mean the next evening,
     * or "tonight" and "tomorrow" would be the same button for most of the evening.
     */
    fun tonight(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val prime = day.atTime(PRIME_TIME_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val late = day.atTime(LATE_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return when {
            now < prime -> prime
            now < late -> late
            else -> day.plusDays(1).atTime(PRIME_TIME_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        }
    }

    fun tomorrowEvening(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
        java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(PRIME_TIME_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    private const val PRIME_TIME_HOUR = 21
    private const val LATE_HOUR = 23
    const val PAST_SHOWN_MS = 20 * 60_000L
    private const val MARGIN_MS = 10 * 60_000L
}
