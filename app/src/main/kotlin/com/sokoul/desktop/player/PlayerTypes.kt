package com.sokoul.desktop.player

/** Ce que l'on lit : lien du flux et en-têtes exigés par certains fournisseurs ; [live] = direct (pas de barre de temps). */
data class StreamRequest(
    val url: String,
    val userAgent: String?,
    val referrer: String?,
    val title: String,
    val live: Boolean = true,
    val startMs: Long = 0,
    /** Separate audio stream (high-quality YouTube trailers). */
    val audioUrl: String? = null,
    /** Number of this opening, repeated in the status: a status with another number describes another stream. */
    val session: Long = 0,
    /** Explicit media type for a signed relay whose path does not identify its container. */
    val mediaType: String? = null,
    /** Source-provided external tracks, separate from the optional subtitle search service. */
    val externalSubtitles: List<StreamSubtitle> = emptyList(),
    /** Local extractor context; never persisted or logged. */
    val headers: Map<String, String> = emptyMap(),
    /** The work's original language (ISO 639-1) from the catalog, to pick the original track; null when unknown. */
    val originalLanguage: String? = null,
)

data class StreamSubtitle(val url: String, val language: String = "und", val label: String? = null, val format: String = "vtt", val default: Boolean = false)

/** What libVLC measures on the stream being read. */
data class StreamSample(
    val session: Long,
    val width: Int,
    val height: Int,
    val framesPerSecond: Float?,
    val videoCodec: String?,
    val audioCodec: String?,
    val audioHz: Int?,
    val audioChannels: Int?,
    /** From the bytes really read since the last measure. */
    val kilobitsPerSecond: Int?,
    val displayed: Int,
    val lost: Int,
    val corrupted: Int,
    val discontinuities: Int,
)

data class PlayerStatus(
    val title: String = "",
    val playing: Boolean = false,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val audio: List<Pair<Int, String>> = emptyList(),
    val audioId: Int = -1,
    val subtitles: List<Pair<Int, String>> = emptyList(),
    val subtitleId: Int = -1,
    val video: String? = null,
    val timeMs: Long = 0,
    val lengthMs: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val rate: Float = 1f,
    val subtitleDelayMs: Long = 0,
    /** Direct : retard accumulé par les pauses (différé), 0 = au direct. */
    val liveDelayMs: Long = 0,
    val session: Long = 0,
    /** Filling of the buffer before the picture moves, from 0 to 100. */
    val bufferPercent: Int = 0,
    /** The stream stopped by itself. Only its position tells whether that is the end of the film. */
    val ended: Boolean = false,
) {
    /** Santé du flux en un mot, comme la tablette. */
    val health: String get() = when {
        error != null -> "Flux interrompu"
        buffering -> "Flux fragile"
        playing -> "Flux stable"
        else -> "Connexion…"
    }
}

/** Format d'image : ajusté (bandes noires), rempli (rognée), ou rapport forcé. */
enum class VideoAspect(val label: String, val ratio: Float?) { FIT("Ajusté", null), FILL("Remplir", null), WIDE("16:9", 16f / 9f), CLASSIC("4:3", 4f / 3f) }

