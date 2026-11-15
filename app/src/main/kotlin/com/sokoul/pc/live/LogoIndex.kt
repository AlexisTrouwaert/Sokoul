package com.sokoul.pc.live

import com.sokoul.desktop.data.AppDirs
import com.sokoul.iptv.parser.TitleTagger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
internal data class IoChannel(
    val id: String,
    val name: String,
    val alt_names: List<String> = emptyList(),
    val country: String? = null,
    val closed: String? = null,
    val is_nsfw: Boolean = false,
)

@Serializable
internal data class IoLogo(
    val channel: String,
    val feed: String? = null,
    val format: String? = null,
    val width: Int? = null,
    val url: String,
)

/**
 * Channel logos from the public iptv-org base (free, no key), ported from the Android app (ChannelDbClient). The two
 * files (~14 MB) are read at most once a month, in the background, and kept as a small index: guide id or normalised
 * name → logo. Nothing is sent: two plain GETs of public files, over https.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object LogoIndex {
    const val BASE = "https://iptv-org.github.io/api"
    private const val MAX_AGE_MS = 30L * 24 * 3600_000
    private const val FILE = "iptvorg-logos.json"

    /** Countries kept, most wanted first when two channels share a name ("TF1": the French one). */
    private val COUNTRIES = listOf("FR", "BE", "CH", "LU", "MC", "CA", "MA", "DZ", "TN", "SN", "CI", "UK", "GB", "US", "DE", "ES", "IT", "PT", "NL")

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    @Volatile private var memory: Map<String, String>? = null
    private val http by lazy { OkHttpClient.Builder().dns(com.sokoul.iptv.data.net.Ipv4FirstDns).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    internal fun buildIndex(channels: List<IoChannel>, logos: List<IoLogo>): Map<String, String> {
        // One logo per channel: PNG or JPEG (SVG does not decode), the main one (no "feed"), the widest.
        val bestLogo = logos.asSequence()
            .filter { it.url.startsWith("https://") && it.format?.uppercase() != "SVG" && !it.url.endsWith(".svg", true) }
            .groupBy { it.channel }
            .mapValues { (_, l) -> l.sortedWith(compareBy<IoLogo> { it.feed != null }.thenByDescending { it.width ?: 0 }).first().url }
        val out = HashMap<String, String>()
        val rank = HashMap<String, Int>()
        channels.asSequence()
            .filter { !it.is_nsfw && it.closed == null && it.country in COUNTRIES }
            .forEach { c ->
                val logo = bestLogo[c.id] ?: return@forEach
                val r = COUNTRIES.indexOf(c.country)
                out["id:" + c.id.lowercase()] = logo
                (listOf(c.name) + c.alt_names).map(TitleTagger::channelKey).filter { it.length >= 2 }.forEach { k ->
                    if (r < (rank["n:$k"] ?: Int.MAX_VALUE)) {
                        out["n:$k"] = logo
                        rank["n:$k"] = r
                    }
                }
            }
        return out
    }

    /** The guide id first ("tf1.fr"), else the normalised name ("tf1"). */
    fun lookup(index: Map<String, String>, guideId: String?, nameKey: String): String? =
        guideId?.lowercase()?.let { index["id:$it"] } ?: nameKey.takeIf { it.length >= 2 }?.let { index["n:$it"] }

    /** The local index, downloaded when missing or older than a month (else kept as it is, even when stale). */
    suspend fun index(): Map<String, String>? = lock.withLock {
        memory?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val f = File(AppDirs.data, FILE)
            val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
            if (!fresh) {
                try {
                    val idx = download()
                    // Written aside then renamed: an interrupted write never leaves a "fresh" but unreadable index.
                    val tmp = File(f.parentFile, "$FILE.tmp")
                    tmp.writeText(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), idx))
                    if (!tmp.renameTo(f)) {
                        f.delete()
                        tmp.renameTo(f)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Offline or the base moved: the old index, if any, still serves.
                }
            }
            if (!f.isFile) return@withContext null
            runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), f.readText()) }.getOrNull()?.also { memory = it }
        }
    }

    private fun download(): Map<String, String> {
        val channels = fetch("$BASE/channels.json") { json.decodeFromStream(ListSerializer(IoChannel.serializer()), it) }
        val logos = fetch("$BASE/logos.json") { json.decodeFromStream(ListSerializer(IoLogo.serializer()), it) }
        return buildIndex(channels, logos)
    }

    /** Read as a stream: the ~14 MB files never sit whole in memory as text on a TV. */
    private fun <T> fetch(url: String, read: (java.io.InputStream) -> T): T =
        http.newCall(Request.Builder().url(url).header("User-Agent", "SokoulTV/2.0").build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            read(checkNotNull(r.body).byteStream())
        }
}
