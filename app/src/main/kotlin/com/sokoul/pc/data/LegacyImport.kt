package com.sokoul.pc.data

import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Playlist
import com.sokoul.desktop.data.PlaylistType
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.data.Settings
import org.sqlite.SQLiteConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/** Counts only: an import result must never carry a credential. */
data class ImportSummary(val playlists: Int, val keys: Int)

/**
 * Reads the IPTV playlists and API keys of the existing Sokoul PC app. The source database is copied first and only
 * the copy is opened, so the source (and its WAL) cannot change. Any failure simply leaves the fields empty.
 */
object LegacyImport {
    private const val KEY_PREFIX = "api_key_"

    fun defaultSource(): File? = System.getenv("APPDATA")?.let { File(it, "Sokoul/sokoul.db") }

    /** A hard stop during an import can leave a copy holding credentials: remove it at the next launch. */
    fun purgeLeftovers(workDir: File) {
        workDir.listFiles { f -> f.isFile && f.name.startsWith("legacy-import-") }?.forEach { it.delete() }
    }

    fun run(source: File?, target: DesktopDb, workDir: File): ImportSummary {
        if (source == null || !source.isFile) return ImportSummary(0, 0)
        val copy = File(workDir, "legacy-import-${System.nanoTime()}.db")
        val wal = File(copy.path + "-wal")
        val shm = File(copy.path + "-shm")
        return try {
            Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val sourceWal = File(source.path + "-wal")
            if (sourceWal.isFile) Files.copy(sourceWal.toPath(), wal.toPath(), StandardCopyOption.REPLACE_EXISTING)
            read(copy, target)
        } catch (_: Exception) {
            ImportSummary(0, 0)
        } finally {
            copy.delete()
            wal.delete()
            shm.delete()
        }
    }

    private fun read(copy: File, target: DesktopDb): ImportSummary {
        var playlists = 0
        var keys = 0
        DriverManager.getConnection("jdbc:sqlite:${copy.path}", SQLiteConfig().toProperties()).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name, type, url, username, password, epg_url, user_agent FROM playlists ORDER BY id").use { r ->
                    while (r.next()) {
                        val type = runCatching { PlaylistType.valueOf(r.getString(2)) }.getOrNull() ?: continue
                        val id = target.upsertPlaylist(
                            Playlist(0, r.getString(1), type, r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7)),
                        )
                        if (target.setting(Settings.ACTIVE_PLAYLIST) == null) target.putSetting(Settings.ACTIVE_PLAYLIST, id.toString())
                        playlists++
                    }
                }
                st.executeQuery("SELECT key, value FROM settings").use { r ->
                    while (r.next()) {
                        val key = r.getString(1)
                        val value = r.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                        val provider = when {
                            key.startsWith(KEY_PREFIX) -> key.removePrefix(KEY_PREFIX)
                            key == Settings.TMDB_KEY -> "tmdb"
                            else -> continue
                        }
                        if (ProviderConfig.key(target, provider) == null) {
                            ProviderConfig.setKey(target, provider, value)
                            keys++
                        }
                    }
                }
            }
        }
        return ImportSummary(playlists, keys)
    }
}
