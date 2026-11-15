package com.sokoul.pc.live

import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.DesktopSync
import com.sokoul.desktop.data.LiveLogic
import com.sokoul.desktop.data.Playlist
import com.sokoul.desktop.data.ProviderCatalog
import com.sokoul.desktop.data.Settings
import com.sokoul.desktop.data.SpecialGroup
import com.sokoul.pc.i18n.Strings
import com.sokoul.pc.Platform
import com.sokoul.pc.playback.session.LiveChannel
import kotlinx.coroutines.sync.withLock

/** The live channels and provider-backed workspace for the active playlist. */
class TvCatalog(
    private val db: DesktopDb,
    /** The provider's own short guide of a channel, for those the big guide does not cover. */
    private val shortGuide: suspend (Channel) -> List<com.sokoul.desktop.data.Program> = { emptyList() },
) {
    data class Hit(val channel: Channel, val program: com.sokoul.desktop.data.Program?)
    data class Position(val group: String, val channelId: String, val programStart: Long?)
    data class SearchPage(val hits: List<Hit>, val hasMore: Boolean)

    /** Channel and programme pages use separate cursors, so a large channel list never hides programme matches. */
    fun searchTv(query: String, programs: Boolean, page: Int = 0, now: Long = System.currentTimeMillis(), until: Long = now + 72 * 3_600_000L): SearchPage {
        val p = playlist() ?: return SearchPage(emptyList(), false)
        val q = query.trim()
        if (q.isEmpty() && !programs) return SearchPage(emptyList(), false)
        val offset = page.coerceAtLeast(0) * 60
        if (programs) {
            // Browsing without a query (Discover): the viewer's own region first, all channels only if it has none.
            val prefix = if (q.isEmpty() && Strings.isFrench()) "FR|" else null
            val raw = db.searchTvPrograms(p.id, q, now, until, offset, 61, prefix)
            val allowed = organization.filterChannels(raw.map { it.second }).mapTo(hashSetOf()) { it.id }
            return SearchPage(raw.filter { it.second.id in allowed && !isSeparator(it.second.name) && !isAccountInfo(it.second.name) }
                .distinctBy { (pr, c) -> Triple(c.nameKey, region(c.name), pr.start) }.map { Hit(it.second, it.first) }, raw.map { it.first.channelKey to it.first.start }.distinct().size > 60)
        }
        val key = LiveLogic.searchKey(q)
        val raw = db.searchTvChannels(p.id, key, offset, 61)
        // Same relevance as the query, then the viewer's region before foreign variants of the same name.
        fun rank(c: Channel) = when {
            q.toIntOrNull() != null && c.number == q.toIntOrNull() -> 0
            c.nameKey == key -> 1
            c.nameKey.startsWith(key) -> 2
            else -> 3
        }
        return SearchPage(organization.filterChannels(raw.take(60)).filterNot { isSeparator(it.name) || isAccountInfo(it.name) }
            .sortedWith(compareBy({ rank(it) }, { if (region(it.name) == "fr" && Strings.isFrench()) 0 else if (isLocal(it.name)) 1 else 2 }))
            .distinctBy { it.nameKey to region(it.name) }.map { Hit(it, null) }, raw.size > 60)
    }

    /** What was seen of each stream: its real height, and whether it failed lately. */
    private data class Seen(val height: Int, val failedAt: Long)

    private val memory by lazy {
        java.util.concurrent.ConcurrentHashMap(
            db.setting(MEMORY_SETTING)?.lines()?.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 3) null else p[0] to Seen(p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0)
            }.orEmpty().toMap(),
        )
    }
    private val shortAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val shortCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<com.sokoul.desktop.data.Program>>>()
    private val shortLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val recordingScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val recorder by lazy { com.sokoul.desktop.data.DesktopRecorder.runtime ?: com.sokoul.desktop.data.DesktopRecorder(db, DesktopSync(db), recordingScope) }
    @Volatile var kidsProfile: () -> Boolean = { false }
    val organization by lazy { TvOrganization(db, ::playlist) { kidsProfile() } }
    val provider by lazy { ProviderCatalog(db) }
    private val userAgent by lazy { DesktopSync(db).userAgent() }
    private val guideSync by lazy { DesktopSync(db) }
    private val guideRefreshLock = kotlinx.coroutines.sync.Mutex()
    private var guideCheckedAt = 0L

    /** Import the provider XMLTV directly when the visible live window has no current listings. */
    suspend fun refreshMissingGuide(channels: List<Channel>) = guideRefreshLock.withLock {
        Platform.guideDiagnostic("guide window channels=${channels.size}")
        val now = System.currentTimeMillis()
        if (now - guideCheckedAt < 5 * 60_000L) return@withLock
        val provider = playlist() ?: return@withLock
        if (!guideSync.isGuideStale(provider) && schedules(channels).values.any { rows -> rows.any { it.start <= now && it.end > now } }) return@withLock
        Platform.guideDiagnostic("provider XMLTV refresh started type=${provider.type}")
        val count = guideSync.syncGuide(provider)
        guideCheckedAt = System.currentTimeMillis()
        Platform.guideDiagnostic("provider XMLTV imported=$count")
    }

    fun playlist(): Playlist? {
        val all = db.playlists()
        val active = db.setting(Settings.ACTIVE_PLAYLIST)?.toLongOrNull()
        return all.firstOrNull { it.id == active } ?: all.firstOrNull()
    }

    fun groups(): List<LiveLogic.Group> {
        val p = playlist() ?: return emptyList()
        val all = organization.visibleGroups(LiveLogic.groups(db.categories(p.id), db.favoriteIds(p.id).isNotEmpty()))
            .filter { !it.locked && !isAccountInfo(it.name) }
        val (special, plain) = all.partition { it.id == SpecialGroup.FAVORITES || it.id == SpecialGroup.ALL }
        val recent = if (recentIds().isEmpty()) emptyList() else listOf(LiveLogic.Group(RECENT, "Recent", false))
        // Favorites, recent, everything, then the groups of the viewer's language: with hundreds of groups, they come first.
        val (mine, others) = plain.partition { LOCAL.containsMatchIn(it.name) == Strings.isFrench() && LOCAL.containsMatchIn(it.name) }
        return organization.visibleGroups(special.filter { it.id == SpecialGroup.FAVORITES } + recent + special.filter { it.id == SpecialGroup.ALL } + mine + others)
    }

    fun channels(group: String?): List<Channel> {
        val p = playlist() ?: return emptyList()
        if (group == RECENT) {
            val byId = db.channelsByIds(p.id, recentIds()).associateBy { it.id }
            val recent = recentIds().mapNotNull(byId::get)
            val allowed = organization.filterChannels(recent).associateBy { it.id }
            return recent.mapNotNull { allowed[it.id] }
        }
        val list = db.channels(p.id, group ?: SpecialGroup.ALL).filterNot { isSeparator(it.name) || isAccountInfo(it.name) }
        // Favorites are chosen one by one. Elsewhere, one row per channel: the player knows its other streams.
        val filtered = organization.filterChannels(list)
        return if (group == SpecialGroup.FAVORITES || !mergeQualities()) filtered else merged(filtered)
    }

    private fun mergeQualities() = db.setting(MERGE_SETTING) != "off"

    internal fun preferredChannels(list: List<Channel>): List<Channel> = merged(list)

    private fun merged(list: List<Channel>): List<Channel> {
        val families = LinkedHashMap<String, MutableList<Channel>>()
        list.forEach { families.getOrPut(it.nameKey) { mutableListOf() } += it }
        return families.values.map { family -> family.minWith(streamOrder(region(family.first().name))) }
    }

    private fun streamOrder(region: String, now: Long = System.currentTimeMillis()) = compareBy<Channel>(
        { StreamChoice.failedLately(memory[it.id]?.failedAt ?: 0, now) },
        { if (region(it.name) == region) 0 else 1 },
        { -StreamChoice.expectedHeight(it.name, memory[it.id]?.height ?: 0) },
    )

    private fun isLocal(name: String) = Strings.isFrench() && LOCAL.containsMatchIn(name)

    private fun localRegion(family: List<Channel>) = region(family.firstOrNull { isLocal(it.name) }?.name ?: family.first().name)

    /** Remembers what a stream really did, to choose better next time. */
    fun noteStream(id: String, height: Int?, failed: Boolean) {
        val before = memory[id]
        memory[id] = Seen(height?.takeIf { it > 0 } ?: before?.height ?: 0, if (failed) System.currentTimeMillis() else 0)
        db.putSetting(MEMORY_SETTING, memory.entries.toList().takeLast(MAX_MEMORY).joinToString("\n") { "${it.key}\t${it.value.height}\t${it.value.failedAt}" })
    }

    fun rememberPosition(group: String?, channelId: String, programStart: Long? = null) {
        db.putSetting(LAST_SETTING, listOfNotNull(group.orEmpty(), channelId, programStart?.toString()).joinToString("\n"))
    }

    /** The group and channel last watched. */
    fun lastPosition(): Position? {
        val parts = db.setting(LAST_SETTING)?.split('\n') ?: return null
        return if (parts.size < 2 || parts[0].isEmpty()) null else Position(parts[0], parts[1], parts.getOrNull(2)?.toLongOrNull())
    }

    /** Channels by name, then programmes by title that have not ended. */
    fun search(query: String, now: Long = System.currentTimeMillis()): List<Hit> {
        val p = playlist() ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = organization.filterChannels(db.searchChannels(p.id, LiveLogic.searchKey(q), SEARCH_LIMIT).filterNot { isSeparator(it.name) || isAccountInfo(it.name) })
        // The viewer's own country first, in the families and in the list: TF1 is the French one before the African one.
        val channels = found.groupBy { it.nameKey }.values.map { family -> family.minWith(streamOrder(localRegion(family))) }
            .sortedBy { if (isLocal(it.name)) 0 else 1 }.take(MAX_CHANNEL_HITS)
        val programs = db.searchPrograms(p.id, q, now, SEARCH_LIMIT).filterNot { (_, c) -> isSeparator(c.name) }.distinctBy { (pr, c) -> "${c.nameKey}${pr.start}" }
            .sortedBy { (pr, c) -> (if (isLocal(c.name)) 0L else Long.MAX_VALUE / 2) + pr.start }
        return channels.map { Hit(it, null) } + programs.map { (pr, c) -> Hit(c, pr) }
    }

    /** The channels that have nothing in the big guide get the provider's short one, once, and only those on screen. */
    suspend fun fillShortGuide(
        channels: List<Channel>,
        at: Long = System.currentTimeMillis(),
        known: (String) -> List<com.sokoul.desktop.data.Program>,
    ): Map<String, List<com.sokoul.desktop.data.Program>> {
        val out = HashMap<String, List<com.sokoul.desktop.data.Program>>()
        // A row somewhere in the next few days does not fill the selected time slot. Only a programme at [at]
        // lets the XMLTV guide suppress the provider's short-EPG fallback for this channel.
        channels.filter { channel -> known(channel.id).none { it.start <= at && it.end > at } }.take(SHORT_AT_ONCE).forEach { c ->
            val found = shortSchedule(c)
            if (found.isNotEmpty()) out[c.id] = found
        }
        return out
    }

    /** Uses the playlist XMLTV first, then the provider's small per-channel guide when that window is empty. */
    private suspend fun loadSchedule(c: Channel): List<com.sokoul.desktop.data.Program> {
        val stored = schedule(c)
        val now = System.currentTimeMillis()
        val upcoming = stored.filter { it.end > now }
        if (upcoming.isNotEmpty()) return stored
        return (stored + shortSchedule(c)).distinctBy { it.channelKey to it.start }.sortedBy { it.start }
    }

    private suspend fun shortSchedule(c: Channel): List<com.sokoul.desktop.data.Program> {
        val cached = shortCache[c.id]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < SHORT_CACHE_MS) return cached.second
        val lock = shortLocks.computeIfAbsent(c.id) { kotlinx.coroutines.sync.Mutex() }
        return lock.withLock {
            val refreshed = shortCache[c.id]
            val current = System.currentTimeMillis()
            if (refreshed != null && current - refreshed.first < SHORT_CACHE_MS) return@withLock refreshed.second
            if (current - (shortAsked[c.id] ?: 0) < SHORT_AGAIN_MS) return@withLock refreshed?.second.orEmpty()
            val found = try {
                shortGuide(c).also { Platform.guideDiagnostic("short guide channel=${c.id} programmes=${it.size}") }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Platform.guideDiagnostic("short guide channel=${c.id} failed=${error.javaClass.simpleName}")
                emptyList()
            }.sortedBy { it.start }
            // A cancelled grid window never received a response; allow its next reader to retry.
            shortAsked[c.id] = current
            if (found.isNotEmpty()) shortCache[c.id] = current to found
            found.ifEmpty { refreshed?.second.orEmpty() }
        }
    }

    private fun recentIds(): List<String> = db.setting(RECENT_SETTING)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun noteWatched(id: String) {
        db.putSetting(RECENT_SETTING, (listOf(id) + recentIds().filter { it != id }).take(MAX_RECENT).joinToString("\n"))
    }

    fun favorites(): Set<String> = playlist()?.let { db.favoriteIds(it.id) }.orEmpty()

    fun toggleFavorite(id: String) {
        val p = playlist() ?: return
        if (id in db.favoriteIds(p.id)) db.removeFavorite(p.id, "LIVE", id) else db.addFavorite(p.id, "LIVE", id, System.currentTimeMillis())
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean): Boolean {
        val p = playlist() ?: return false
        val channel = db.channelsByIds(p.id, listOf(id)).firstOrNull() ?: return false
        if (!organization.canAccess(channel)) return false
        if (favorite) db.addFavorite(p.id, "LIVE", id, System.currentTimeMillis()) else db.removeFavorite(p.id, "LIVE", id)
        return true
    }

    /** What these channels show from now on, by channel id. */
    fun schedules(channels: List<Channel>, now: Long = System.currentTimeMillis()): Map<String, List<com.sokoul.desktop.data.Program>> {
        val p = playlist() ?: return emptyMap()
        // XMLTV import can repair channel-to-guide mappings after this screen's channel list was loaded.
        val fresh = channels.map { it.id }.chunked(500).flatMap { db.channelsByIds(p.id, it) }.associateBy { it.id }
        val keys = channels.associate { it.id to key(fresh[it.id] ?: it) }
        val byKey = keys.values.distinct().chunked(500).flatMap { db.programs(p.id, it, now - LOOK_BACK_MS, now + LOOK_AHEAD_MS) }.groupBy { it.channelKey }
        return channels.associate { c -> c.id to byKey[keys[c.id]].orEmpty().sortedBy { it.start } }
    }

    /** What is on now and next on these channels, by channel id. */
    fun programs(channels: List<Channel>, now: Long = System.currentTimeMillis()): Map<String, LiveLogic.NowNext> {
        return schedules(channels, now).mapValues { (_, rows) -> LiveLogic.nowNext(rows, now) }
    }

    fun channelById(id: String): Channel? = playlist()?.let { db.channelsByIds(it.id, listOf(id)).firstOrNull() }

    /** All at once: reading the playlist for each of thousands of channels would stop the interface. */
    fun toLive(list: List<Channel>): List<LiveChannel> {
        val p = playlist() ?: return emptyList()
        return organization.filterChannels(list).map { toLive(it, p) }
    }

    fun toLive(c: Channel): LiveChannel? = playlist()?.let { toLive(c, it) }

    private fun toLive(c: Channel, p: Playlist): LiveChannel {
        return toLive(c, p, true)
    }

    private fun toLive(c: Channel, p: Playlist, includeVariants: Boolean): LiveChannel {
        val capabilities = com.sokoul.pc.playback.session.LiveCapabilities(c.catchupDays.coerceAtLeast(0))
        val stream = LiveLogic.stream(p, c, userAgent, provider.resolvedOutputFormat())
        return LiveChannel(
            c.id, c.name, c.number, c.logo, stream,
            program = { LiveLogic.nowNext(loadSchedule(c), System.currentTimeMillis()) },
            schedule = { loadSchedule(c) },
            variants = { if (includeVariants) variants(c, p) else listOf(toLive(c, p, false)) },
            recording = { com.sokoul.pc.playback.session.LiveRecording(recorder, p, c) { canPlayRecording(db, it, kidsProfile()) } },
            canOpen = { organization.canAccess(c) },
            capabilities = capabilities,
            replay = { item ->
                val now = System.currentTimeMillis()
                val days = capabilities.catchupDays
                if (days <= 0 || item.end <= item.start || item.end > now || item.start < now - days * DAY_MS) null
                else {
                    val offset = provider.guideOffsetMinutes() * 60_000L
                    val accountZone = provider.account()?.timezone
                        ?.let { runCatching { java.util.TimeZone.getTimeZone(it) }.getOrNull() }
                        ?: java.util.TimeZone.getTimeZone("UTC")
                    com.sokoul.iptv.parser.Catchup.build(c.catchupType, c.catchupSource, stream.url,
                        item.start - offset, item.end - offset, now - offset, accountZone)
                    ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?.let { stream.copy(url = it, title = item.title, live = false, startMs = 0) }
                }
            },
        )
    }

    private fun schedule(c: Channel): List<com.sokoul.desktop.data.Program> {
        val p = playlist() ?: return emptyList()
        val now = System.currentTimeMillis()
        val lookBack = c.catchupDays.coerceAtLeast(0).toLong() * DAY_MS
        val mapped = db.channelsByIds(p.id, listOf(c.id)).firstOrNull() ?: c
        return db.programs(p.id, listOf(key(mapped)), now - lookBack, now + LOOK_AHEAD_MS)
    }

    /** The same channel in other qualities: "FR| TF1 FHD" and "FR| TF1 HD" share one name once the quality is removed. */
    private fun variants(c: Channel, p: Playlist): List<LiveChannel> =
        db.channelsByNameKey(p.id, c.nameKey).filterNot { isSeparator(it.name) }
            .sortedWith(streamOrder(region(c.name))).map { other -> toLive(other, p, false) }

    private fun key(c: Channel) = c.epgKey ?: c.nameKey

    companion object {
        const val RECENT = "__recent"
        private const val MEMORY_SETTING = "pc.live_stream_memory"
        private const val LAST_SETTING = "pc.live_last"
        private const val MERGE_SETTING = "pc.live_merge"
        private const val MAX_MEMORY = 400
        private const val SEARCH_LIMIT = 60
        private const val MAX_CHANNEL_HITS = 12
        private const val SHORT_AT_ONCE = 8
        private const val SHORT_AGAIN_MS = 30 * 60_000L
        private const val SHORT_CACHE_MS = 30 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
        private val QUALITY_WORDS = Regex("""(?i)\s*\b(uhd|fhd|hd|sd|4k|hevc|h\.?26[45]|1080p?|720p?)\b""")

        /** "TF1" for "FR| TF1 FHD": on a row that stands for all the streams of a channel. */
        fun shortLabel(name: String): String = label(name).replace(QUALITY_WORDS, "").trim().ifEmpty { label(name) }
        private const val RECENT_SETTING = "pc.live_recent"
        private const val MAX_RECENT = 30
        private val LOCAL = Regex("""(?i)\b(fr|france|french|français)\b""")
        private val UHD = Regex("""(?i)\b(uhd|4k|2160p?)\b""")
        private val FHD = Regex("""(?i)\b(fhd|1080p?)\b""")
        private val SD = Regex("""(?i)\b(sd|480p?|576p?)\b""")

        /** "FR" for "FR| TF1 FHD". A regional stream is the same channel, but the viewer's own comes first. */
        fun region(name: String): String = name.substringBefore('|', "").trim().lowercase()

        /** "TF1 FHD" for "FR| TF1 FHD": the list already says where it comes from. */
        fun label(name: String): String = name.substringAfter('|', name).trim().ifEmpty { name }

        /** Sharpest first; a name that says nothing counts as HD. */
        fun qualityRank(name: String) = when {
            UHD.containsMatchIn(name) -> 0
            FHD.containsMatchIn(name) -> 1
            SD.containsMatchIn(name) -> 3
            else -> 2
        }

        /** Providers title their lists with fake channels: "✦●✦| GENERAL FHD |✦●✦". */
        fun isSeparator(name: String) = name.any { it in SEPARATOR_MARKS }

        /** The provider's page about the account, listed as a channel and even as a group, with its typo. */
        fun isAccountInfo(name: String) = ACCOUNT_INFO.matches(name.trim())

        private val ACCOUNT_INFO = Regex("""(?i)acc?ou?n?t\s+info(rmation)?""")
        private const val SEPARATOR_MARKS = "✦●★☆═━─▬◆◇■□▶◀♦"
        private const val LOOK_BACK_MS = 3 * 3_600_000L
        private const val LOOK_AHEAD_MS = 72 * 3_600_000L
    }
}

/** How a stream is expected to be: what was measured on it, else what its name says. Pure and tested. */
object StreamChoice {
    private const val FAILED_FOR_MS = 6 * 3_600_000L

    fun failedLately(failedAt: Long, now: Long): Int = if (failedAt > 0 && now - failedAt < FAILED_FOR_MS) 1 else 0

    /** An unmeasured "UHD" comes after "FHD": on a real provider it was 720p, and heavier to open. */
    fun expectedHeight(name: String, measured: Int): Int = when {
        measured > 0 -> measured
        else -> when (TvCatalog.qualityRank(name)) {
            1 -> 1080
            0 -> 1000
            3 -> 480
            else -> 720
        }
    }
}
