package com.sokoul.desktop.data

import com.sokoul.desktop.player.StreamRequest
import com.sokoul.iptv.data.net.XtreamClient
import com.sokoul.iptv.parser.XtreamUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

enum class ProviderKind { Movies, Series }
data class ProviderCategory(val id: String, val title: String, val count: Int = 0)
data class ProviderItem(val id: String, val title: String, val poster: String?, val year: Int?, val kind: ProviderKind, val rating: Double? = null, val description: String? = null, val categoryId: String? = null)
data class ProviderPage(val items: List<ProviderItem>, val total: Int, val offset: Int, val hasMore: Boolean)
data class ProviderEpisode(val id: String, val season: Int, val episode: Int, val title: String, val poster: String?, val durationMs: Long?, val extension: String? = null)
data class ProviderAccountSnapshot(
    val status: String?, val active: Boolean, val expiresAt: Long?, val maxConnections: Int?, val activeConnections: Int?,
    val formats: List<String>, val timezone: String?, val checkedAt: Long, val stale: Boolean = false, val error: String? = null,
)

/** Source-first catalogue access. Reads are paged locally; no TMDB identity is required to play an entry. */
class ProviderCatalog(private val db: DesktopDb) {
    private val sync = DesktopSync(db)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // Metadata and channel lists must not inherit the five-minute XMLTV import timeout.
    private val client = XtreamClient(sync.http.newBuilder()
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build(), json)

    fun playlist(): Playlist? {
        val all = db.playlists()
        val active = db.setting(Settings.ACTIVE_PLAYLIST)?.toLongOrNull()
        return all.firstOrNull { it.id == active } ?: all.firstOrNull()
    }

    suspend fun categories(kind: ProviderKind, refresh: Boolean = false): List<ProviderCategory> = withContext(Dispatchers.IO) {
        val p = playlist() ?: return@withContext emptyList()
        val stored = db.providerCategories(p.id, kind)
        val cachedAt = db.setting("provider_categories_${p.id}_${kind.name}")?.toLongOrNull() ?: 0L
        if (p.type == PlaylistType.XTREAM && (refresh || System.currentTimeMillis() - cachedAt > 6 * 3_600_000L)) {
            try {
                val action = if (kind == ProviderKind.Movies) "get_vod_categories" else "get_series_categories"
                val incoming = client.categories(sync.auth(p), action).map { ProviderCategory(it.id, it.name) }
                // An empty/invalid remote answer must not erase a previously useful category list.
                if (incoming.isNotEmpty()) db.replaceProviderCategories(p.id, kind, incoming)
                db.putSetting("provider_categories_${p.id}_${kind.name}", System.currentTimeMillis().toString())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (stored.isEmpty()) throw failure }
        }
        db.providerCategories(p.id, kind)
    }

    fun page(kind: ProviderKind, categoryId: String? = null, query: String = "", offset: Int = 0, limit: Int = 36, exactCount: Boolean = true): ProviderPage {
        val p = playlist() ?: return ProviderPage(emptyList(), 0, 0, false)
        return page(p.id, kind, categoryId, query, offset, limit, exactCount)
    }

    fun page(playlistId: Long, kind: ProviderKind, categoryId: String? = null, query: String = "", offset: Int = 0, limit: Int = 36, exactCount: Boolean = true): ProviderPage {
        val startedAt = System.nanoTime()
        val start = offset.coerceAtLeast(0)
        val size = limit.coerceIn(1, 100)
        com.sokoul.pc.Platform.guideDiagnostic("provider page started kind=${kind.name} category=${categoryId != null} search=${query.isNotBlank()} offset=$start")
        val items = when (kind) {
            ProviderKind.Movies -> db.providerMoviesPage(playlistId, query, if (exactCount) size else size + 1, categoryId, start).map { ProviderItem(it.id, it.name, it.poster, it.year, kind, it.rating, categoryId = it.categoryId) }
            ProviderKind.Series -> db.providerSeriesPage(playlistId, query, if (exactCount) size else size + 1, categoryId, start).map { ProviderItem(it.id, it.name, it.poster, it.year, kind, it.rating, it.plot, it.categoryId) }
        }
        val exactTotal = if (exactCount) db.providerPageCount(playlistId, kind, categoryId, query) else null
        val hasMore = exactTotal?.let { start + items.size < it } ?: (items.size > size)
        val visible = items.take(size)
        val total = exactTotal ?: if (hasMore) -1 else start + visible.size
        com.sokoul.pc.Platform.guideDiagnostic("provider page finished kind=${kind.name} rows=${visible.size} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")
        return ProviderPage(visible, total, start, hasMore)
    }

    suspend fun movieStream(item: ProviderItem): StreamRequest? = withContext(Dispatchers.IO) {
        if (item.kind != ProviderKind.Movies) return@withContext null
        val p = playlist() ?: return@withContext null
        val movie = db.providerMovie(p.id, item.id) ?: return@withContext null
        val url = movie.streamUrl?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: if (p.type == PlaylistType.XTREAM) movie.id.toLongOrNull()?.let {
                XtreamUrls.movie(p.url.trimEnd('/'), p.username.orEmpty(), p.password.orEmpty(), it, movie.extension)
            } else null
        url?.let { StreamRequest(it, p.userAgent ?: sync.userAgent(), null, title = item.title, live = false) }
    }

