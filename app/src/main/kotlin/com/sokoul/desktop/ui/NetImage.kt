package com.sokoul.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Chargeur d'images du bureau (Compose Desktop n'en a pas) : cache mémoire borné en octets décodés (300 Mo),
 * cache disque dans `%LOCALAPPDATA%\Sokoul\images` (affiches et fonds TMDB réutilisés d'un lancement à l'autre).
 */
object ImageLoader {
    private val http = OkHttpClient.Builder().dns(com.sokoul.iptv.data.net.Ipv4FirstDns).connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val gate = Semaphore(8)
    private val MAX_BYTES = com.sokoul.pc.Platform.IMAGE_CACHE_BYTES
    private var bytes = 0L
    private val cache = LinkedHashMap<String, ImageBitmap>(256, 0.75f, true)
    /** Échecs récents (heure) : réessayés après 5 min ; une erreur réseau n'est jamais retenue. */
    private val failed = HashMap<String, Long>()
    private const val RETRY_MS = 5 * 60_000L

    /** Remplaçable par les rendus sans fenêtre (images d'exemple locales). */
    @Volatile var dir: File? = runCatching {
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "Sokoul/images").apply { mkdirs() }
    }.getOrNull()

    /** The widths an image is decoded at: a card asks for the next step up, so that one decoding serves many cards. */
    fun bucket(widthPx: Int): Int = when {
        widthPx <= 0 -> 0
        widthPx <= 256 -> 256
        widthPx <= 512 -> 512
        widthPx <= 1024 -> 1024
        else -> 0
    }

    private fun key(url: String, width: Int) = if (width <= 0) url else "$width|$url"

    fun cached(url: String, width: Int = 0): ImageBitmap? = synchronized(cache) { cache[key(url, width)] }

    /** Any decoding of it already there: shown at once while the right size is being made, so nothing flashes. */
    fun cachedAny(url: String): ImageBitmap? = synchronized(cache) { cache[url] ?: BUCKETS.firstNotNullOfOrNull { cache["$it|$url"] } }

    private val BUCKETS = listOf(1024, 512, 256)

    private fun put(url: String, img: ImageBitmap) = synchronized(cache) {
        cache.put(url, img)?.let { bytes -= weight(it) }
        bytes += weight(img)
        val it = cache.entries.iterator()
        while (bytes > MAX_BYTES && it.hasNext()) { val e = it.next(); if (e.key == url) continue; bytes -= weight(e.value); it.remove() }
    }

    /**
     * Gives memory back when the system asks (onTrimMemory): [keep] of the budget stays, the least recently shown images
     * go first. 0 empties the cache, as when the app goes to the background on a small TV stick.
     */
    fun trim(keep: Float) = synchronized(cache) {
        val limit = (MAX_BYTES * keep.coerceIn(0f, 1f)).toLong()
        val it = cache.entries.iterator()
        while (bytes > limit && it.hasNext()) { val e = it.next(); bytes -= weight(e.value); it.remove() }
    }

    private fun weight(i: ImageBitmap) = i.width.toLong() * i.height * (if (i.config == androidx.compose.ui.graphics.ImageBitmapConfig.Rgb565) 2 else 4)

    private fun fileFor(url: String): File? = dir?.let { d ->
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        File(d, "$h.img")
    }

    /** [width]: the width it is shown at, in pixels (0: full size). On the TV, the image is decoded at that size. */
    suspend fun load(url: String, width: Int = 0): ImageBitmap? {
        val w = bucket(width)
        val k = key(url, w)
        cached(url, w)?.let { return it }
        if (synchronized(failed) { failed[url]?.let { System.currentTimeMillis() - it < RETRY_MS } == true }) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                cached(url, w)?.let { return@withContext it }
                var offline = false
                runCatching {
                    val file = fileFor(url)
                    val data = if (url.startsWith("file:")) File(java.net.URI(url)).readBytes()
                    else file?.takeIf { it.isFile && it.length() > 0 }?.readBytes() ?: http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        r.body?.bytes()?.takeIf { r.isSuccessful && it.size < 8_000_000 }?.also { b -> runCatching { file?.writeBytes(b) } }
                    }
                    data?.let { com.sokoul.pc.Platform.decodeImage(it, w) }
                }.onFailure { offline = it is java.io.IOException }.getOrNull().also { img ->
                    if (img != null) put(k, img) else if (!offline) synchronized(failed) { failed[url] = System.currentTimeMillis() }
                }
            }
        }
    }
}
