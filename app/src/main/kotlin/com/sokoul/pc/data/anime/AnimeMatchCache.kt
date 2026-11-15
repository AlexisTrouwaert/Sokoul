package com.sokoul.pc.data.anime

import com.sokoul.iptv.data.net.TmdbItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** One matching budget for every row; duplicate titles share work and failed lookups expire. */
internal class AnimeMatchCache(
    parallelism: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val item: TmdbItem?, val until: Long)
    private val known = ConcurrentHashMap<String, Entry>()
    private val locks = List(64) { Mutex() }
    private val gate = Semaphore(parallelism)

    suspend fun resolve(id: Int, load: suspend () -> TmdbItem?): TmdbItem? = resolve("anilist:$id", load)

    suspend fun resolve(id: String, load: suspend () -> TmdbItem?): TmdbItem? =
        locks[(id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            known[id]?.takeIf { it.until > clock() }?.let { return@withLock it.item }
            val result = gate.withPermit { load() }
            if (known.size >= 4096) {
                known.entries.minByOrNull { it.value.until }?.let { known.remove(it.key, it.value) }
            }
            known[id] = Entry(result, clock() + if (result == null) 60_000L else 12 * 3_600_000L)
            result
        }
}