    suspend fun episodes(item: ProviderItem): List<ProviderEpisode> = withContext(Dispatchers.IO) {
        if (item.kind != ProviderKind.Series) return@withContext emptyList()
        val p = playlist()?.takeIf { it.type == PlaylistType.XTREAM } ?: return@withContext emptyList()
        val id = item.id.toLongOrNull() ?: return@withContext emptyList()
        client.seriesInfo(sync.auth(p), id).episodes.sortedWith(compareBy({ it.season }, { it.episodeNum })).map {
            ProviderEpisode(it.id, it.season, it.episodeNum, it.title, it.image ?: item.poster, it.durationSecs?.toLong()?.times(1000), it.extension)
        }
    }

    fun episodeStream(episode: ProviderEpisode): StreamRequest? {
        val p = playlist()?.takeIf { it.type == PlaylistType.XTREAM } ?: return null
        if (episode.id.isBlank()) return null
        return StreamRequest(XtreamUrls.episode(p.url.trimEnd('/'), p.username.orEmpty(), p.password.orEmpty(), episode.id, episode.extension), p.userAgent ?: sync.userAgent(), null, title = episode.title, live = false)
    }

    suspend fun account(refresh: Boolean = false): ProviderAccountSnapshot? = withContext(Dispatchers.IO) {
        val p = playlist()?.takeIf { it.type == PlaylistType.XTREAM } ?: return@withContext null
        val key = "provider_account_${p.id}"
        val saved = db.setting(key)?.let(::decodeAccount)
        if (!refresh && saved != null && System.currentTimeMillis() - saved.checkedAt < 10 * 60_000L) return@withContext saved
        try {
            val info = client.account(sync.auth(p))
            val result = ProviderAccountSnapshot(info.status, info.isActive, info.expiresAt, info.maxConnections, info.activeConnections,
                info.allowedOutputFormats, info.serverTimezone, System.currentTimeMillis())
            db.putSetting(key, encodeAccount(result))
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            saved?.copy(stale = true, error = failure.javaClass.simpleName)
                ?: ProviderAccountSnapshot(null, false, null, null, null, emptyList(), null, 0, true, failure.javaClass.simpleName)
        }
    }

    fun outputFormat(): String = playlist()?.let { db.setting("provider_output_format_${it.id}") }?.takeIf { it in FORMATS } ?: "auto"
    fun resolvedOutputFormat(): String {
        val p = playlist() ?: return "ts"
        val selected = outputFormat()
        val allowed = db.setting("provider_account_${p.id}")?.let(::decodeAccount)?.formats.orEmpty()
        if (selected != "auto" && (allowed.isEmpty() || selected in allowed)) return selected
        return if (allowed.isNotEmpty() && "ts" !in allowed && "m3u8" in allowed) "m3u8" else "ts"
    }
    fun setOutputFormat(value: String) {
        require(value in FORMATS)
        val p = playlist() ?: return
        db.putSetting("provider_output_format_${p.id}", value)
    }

    fun guideOffsetMinutes(): Int = playlist()?.let { guideOffsetMinutes(db, it.id) } ?: 0
    fun setGuideOffsetMinutes(value: Int) {
        require(value in -720..720)
        val p = playlist() ?: return
        val old = guideOffsetMinutes()
        if (value != old) db.applyGuideOffset(p.id, "provider_epg_offset_${p.id}", value, (value - old) * 60_000L)
    }

    private fun encodeAccount(value: ProviderAccountSnapshot): String = buildJsonObject {
        put("status", value.status?.let(::JsonPrimitive) ?: JsonNull); put("active", value.active)
        put("expiresAt", value.expiresAt?.let(::JsonPrimitive) ?: JsonNull)
        put("maxConnections", value.maxConnections?.let(::JsonPrimitive) ?: JsonNull)
        put("activeConnections", value.activeConnections?.let(::JsonPrimitive) ?: JsonNull)
        put("formats", JsonArray(value.formats.map(::JsonPrimitive)))
        put("timezone", value.timezone?.let(::JsonPrimitive) ?: JsonNull); put("checkedAt", value.checkedAt)
    }.toString()

    private fun decodeAccount(text: String): ProviderAccountSnapshot? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        ProviderAccountSnapshot(o["status"]?.jsonPrimitive?.contentOrNull, o["active"]?.jsonPrimitive?.booleanOrNull == true,
            o["expiresAt"]?.jsonPrimitive?.longOrNull, o["maxConnections"]?.jsonPrimitive?.intOrNull,
            o["activeConnections"]?.jsonPrimitive?.intOrNull, o["formats"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            o["timezone"]?.jsonPrimitive?.contentOrNull, o["checkedAt"]?.jsonPrimitive?.longOrNull ?: 0)
    }.getOrNull()

    companion object {
        private val FORMATS = setOf("auto", "ts", "m3u8")
        fun guideOffsetMinutes(db: DesktopDb, playlistId: Long): Int =
            (db.setting("provider_epg_offset_$playlistId") ?: db.setting(Settings.EPG_OFFSET))?.toIntOrNull()?.coerceIn(-720, 720) ?: 0
    }
}
