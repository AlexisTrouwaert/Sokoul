package com.sokoul.pc.live

/** One visible-window request owner. Overlay dismissal may reuse already delivered local data. */
internal data class GuideWindowKey(val channelIds: List<String>, val window: Int, val minute: Long)

internal class GuideWindowRequests {
    private var generation = 0L
    private var delivered: GuideWindowKey? = null

    fun invalidate() { generation++ }
    fun begin(key: GuideWindowKey, reuse: Boolean = true): Long? {
        generation++
        return generation.takeUnless { reuse && delivered == key }
    }
    fun deliver(ticket: Long, key: GuideWindowKey, commit: () -> Unit): Boolean {
        if (ticket != generation) return false
        commit()
        delivered = key
        return true
    }
}
