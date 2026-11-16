package com.sokoul.pc.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A playable trailer. YouTube serves high quality as separate video and audio streams. Never carries a token. */
data class TrailerStream(
    val videoUrl: String,
    val audioUrl: String?,
    val method: String,
    /** Real height of the chosen stream; TMDB's announced size can be wrong. Null when unknown. */
    val height: Int? = null,
    val key: String = "",
    val width: Int? = null,
) {
    /** The height the picture would have in 16:9: a 1920x800 scope trailer is full HD, though only 800 high. */
    val sharpness: Int get() = maxOf(height ?: 0, (width ?: 0) * 9 / 16)
}

/** Locates and runs yt-dlp. Only an id checked by [isValidKey] ever reaches its command line. */
class YtDlp(private val executable: () -> String? = ::locate, private val timeoutSeconds: Long = 75) {
    fun available() = executable() != null

    /** Video and audio URLs for a YouTube key, or null when yt-dlp is missing or fails. */
    fun streams(key: String): TrailerStream? {
        if (!isValidKey(key)) return null
        val exe = executable() ?: return null
        return runCatching {
            val process = ProcessBuilder(
                exe, "--no-warnings", "--no-playlist", "--js-runtimes", "node",
                "-f", FORMAT, "-S", SORT, "--print", "%(height)s", "--print", "%(width)s", "--print", "urls", "--", key,
            ).redirectError(ProcessBuilder.Redirect.to(java.io.File(if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "NUL" else "/dev/null"))).start()
            // The output is short (two URLs), so it fits the pipe buffer until the process ends.
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            parse(process.inputStream.bufferedReader().readText())
        }.getOrNull()
    }

    companion object {
        /**
         * Up to 1080p, highest bitrate first: neither codec always wins (measured: VP9 from 681 to 6793 kb/s, AVC at
         * 1589 kb/s beating VP9 at 907 kb/s on the same video). AV1 is left out: much lower bitrates, heavier to decode.
         */
        const val FORMAT = "bv*[height<=1080][vcodec!^=av01]+ba[ext=m4a]/b[height<=1080]/b"
        /** "lang" first: on a video with several audio tracks, yt-dlp ranks the original track highest. */
        const val SORT = "lang,res:1080,tbr"

        fun isValidKey(key: String) = Regex("[A-Za-z0-9_-]{6,20}").matches(key)

        fun parse(output: String): TrailerStream? {
            val lines = output.lines().map { it.trim() }.filter { it.startsWith("https://") }
            val video = lines.firstOrNull() ?: return null
            val numbers = output.lines().mapNotNull { it.trim().toIntOrNull() }
            return TrailerStream(video, lines.getOrNull(1), "yt-dlp", numbers.getOrNull(0), width = numbers.getOrNull(1))
        }

        fun locate(): String? {
            System.getenv("YTDLP_PATH")?.takeIf { File(it).isFile }?.let { return it }
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
            val name = if (windows) "yt-dlp.exe" else "yt-dlp"
            System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.isFile }?.let { return it.path }
            // An app opened from the Finder does not get the shell's PATH: look where Homebrew installs it.
            if (!windows) return listOf("/opt/homebrew/bin", "/usr/local/bin").map { File(it, name) }.firstOrNull { it.canExecute() }?.path
            // winget's PATH change only reaches processes started after the install.
            val packages = System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft/WinGet/Packages") } ?: return null
            return packages.listFiles { f -> f.name.startsWith("yt-dlp.yt-dlp") }?.map { File(it, name) }?.firstOrNull { it.isFile }?.path
        }
    }
}

/** Where resolved links survive a restart. Links are time-limited video URLs, not credentials. */
interface TrailerStore {
    fun get(key: String): Pair<Long, TrailerStream>?
    fun put(key: String, at: Long, stream: TrailerStream)
}

/**
 * Turns a TMDB YouTube key into playable streams: yt-dlp first, because it alone tells the real quality, then Real-Debrid.
 * yt-dlp takes seconds per call, so results are kept in memory and in [store] until shortly before YouTube's links expire.
 */
class TrailerResolver(
    private val http: OkHttpClient,
    private val token: () -> String?,
    private val ytDlp: (String) -> TrailerStream? = YtDlp()::streams,
    private val baseUrl: String = "https://api.real-debrid.com/rest/1.0",
    private val now: () -> Long = System::currentTimeMillis,
    private val store: TrailerStore? = null,
) {
    private val cache = ConcurrentHashMap<String, Pair<Long, TrailerStream>>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val slots = Semaphore(MAX_PARALLEL)
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * The sharpest of [keys], in their order: TMDB can announce 1080p for a video that only exists in 360p. Each extra
     * candidate costs a yt-dlp call, so the search stops at the first full HD one. A stream of unknown height ranks last.
     */
    suspend fun best(keys: List<String>): TrailerStream? {
        var best: TrailerStream? = null
        for (key in keys.take(MAX_CANDIDATES)) {
            val stream = resolve(key) ?: continue
            if (stream.sharpness >= FULL_HD) return stream
            if (best == null || stream.sharpness > best.sharpness) best = stream
        }
        return best
    }

    suspend fun resolve(key: String): TrailerStream? = withContext(Dispatchers.IO) {
        val id = "v4:$key"
        known(id)?.let { return@withContext it }
        misses[id]?.takeIf { now() - it < MISS_TTL_MS }?.let { return@withContext null }
        // A preview and a warm-up asking for the same trailer share one call.
        locks.computeIfAbsent(id) { Mutex() }.withLock {
            known(id)?.let { return@withLock it }
            val found = slots.withPermit { ytDlp(key) ?: realDebrid(key) }?.copy(key = key)
            if (found != null) {
                val at = now()
                cache[id] = at to found
                runCatching { store?.put(id, at, found) }
            } else {
                misses[id] = now()
            }
            found
        }
    }

    private fun known(id: String): TrailerStream? {
        cache[id]?.takeIf { now() - it.first < TTL_MS }?.let { return it.second }
        val stored = runCatching { store?.get(id) }.getOrNull()?.takeIf { now() - it.first < TTL_MS } ?: return null
        cache[id] = stored
        return stored.second
    }

    private fun realDebrid(key: String): TrailerStream? {
        val t = token()?.takeIf { it.isNotBlank() } ?: return null
        val body = FormBody.Builder().add("link", youtubeUrl(key)).build()
        val request = Request.Builder().url("$baseUrl/unrestrict/link").header("Authorization", "Bearer $t").post(body).build()
        return runCatching {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = Json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
                o?.get("download")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { TrailerStream(it, null, "real-debrid") }
            }
        }.getOrNull()
    }

    companion object {
        /** YouTube links last about 6 hours. */
        private const val TTL_MS = 4 * 3_600_000L
        private const val MISS_TTL_MS = 10 * 60_000L
        private const val MAX_PARALLEL = 2
        private const val MAX_CANDIDATES = 4
        private const val FULL_HD = 1080

        fun youtubeUrl(key: String) = "https://www.youtube.com/watch?v=$key"
    }
}
