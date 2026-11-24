package com.sokoul.pc.playback.sources

import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.Resume
import com.sokoul.iptv.data.debrid.RdTorrent
import com.sokoul.iptv.data.debrid.RealDebridApi
import com.sokoul.pc.data.PcLog
import kotlinx.coroutines.delay

/**
 * The daily cleanup of the Real-Debrid account, by what the owner does with each torrent (rules chosen on 2026-11-24):
 * - broken (error, dead, virus) or a day at 0 %: removed;
 * - a film or an episode finished: removed 7 days after; a season pack, 30 days after its last episode played;
 * - in progress: kept (90 days without playing and it goes);
 * - added by Sokoul and never played: removed after 2 days;
 * - unknown to Sokoul (added before it kept track): removed after 30 days.
 * Removing loses nothing: Real-Debrid keeps the file in its own cache, and adding it again is immediate.
 */
class RdCleanup(private val api: RealDebridApi, private val db: DesktopDb, private val now: () -> Long = System::currentTimeMillis) {

    suspend fun runIfDue(): Int {
        val last = db.setting(LAST_RUN)?.toLongOrNull() ?: 0L
        if (now() - last < DAY_MS) return 0
        db.putSetting(LAST_RUN, now().toString())
        val all = buildList {
            var page = 1
            while (page <= MAX_PAGES) {
                val batch = api.torrents(PAGE, page)
                addAll(batch)
                if (batch.size < PAGE) break
                page++
            }
        }
        val t0 = now()
        val gone = all.filter { t ->
            val known = db.rdKnown(t.hash)
            useless(t, known, known?.playedKey?.let(db::resume), t0)
        }.take(MAX_PER_RUN)
        var removed = 0
        for (t in gone) {
            if (runCatching { api.deleteTorrent(t.id) }.isSuccess) removed++
            delay(SPACING_MS)
        }
        PcLog.event("real-debrid cleanup: $removed removed of ${gone.size} chosen, ${all.size} in the account")
        return removed
    }

    companion object {
        private const val LAST_RUN = "rd.cleanup.last.v2"
        private const val DAY_MS = 24 * 3_600_000L
        private const val FINISHED_KEEP_MS = 7 * DAY_MS
        private const val PACK_KEEP_MS = 30 * DAY_MS
        private const val IN_PROGRESS_KEEP_MS = 90 * DAY_MS
        private const val NEVER_PLAYED_KEEP_MS = 2 * DAY_MS
        private const val UNKNOWN_KEEP_MS = 30 * DAY_MS
        private const val PAGE = 100
        private const val MAX_PAGES = 30
        private const val MAX_PER_RUN = 200
        private const val SPACING_MS = 1_500L
        private val BROKEN = setOf("error", "magnet_error", "virus", "dead")
        private val WAITING = setOf("magnet_conversion", "waiting_files_selection", "queued", "downloading", "compressing", "uploading")
        private val EPISODE = Regex("""(?i)\bS\d{1,2}[ ._-]?E\d{1,3}\b|\b\d{1,2}x\d{2}\b""")
        private val SEASON = Regex("""(?i)\bS\d{1,2}\b|\bseason\b|\bsaison\b|\bint[eé]grale\b|\bcomplete\b""")

        /** A whole season in one torrent: a season named, no single episode. */
        fun isPack(t: RdTorrent): Boolean = !EPISODE.containsMatchIn(t.filename) && SEASON.containsMatchIn(t.filename)

        fun useless(t: RdTorrent, known: DesktopDb.RdKnown?, watched: Resume?, now: Long): Boolean {
            fun older(at: Long?, ms: Long) = at != null && at > 0 && now - at > ms
            if (t.status in BROKEN) return true
            if (!t.ready) return t.status in WAITING && t.progress <= 0 && older(t.addedAt, DAY_MS)
            if (known == null) return older(t.addedAt, UNKNOWN_KEEP_MS)
            if (known.playedKey == null) return older(known.addedAt, NEVER_PLAYED_KEEP_MS)
            if (isPack(t)) return older(known.playedAt, PACK_KEEP_MS)
            return when {
                watched == null -> older(known.playedAt, FINISHED_KEEP_MS)
                watched.finished -> older(watched.updatedAt, FINISHED_KEEP_MS)
                else -> older(watched.updatedAt, IN_PROGRESS_KEEP_MS)
            }
        }
    }
}
