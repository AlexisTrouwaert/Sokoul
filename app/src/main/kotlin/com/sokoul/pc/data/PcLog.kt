package com.sokoul.pc.data

import com.sokoul.desktop.data.AppDirs
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.iptv.data.net.Redact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalTime

/**
 * Timing notes for live checks, only when SOKOUL_DEBUG is set. Callers pass timings and counts, never a URL or a
 * credential, and the text still goes through [Redact].
 */
object PcLog {
    private val enabled = !System.getenv("SOKOUL_DEBUG").isNullOrBlank()
    private val file by lazy { File(AppDirs.data, "debug.log") }

    fun event(text: String) {
        if (!enabled) return
        runCatching { synchronized(this) { file.appendText("${LocalTime.now()} ${Redact.text(text)}\n") } }
    }
}

/** Keeps resolved trailer links across restarts, in the settings table. */
class DbTrailerStore(private val db: DesktopDb) : TrailerStore {
    override fun get(key: String): Pair<Long, TrailerStream>? {
        val raw = db.setting(PREFIX + key) ?: return null
        val o = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val at = o["at"]?.jsonPrimitive?.long ?: return null
        val video = o["video"]?.jsonPrimitive?.contentOrNull ?: return null
        return at to TrailerStream(
            video, o["audio"]?.jsonPrimitive?.contentOrNull, o["method"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["height"]?.jsonPrimitive?.intOrNull, o["key"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["width"]?.jsonPrimitive?.intOrNull,
        )
    }

    override fun put(key: String, at: Long, stream: TrailerStream) {
        db.putSetting(
            PREFIX + key,
            buildJsonObject {
                put("at", at)
                put("video", stream.videoUrl)
                put("audio", stream.audioUrl)
                put("method", stream.method)
                put("height", stream.height)
                put("key", stream.key)
                put("width", stream.width)
            }.toString(),
        )
    }

    private companion object {
        const val PREFIX = "pc_trailer:"
    }
}
