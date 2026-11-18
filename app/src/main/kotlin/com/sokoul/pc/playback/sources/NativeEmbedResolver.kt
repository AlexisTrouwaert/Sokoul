package com.sokoul.pc.playback.sources
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.desktop.player.StreamSubtitle
import com.sokoul.extract.LocalExtractor
import okhttp3.OkHttpClient
import java.net.URI

/** Extract only after the server explicitly grants access to a selected catalogue player. */
class NativeEmbedResolver(http: OkHttpClient) : AutoCloseable {
    private val runtime = lazy { LocalExtractor(http, com.sokoul.pc.Platform.localExtractionBridge(http)) }
    private val extractor by runtime

    suspend fun resolve(embedUrl: String, title: String): StreamRequest {
        val result = extractor.extract(embedUrl)
        val url = result?.url ?: run {
            com.sokoul.pc.playback.session.StartClock.note("extract failed: ${extractor.lastError ?: "unknown"}")
            throw IllegalStateException("Local source extraction unavailable")
        }
        return StreamRequest(url, result.headers["User-Agent"], result.headers["Referer"], title,
            live = false, mediaType = if (result.isHls == true) "hls" else null,
            headers = result.headers,
            externalSubtitles = result.subtitles.map { StreamSubtitle(it.url, it.lang ?: "und", it.label, it.format ?: "vtt", it.isDefault) })
    }

    override fun close() { if (runtime.isInitialized()) extractor.close() }

    companion object {
        fun supported(url: String): Boolean = LocalExtractor.supports(url)
    }
}
