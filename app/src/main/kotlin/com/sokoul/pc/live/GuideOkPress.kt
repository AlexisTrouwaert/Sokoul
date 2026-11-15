package com.sokoul.pc.live

/** Remote repeats are one gesture; only release may perform the short action. */
internal class GuideOkPress {
    private var channel: String? = null
    private var held = false
    private var long = false
    fun down(channelId: String): Boolean {
        if (held) return false
        held = true; long = false; channel = channelId
        return true
    }
    fun expire(channelId: String): Boolean {
        if (!held || long || channel != channelId) return false
        long = true
        return true
    }
    fun up(): String? {
        val result = channel?.takeIf { held && !long }
        cancel()
        return result
    }
    fun cancel() { held = false; long = false; channel = null }
}
