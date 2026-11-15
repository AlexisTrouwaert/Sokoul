package com.sokoul.iptv.data.debrid

import java.io.IOException

// Modèles Real-Debrid, communs à l'app Android (`RealDebridClient`) et à l'app PC.

data class RdUser(val username: String, val premium: Boolean, val expiresAtMs: Long) {
    fun daysLeft(now: Long): Int = if (!premium) 0 else ((expiresAtMs - now) / 86_400_000L).toInt().coerceAtLeast(0)
}

data class RdFile(val id: Int, val path: String, val bytes: Long, val selected: Boolean) {
    val name get() = path.substringAfterLast('/')
}

data class RdTorrent(
    val id: String, val filename: String, val hash: String = "", val status: String, val progress: Int,
    val bytes: Long, val files: List<RdFile>, val links: List<String>, val addedAt: Long,
    /** While downloading: bytes per second and connected peers. */
    val speedBps: Long = 0, val seeders: Int = 0,
) {
    val ready get() = status == "downloaded"
}

data class RdLink(val url: String, val filename: String, val bytes: Long, val mime: String?)

/** [apiCode] is Real-Debrid's own `error_code` (8 bad token, 21 too many downloads, 23 and 36 traffic limits...). */
class RdException(val code: Int, message: String, val apiCode: Int = 0) : IOException(message)
