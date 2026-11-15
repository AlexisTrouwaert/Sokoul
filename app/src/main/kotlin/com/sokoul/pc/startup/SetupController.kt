package com.sokoul.pc.startup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Playlist
import com.sokoul.desktop.data.PlaylistType
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.data.Settings
import com.sokoul.pc.data.CredentialValidator
import com.sokoul.pc.data.Verdict
import com.sokoul.pc.i18n.Text
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class SetupState { None, Done, Deferred }

/** Which screen follows the logo, and whether the catalog may be shown. */
object StartupRoute {
    const val SETTING = "pc_setup_state"
    const val IMPORT_DONE = "pc_legacy_import_done"

    fun state(db: DesktopDb): SetupState = when (db.setting(SETTING)) {
        "done" -> SetupState.Done
        "deferred" -> SetupState.Deferred
        else -> SetupState.None
    }

    fun set(db: DesktopDb, state: SetupState) = db.putSetting(
        SETTING,
        when (state) {
            SetupState.Done -> "done"
            SetupState.Deferred -> "deferred"
            SetupState.None -> null
        },
    )

    fun needsSetup(state: SetupState) = state == SetupState.None
}

const val FIELD_SERVER = "iptv_server"
const val FIELD_USERNAME = "iptv_username"
const val FIELD_PASSWORD = "iptv_password"
const val FIELD_EPG_URL = "iptv_epg_url"
const val FIELD_ANIME_BACKEND = "anime_backend_url"
const val GENERAL_ERROR = "general"

/**
 * Form state. Stored values never reach the fields: a saved credential is only ever shown as a mask, and a field
 * holds text only when the user typed it. Nothing is stored until every entered credential has been accepted.
 */
class SetupController(private val db: DesktopDb, private val validator: CredentialValidator) {
    val edits = mutableStateMapOf<String, String>()
    val errors = mutableStateMapOf<String, Text>()
    /** The active editor handles Back before the enclosing credentials screen. */
    var editingField by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var playlistChanged by mutableStateOf(false)
        private set
    var guideSourceChanged by mutableStateOf(false)
        private set
    private var animeBackendEdited = false

    private fun activePlaylist(): Playlist? {
        val all = db.playlists()
        val active = db.setting(Settings.ACTIVE_PLAYLIST)?.toLongOrNull()
        return all.firstOrNull { it.id == active } ?: all.firstOrNull()
    }

    fun hasSavedIptv(): Boolean = activePlaylist() != null

    fun hasSavedEpg(): Boolean = !activePlaylist()?.epgUrl.isNullOrBlank()

    fun keyHint(id: String): String? = ProviderConfig.key(db, id)?.let(ProviderConfig::hint)

    fun animeBackendHint(): String? = ProviderConfig.animeBackendUrl(db)?.toHttpUrlOrNull()?.host

    fun update(field: String, value: String) {
        if (field == FIELD_ANIME_BACKEND) animeBackendEdited = true
        if (value.isEmpty()) edits.remove(field) else edits[field] = value
        errors.remove(field)
        errors.remove(GENERAL_ERROR)
    }

    private fun edit(field: String) = edits[field]?.trim()?.takeIf { it.isNotEmpty() }

