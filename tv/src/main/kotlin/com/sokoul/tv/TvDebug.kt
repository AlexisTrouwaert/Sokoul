package com.sokoul.tv

import com.sokoul.pc.AppDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/** Live checks over adb, only when the file debug-on was pushed: same tools as on Windows, read in debug-results. */
object TvDebug {
    private var guideReceiverInstalled = false

    /** Aggregate guide diagnostics for adb only; DUMP is a system/shell permission. No provider URLs or credentials. */
    private fun installGuideDiagnostics(deps: AppDeps) {
        if (guideReceiverInstalled) return
        guideReceiverInstalled = true
        val sync = com.sokoul.desktop.data.DesktopSync(deps.db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var restoreError = ""
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                try {
                    val db = deps.db
                    intent.getStringExtra("restoreUrl")?.let { address ->
                        val uri = android.net.Uri.parse(address)
                        require(uri.scheme == "http" && uri.host == "127.0.0.1")
                        scope.launch {
                            try {
                                val request = okhttp3.Request.Builder().url(address).build()
                                val source = okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                                    check(response.isSuccessful)
                                    Json.parseToJsonElement(response.body!!.string()) as JsonObject
                                }
                                fun field(key: String) = (source[key] as? JsonPrimitive)?.content?.takeUnless { it == "null" }
                                val id = db.upsertPlaylist(com.sokoul.desktop.data.Playlist(0, "IPTV", com.sokoul.desktop.data.PlaylistType.XTREAM,
                                    field("server")!!, field("username"), field("password"), field("epgUrl"), field("userAgent")))
                                db.putSetting(com.sokoul.desktop.data.Settings.ACTIVE_PLAYLIST, id.toString())
                                sync.sync(db.playlists().first { it.id == id })
                            } catch (error: Exception) { restoreError = error.javaClass.simpleName }
                        }
                    }
                    val playlists = db.playlists()
                    val active = db.setting(com.sokoul.desktop.data.Settings.ACTIVE_PLAYLIST)?.toLongOrNull()
                    val playlist = playlists.firstOrNull { it.id == active } ?: playlists.firstOrNull()
                    if (intent.getBooleanExtra("refresh", false) && playlist != null && !sync.state.value.running) {
                        scope.launch { sync.syncGuide(playlist) }
                    }
                    resultCode = 0
                    resultData = buildJsonObject {
                        val roots = listOf(context.filesDir, File(context.applicationInfo.dataDir, "databases"), context.getExternalFilesDir(null))
                        put("databaseFiles", kotlinx.serialization.json.buildJsonArray {
                            roots.filterNotNull().forEach { root ->
                                root.listFiles().orEmpty().filter { it.name.contains(".db") || it.extension == "bak" }.take(20).forEach {
                                    add(JsonPrimitive("${root.name}/${it.name}:${it.length()}"))
                                }
                            }
                        })
                        put("playlistType", JsonPrimitive(playlist?.type?.name.orEmpty()))
                        put("channels", JsonPrimitive(playlist?.let { db.channelCount(it.id) } ?: 0))
                        put("storedProgrammes", JsonPrimitive(playlist?.let { db.programCount(it.id) } ?: 0))
                        put("running", JsonPrimitive(sync.state.value.running))
                        put("imported", JsonPrimitive(sync.state.value.programs))
                        // Existing shell-only diagnostic: semantic state, never provider credentials or URLs.
                        deps.tvReport?.let { guide ->
                            put("guideGroup", JsonPrimitive(guide.group.orEmpty()))
                            put("guideChannel", JsonPrimitive(guide.channel.orEmpty()))
                            put("guideRow", JsonPrimitive(guide.row))
                            put("guideDrawer", JsonPrimitive(guide.drawerOpen))
                            put("guideFocused", JsonPrimitive(guide.focused))
                        }
                        put("previewChannelId", JsonPrimitive(deps.playback.previewKey.orEmpty()))
                        put("restoreError", JsonPrimitive(restoreError))
                        val error = sync.state.value.error
                        var detail = error.orEmpty()
                        listOfNotNull(playlist?.username, playlist?.password).filter { it.isNotBlank() }.forEach { detail = detail.replace(it, "***") }
                        detail = com.sokoul.iptv.data.net.Redact.text(detail).replace(Regex("https?://[^\\s]+"), "[provider]")
                        put("errorDetail", JsonPrimitive(detail.take(400)))
                        put("error", JsonPrimitive(when {
                            error == null -> ""
                            error.contains("Aucun programme") -> "empty_or_unmatched"
                            else -> Regex("HTTP[^0-9]*([0-9]{3})").find(error)?.groupValues?.get(1)?.let { "http_$it" } ?: "request_failed"
                        }))
                    }.toString()
                } catch (error: Exception) {
                    resultCode = 1
                    resultData = "diagnostic_error=" + error.javaClass.simpleName
                }
            }
        }
        val filter = android.content.IntentFilter("com.sokoul.tv.GUIDE_DIAGNOSTICS")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            TvApp.context.registerReceiver(receiver, filter, "android.permission.DUMP", null, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            TvApp.context.registerReceiver(receiver, filter, "android.permission.DUMP", null)
        }
    }

    fun start(deps: AppDeps) {
        installGuideDiagnostics(deps)
        val dir = TvApp.context.getExternalFilesDir(null) ?: return
        val commands = File(dir, "debug-tools.txt")
        val results = File(dir, "debug-results")
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            while (true) {
                delay(POLL_MS)
                if (!File(dir, "debug-on").isFile || !commands.isFile) continue
                // A file the system refuses for a moment must not end the checks.
                val lines = runCatching { commands.readLines() }.getOrDefault(emptyList())
                commands.delete()
                results.mkdirs()
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val (id, name, args) = line.split(' ', limit = 3).let { Triple(it[0], it[1], it.getOrElse(2) { "{}" }) }
                    val answer = deps.bridge.registry.call(name, Json.parseToJsonElement(args) as JsonObject, com.sokoul.pc.control.ToolPolicy.Everything)
                    val text = buildJsonObject {
                        when (answer) {
                            is com.sokoul.pc.control.ToolResult.Done -> put("value", answer.value)
                            is com.sokoul.pc.control.ToolResult.Failed -> put("error", JsonPrimitive(answer.message))
                            is com.sokoul.pc.control.ToolResult.Refused -> put("error", JsonPrimitive(answer.reason))
                        }
                    }.toString()
                    File(results, "$id.json").writeText(text)
                }
            }
        }
    }

    private const val POLL_MS = 400L
}
