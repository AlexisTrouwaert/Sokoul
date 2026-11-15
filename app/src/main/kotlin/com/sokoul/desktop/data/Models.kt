package com.sokoul.desktop.data

/** Playlist IPTV (même modèle que l'app Android : Xtream Codes ou M3U). */
data class Playlist(
    val id: Long,
    val name: String,
    val type: PlaylistType,
    val url: String,
    val username: String?,
    val password: String?,
    val epgUrl: String?,
    val userAgent: String?,
    val syncedAt: Long = 0,
)

enum class PlaylistType { XTREAM, M3U }

data class Category(val playlistId: Long, val id: String, val name: String, val position: Int, val hidden: Boolean = false, val locked: Boolean = false)

data class Channel(
    val playlistId: Long,
    val id: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    /** Identifiant du guide (tvg-id / epg_channel_id), en minuscules. */
    val epgKey: String?,
    /** Nom normalisé pour rapprocher la chaîne du guide quand l'identifiant manque. */
    val nameKey: String,
    /** Lien direct (M3U) ; null pour Xtream (construit à la lecture). */
    val url: String?,
    val userAgent: String?,
    val referrer: String?,
    val position: Int,
    /** M3U catch-up mode, or `xc` for an Xtream channel advertising an archive. */
    val catchupType: String? = null,
    /** M3U catch-up URL template. */
    val catchupSource: String? = null,
    /** Provider-advertised replay retention in days; zero means live-only. */
    val catchupDays: Int = 0,
)

private val ESCAPED_SPACES = Regex("(\\\\[trn])+")
private val SPACES = Regex("\\s+")

/**
 * A programme description as people read it: some guides send the escapes themselves (a backslash then "t", "r" or
 * "n", as text), which showed as `\t\t\t\r\n` in the middle of a sentence.
 */
fun tidyDescription(text: String?): String? =
    text?.replace(ESCAPED_SPACES, " ")?.replace(SPACES, " ")?.trim()?.takeIf { it.isNotEmpty() }

/** A guide entry. [icon] is the programme's picture from the guide (https only), null when the guide gives none. */
data class Program(val channelKey: String, val start: Long, val end: Long, val title: String, val description: String?, val icon: String? = null)

data class Movie(
    val playlistId: Long,
    val id: String,
    val name: String,
    val year: Int?,
    val poster: String?,
    val rating: Double?,
    val categoryId: String?,
    val extension: String?,
    val added: Long?,
    val tmdbId: String?,
    val streamUrl: String? = null,
)

data class Series(
    val playlistId: Long,
    val id: String,
    val name: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val rating: Double?,
    val categoryId: String?,
    val lastModified: Long?,
    val tmdbId: String?,
)

/** Position de lecture d'un film ou d'un épisode TMDB ; [kind] « movie » ou « tv ». */
data class Resume(
    val key: String,
    val kind: String,
    val tmdbId: Int,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val finished: Boolean,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    companion object {
        /** Au-delà de 92 % : vu (générique de fin). */
        const val FINISHED_AT = 0.92f
        fun key(kind: String, tmdbId: Int, season: Int?, episode: Int?) =
            if (kind == "tv" && season != null && episode != null) "tv:$tmdbId:$season:$episode" else "$kind:$tmdbId"
    }
}

/** Groupes spéciaux en tête de la liste (comme sur la tablette). */
object SpecialGroup {
    const val ALL = "__all"
    const val FAVORITES = "__fav"
}