    /** Returns true once everything has been accepted and stored. */
    suspend fun submit(): Boolean {
        if (busy) return false
        busy = true
        errors.clear()
        playlistChanged = false
        guideSourceChanged = false
        try {
            val keyEdits = ProviderConfig.providers.mapNotNull { (id, _) -> edit(id)?.let { id to it } }.toMap()
            val animeBackendInput = edit(FIELD_ANIME_BACKEND)
            val animeBackend = animeBackendInput?.takeUnless { it.equals("off", ignoreCase = true) }
            val iptvEdited = listOf(FIELD_SERVER, FIELD_USERNAME, FIELD_PASSWORD).any { edit(it) != null }
            val epgEdited = FIELD_EPG_URL in edits
            val epgInput = edit(FIELD_EPG_URL)
            val saved = activePlaylist()
            val tmdb = keyEdits["tmdb"] ?: ProviderConfig.key(db, "tmdb")

            if (!iptvEdited && !epgEdited && saved == null && tmdb == null && animeBackend == null && animeBackendInput == null) {
                errors[GENERAL_ERROR] = Text.ErrorNothingEntered
                return false
            }

            if (animeBackendEdited && animeBackend != null && !validAnimeBackend(animeBackend)) {
                errors[FIELD_ANIME_BACKEND] = Text.ErrorAnimeBackendUrl
                return false
            }

            if (epgEdited && epgInput != null && !epgInput.equals("off", ignoreCase = true) &&
                epgInput.toHttpUrlOrNull()?.let { it.scheme == "http" || it.scheme == "https" } != true
            ) {
                errors[FIELD_EPG_URL] = Text.ErrorEpgUrl
                return false
            }
            if (epgEdited && saved == null && !iptvEdited) {
                errors[FIELD_EPG_URL] = Text.ErrorIptvIncomplete
                return false
            }

            // Saved or newly typed IPTV credentials: typed values replace saved ones field by field.
            val server = edit(FIELD_SERVER) ?: saved?.url
            val username = edit(FIELD_USERNAME) ?: saved?.username
            val password = edit(FIELD_PASSWORD) ?: saved?.password
            val checkIptv = iptvEdited || (saved?.type == PlaylistType.XTREAM && !epgEdited)
            // Saved credentials must never go to a host they were not saved for: a new server needs them typed again.
            if (saved != null && edit(FIELD_SERVER) != null && !sameHost(edit(FIELD_SERVER)!!, saved.url) &&
                (edit(FIELD_USERNAME) == null || edit(FIELD_PASSWORD) == null)
            ) {
                errors[FIELD_SERVER] = Text.ErrorIptvIncomplete
                return false
            }
            if (checkIptv) {
                if (server.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
                    errors[FIELD_SERVER] = Text.ErrorIptvIncomplete
                    return false
                }
                if (validator.iptv(server, username, password, saved?.userAgent) != Verdict.Accepted) {
                    errors[FIELD_SERVER] = Text.ErrorIptvRefused
                    return false
                }
            }

            val toCheck = buildMap {
                putAll(keyEdits)
                if (tmdb != null) put("tmdb", tmdb)
            }
            for ((id, key) in toCheck) {
                when (validator.key(id, key)) {
                    Verdict.Accepted -> Unit
                    Verdict.Refused -> errors[id] = Text.ErrorKeyRefused
                    Verdict.Unreachable -> errors[id] = Text.ErrorKeyUnreachable
                }
            }
            if (errors.isNotEmpty()) return false

            keyEdits.forEach { (id, key) -> ProviderConfig.setKey(db, id, key) }
            if (animeBackendEdited) ProviderConfig.setAnimeBackendUrl(db, animeBackend)
            val guideUrl = if (epgEdited) epgInput?.takeUnless { it.equals("off", ignoreCase = true) } else saved?.epgUrl
            if (iptvEdited) {
                val id = db.upsertPlaylist(
                    Playlist(saved?.id ?: 0, saved?.name ?: "IPTV", PlaylistType.XTREAM, server!!.trimEnd('/'), username, password, guideUrl, saved?.userAgent),
                )
                db.putSetting(Settings.ACTIVE_PLAYLIST, id.toString())
                playlistChanged = true
            } else if (epgEdited && saved != null) {
                db.upsertPlaylist(saved.copy(epgUrl = guideUrl))
            }
            guideSourceChanged = epgEdited
            StartupRoute.set(db, SetupState.Done)
            edits.clear()
            return true
        } finally {
            busy = false
        }
    }

    fun defer() = StartupRoute.set(db, SetupState.Deferred)

    private fun sameHost(a: String, b: String): Boolean {
        fun authority(url: String) = url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
        return authority(a) == authority(b)
    }

    private fun validAnimeBackend(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.host == "example.com" || url.host.endsWith(".example.com")) return false
        return url.isHttps || url.host in setOf("localhost", "127.0.0.1", "10.0.2.2") ||
            url.host.startsWith("192.168.") || url.host.startsWith("10.") ||
            (url.host.startsWith("172.") && url.host.substringAfter("172.").substringBefore('.').toIntOrNull()?.let { it in 16..31 } == true)
    }
}
