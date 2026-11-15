package com.sokoul.tv

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.sokoul.desktop.data.Resume
import com.sokoul.pc.playback.session.PlayRequest

/**
 * "Continue watching" on the Android TV home screen itself (the Watch Next row): what was started and not finished, with its
 * picture and progress, and a press on it opens Sokoul on that title. The row belongs to this app: only its own entries are
 * ever added, changed or removed here.
 */
object WatchNext {
    /** The row shows few entries; more would only bury the ones that matter. */
    private const val MAX = 6
    private const val SCHEME = "sokoul"
    private const val HOST = "watch"
    private const val TAG = "SokoulWatchNext"

    /** Brings the row in line with [items] (most recent first): new ones added, known ones updated, others removed. */
    fun sync(context: Context, items: List<Resume>) {
        try {
            val resolver = context.contentResolver
            val wanted = items.filter { !it.finished && it.durationMs > 0 }.take(MAX).associateBy { idOf(it) }
            val existing = HashMap<String, Long>()
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val p = WatchNextProgram.fromCursor(c)
                    p.internalProviderId?.let { existing[it] = p.id }
                }
            }
            Log.i(TAG, "started: ${items.size}, wanted: ${wanted.size}, already in the row: ${existing.size}")
            existing.filterKeys { it !in wanted }.values.forEach { resolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
            wanted.forEach { (key, r) ->
                val values = program(r).toContentValues()
                val row = existing[key]
                if (row == null) resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                else resolver.update(TvContractCompat.buildWatchNextProgramUri(row), values, null, null)
            }
        } catch (e: Exception) {
            // The class only: a message could carry a title or an address.
            Log.w(TAG, "sync failed: ${e.javaClass.simpleName}")
        }
    }

    /** Removes everything this app put in the row (a profile change, or a cleared history). */
    fun clear(context: Context) = sync(context, emptyList())

    private fun idOf(r: Resume) = r.key

    private fun program(r: Resume): WatchNextProgram {
        val b = WatchNextProgram.Builder()
            .setType(if (r.kind == "tv") TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(r.updatedAt)
            .setTitle(r.title)
            .setInternalProviderId(idOf(r))
            .setLastPlaybackPositionMillis(r.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setDurationMillis(r.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(link(r))
        if (r.kind == "tv" && r.season != null && r.episode != null) {
            b.setSeasonNumber(r.season!!).setEpisodeNumber(r.episode!!)
            r.subtitle?.takeIf { it.isNotBlank() }?.let { b.setEpisodeTitle(it) }
        } else {
            r.subtitle?.takeIf { it.isNotBlank() }?.let { b.setDescription(it) }
        }
        r.image?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { b.setPosterArtUri(Uri.parse(it)) }
        return b.build()
    }

    /** The address a press on the entry opens: it carries what is needed to start the title, nothing secret. */
    private fun link(r: Resume): Uri = Uri.Builder().scheme(SCHEME).authority(HOST)
        .appendQueryParameter("kind", r.kind).appendQueryParameter("id", r.tmdbId.toString())
        .apply {
            r.season?.let { appendQueryParameter("season", it.toString()) }
            r.episode?.let { appendQueryParameter("episode", it.toString()) }
            appendQueryParameter("t", r.title)
            r.subtitle?.let { appendQueryParameter("k", it) }
            r.image?.let { appendQueryParameter("i", it) }
        }
        .build()

    /** The title a Watch Next address names, or null for any other address. */
    fun requestOf(uri: Uri?): PlayRequest? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val kind = uri.getQueryParameter("kind")?.takeIf { it == "movie" || it == "tv" } ?: return null
        val id = uri.getQueryParameter("id")?.toIntOrNull() ?: return null
        return PlayRequest(
            kind, id, uri.getQueryParameter("season")?.toIntOrNull(), uri.getQueryParameter("episode")?.toIntOrNull(),
            uri.getQueryParameter("t").orEmpty(), uri.getQueryParameter("k"), uri.getQueryParameter("i"),
        )
    }
}
