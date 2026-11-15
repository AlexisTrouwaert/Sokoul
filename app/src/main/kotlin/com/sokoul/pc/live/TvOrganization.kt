package com.sokoul.pc.live

import com.sokoul.desktop.data.Category
import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.LiveLogic
import com.sokoul.desktop.data.Playlist
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local IPTV organization and parental access; every playback path must apply [canAccess]. */
class TvOrganization(
    private val db: DesktopDb,
    private val playlist: () -> Playlist?,
    private val isKids: () -> Boolean = { false },
) {
    enum class UnlockResult { Unlocked, WrongPin, LockedOut, KidRestricted, NotConfigured }

    fun hasPin(): Boolean = db.setting(globalKey("pin")) != null

    fun canManage(): Boolean = !isKids() && (!hasPin() || isUnlocked())

    fun isUnlocked(now: Long = System.currentTimeMillis()): Boolean {
        if (isKids()) return false
        return (unlocks()[sessionKey()] ?: 0L) > now
    }

    fun lockoutRemainingMs(now: Long = System.currentTimeMillis()): Long =
        ((db.setting(globalKey("locked_until"))?.toLongOrNull() ?: 0L) - now).coerceAtLeast(0)

    /** Configure or rotate a numeric PIN; an existing PIN requires a verified adult session. */
    fun setPin(pin: String): Boolean {
        if (isKids() || !validPin(pin) || (hasPin() && !isUnlocked())) return false
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt, PIN_ITERATIONS)
        db.putSetting(globalKey("pin"), "v1:$PIN_ITERATIONS:${encode(salt)}:${encode(hash)}")
        resetFailures()
        lockSession()
        unlocks()[sessionKey()] = System.currentTimeMillis() + UNLOCK_TTL_MS
        return true
    }

    /** Failed attempts survive process restarts. Children cannot unlock IPTV category restrictions. */
    @Synchronized
    fun unlockPin(pin: String, now: Long = System.currentTimeMillis()): UnlockResult {
        if (isKids()) return UnlockResult.KidRestricted
        val stored = db.setting(globalKey("pin")) ?: return UnlockResult.NotConfigured
        if (lockoutRemainingMs(now) > 0) return UnlockResult.LockedOut
        if (verify(pin, stored)) {
            resetFailures()
            unlocks()[sessionKey()] = now + UNLOCK_TTL_MS
            return UnlockResult.Unlocked
        }
        val failures = (db.setting(globalKey("failed_attempts"))?.toIntOrNull() ?: 0) + 1
        if (failures >= MAX_PIN_ATTEMPTS) {
            db.putSetting(globalKey("failed_attempts"), "0")
            db.putSetting(globalKey("locked_until"), (now + LOCKOUT_MS).toString())
            return UnlockResult.LockedOut
        }
        db.putSetting(globalKey("failed_attempts"), failures.toString())
        return UnlockResult.WrongPin
    }

    /** Call when switching profiles as well as when explicitly locking IPTV. */
    fun lockSession() {
        val prefix = "${playlist()?.id ?: 0L}:"
        unlocks().keys.removeAll { it.startsWith(prefix) }
    }

    fun allGroups(): List<Category> {
        val state = policy() ?: return emptyList()
        return ordered(state.categories.values.toList(), readList("group_order", state.pid)) { it.id }
            .map { it.copy(hidden = state.hidden(it.id), locked = state.locked(it.id)) }
    }

    fun isGroupHidden(groupId: String): Boolean {
        if (groupId in readList("shown_groups")) return false
        if (groupId in readList("hidden_groups")) return true
        return category(groupId)?.hidden == true
    }

    fun isGroupLocked(groupId: String): Boolean {
        if (groupId in readList("unlocked_groups")) return false
        if (groupId in readList("locked_groups")) return true
        return category(groupId)?.locked == true
    }

    fun isChannelHidden(channelId: String): Boolean = channelId in readList("hidden_channels")

    fun setGroupHidden(groupId: String, hidden: Boolean): Boolean {
        if (!canManage() || category(groupId) == null) return false
        changeSet("hidden_groups", groupId, hidden)
        changeSet("shown_groups", groupId, !hidden)
        return true
    }

    fun setGroupLocked(groupId: String, locked: Boolean): Boolean {
        if (!hasPin() || !canManage() || category(groupId) == null) return false
        changeSet("locked_groups", groupId, locked)
        changeSet("unlocked_groups", groupId, !locked)
        return true
    }

    fun setCategoryHidden(id: String, hidden: Boolean, kind: String = "live"): Boolean {
        val key = categoryKey(id, kind) ?: return false
        if (kind == "live") return setGroupHidden(id, hidden)
        if (!canManage()) return false
        changeSet("hidden_groups", key, hidden)
        changeSet("shown_groups", key, !hidden)
        return true
    }

    fun setCategoryLocked(id: String, locked: Boolean, kind: String = "live"): Boolean {
        val key = categoryKey(id, kind) ?: return false
        if (kind == "live") return setGroupLocked(id, locked)
        if (!hasPin() || !canManage()) return false
        changeSet("locked_groups", key, locked)
        changeSet("unlocked_groups", key, !locked)
        return true
    }

    fun setChannelHidden(channelId: String, hidden: Boolean): Boolean {
        if (!canManage() || channel(channelId) == null) return false
        changeSet("hidden_channels", channelId, hidden)
        return true
    }

    fun moveGroup(groupId: String, delta: Int): Boolean {
        if (!canManage()) return false
        return move("group_order", allGroups().map { it.id }, groupId, delta)
    }

    fun moveChannel(channelId: String, delta: Int): Boolean {
        if (!canManage()) return false
        val c = channel(channelId) ?: return false
        return move(channelOrderKey(c.categoryId), rawChannels(c.categoryId).map { it.id }, channelId, delta)
    }

    fun isFavorite(channelId: String): Boolean = playlist()?.let { channelId in db.favoriteIds(it.id) } == true

    fun toggleFavorite(channelId: String): Boolean {
        val p = playlist() ?: return false
        val c = channel(channelId) ?: return false
        if (!canManage() && !canAccess(c)) return false
        if (channelId in db.favoriteIds(p.id)) db.removeFavorite(p.id, "LIVE", channelId)
        else db.addFavorite(p.id, "LIVE", channelId, System.currentTimeMillis())
        return true
    }

    /** Organizer pages include hidden rows for an unlocked adult; playback uses [filterChannels]. */
    fun channels(groupId: String, offset: Int = 0, limit: Int = PAGE_SIZE): List<Channel> {
        val list = rawChannels(groupId).let { if (canManage()) it else filterChannels(it) }
        return list.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, PAGE_SIZE))
    }

    fun channelCount(groupId: String): Int = rawChannels(groupId).let { if (canManage()) it else filterChannels(it) }.size

    fun visibleGroups(groups: List<LiveLogic.Group>, now: Long = System.currentTimeMillis()): List<LiveLogic.Group> {
        val state = policy(now) ?: return emptyList()
        val visible = groups.filter { g ->
            state.allowed(g.id, g.name)
        }
        val (special, ordinary) = visible.partition { state.categories[it.id] == null }
        return special + ordered(ordinary, readList("group_order", state.pid)) { it.id }
    }

    fun canAccess(c: Channel, now: Long = System.currentTimeMillis()): Boolean {
        return policy(now)?.let { it.channelAllowed(c) } == true
    }

    /** Library and direct-play callers must provide the provider category name when available. */
    fun canAccessCategory(id: String, name: String? = null, kind: String = "live", now: Long = System.currentTimeMillis()): Boolean {
        val key = categoryKey(id, kind) ?: return false
        return policy(now)?.allowed(key, name) == true
    }

    /** Evaluate a provider category page against one policy snapshot instead of rereading SQLite per title. */
    fun allowedCategoryIds(categories: List<Pair<String, String>>, kind: String, now: Long = System.currentTimeMillis()): Set<String> {
        val state = policy(now) ?: return emptySet()
        return categories.asSequence().filter { (id, name) ->
            val key = categoryKey(id, kind) ?: return@filter false
            state.allowed(key, name)
        }.map { it.first }.toSet()
    }

    /** Filter first, then apply each group's custom order without disturbing special-list grouping. */
    fun filterChannels(list: List<Channel>, now: Long = System.currentTimeMillis()): List<Channel> {
        val state = policy(now) ?: return emptyList()
        val visible = list.filter(state::channelAllowed)
        val groupPositions = visible.map { it.categoryId }.distinct().withIndex().associate { it.value to it.index }
        val channelPositions = visible.map { it.categoryId }.distinct().associateWith { group ->
            readList(channelOrderKey(group), state.pid).withIndex().associate { it.value to it.index }
        }
        val original = visible.withIndex().associate { it.value.id to it.index }
        return visible.sortedWith(compareBy<Channel>(
            { groupPositions[it.categoryId] ?: Int.MAX_VALUE },
            { channelPositions[it.categoryId]?.get(it.id) ?: Int.MAX_VALUE },
            { original[it.id] ?: Int.MAX_VALUE },
        ))
    }

    private fun rawChannels(groupId: String): List<Channel> {
        val p = playlist() ?: return emptyList()
        return ordered(db.channels(p.id, groupId), readList(channelOrderKey(groupId))) { it.id }
    }

    private fun category(id: String): Category? = playlist()?.let { p -> db.categories(p.id).firstOrNull { it.id == id } }
    private fun channel(id: String): Channel? = playlist()?.let { p -> db.channelsByIds(p.id, listOf(id)).firstOrNull() }
    private fun globalKey(suffix: String, pid: Long = playlist()?.id ?: 0L) = "pc.iptv.organization.$pid.$suffix"
    private fun channelOrderKey(group: String) = "channel_order.${encode(group.toByteArray(StandardCharsets.UTF_8))}"
    private fun sessionKey() = "${playlist()?.id ?: 0L}:${db.profile}:${isKids()}"

    private fun readList(key: String, pid: Long = playlist()?.id ?: 0L): List<String> = db.setting(globalKey(key, pid))?.let {
        runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull()
    }.orEmpty()

    private fun writeList(key: String, ids: List<String>) = db.putSetting(globalKey(key), Json.encodeToString(ids.distinct()))
    private fun changeSet(key: String, id: String, present: Boolean) =
        writeList(key, readList(key).let { old -> if (present) old + id else old.filterNot { it == id } })

    private fun move(key: String, ids: List<String>, id: String, delta: Int): Boolean {
        val oldIndex = ids.indexOf(id)
        if (oldIndex < 0) return false
        val newIndex = (oldIndex + delta.coerceIn(-1, 1)).coerceIn(0, ids.lastIndex)
        if (oldIndex == newIndex) return false
        val order = ids.toMutableList().apply { removeAt(oldIndex); add(newIndex, id) }
        writeList(key, order)
        return true
    }

    private fun resetFailures() {
        db.putSetting(globalKey("failed_attempts"), "0")
        db.putSetting(globalKey("locked_until"), null)
    }

    private fun unlocks(): ConcurrentHashMap<String, Long> = synchronized(sessions) {
        sessions.getOrPut(db) { ConcurrentHashMap() }
    }

    /** Snapshot once for a full playlist; never issue category/settings queries per channel. */
    private fun policy(now: Long = System.currentTimeMillis()): Policy? {
        val p = playlist() ?: return null
        val kids = isKids()
        return Policy(
            p.id, db.categories(p.id).associateBy { it.id },
            readList("hidden_groups", p.id).toSet(), readList("shown_groups", p.id).toSet(),
            readList("locked_groups", p.id).toSet(), readList("unlocked_groups", p.id).toSet(),
            readList("hidden_channels", p.id).toSet(), kids,
            !kids && (unlocks()["${p.id}:${db.profile}:false"] ?: 0L) > now,
        )
    }

    private data class Policy(
        val pid: Long,
        val categories: Map<String, Category>,
        val hiddenGroups: Set<String>,
        val shownGroups: Set<String>,
        val lockedGroups: Set<String>,
        val unlockedGroups: Set<String>,
        val hiddenChannels: Set<String>,
        val kids: Boolean,
        val unlocked: Boolean,
    ) {
        fun hidden(key: String): Boolean = key !in shownGroups && (key in hiddenGroups || categories[key]?.hidden == true)
        fun locked(key: String): Boolean = key !in unlockedGroups && (key in lockedGroups || categories[key]?.locked == true)
        fun allowed(key: String, name: String? = null): Boolean = !hidden(key) &&
            (!locked(key) || (!kids && unlocked)) && (!kids || !adultName(name ?: categories[key]?.name.orEmpty()))
        fun channelAllowed(c: Channel): Boolean = c.playlistId == pid && c.id !in hiddenChannels &&
            allowed(c.categoryId) && (!kids || !adultName(c.name))
    }

    companion object {
        const val PAGE_SIZE = 60
        const val UNLOCK_TTL_MS = 15 * 60_000L
        const val LOCKOUT_MS = 60_000L
        const val MAX_PIN_ATTEMPTS = 5
        private const val PIN_ITERATIONS = 600_000
        private val sessions = WeakHashMap<DesktopDb, ConcurrentHashMap<String, Long>>()
        private val ADULT = Regex("(?:^|[^a-z0-9])(?:adult(?:e|es|s)?|xxx|porn(?:o|ography)?|18\\+)(?:$|[^a-z0-9])", RegexOption.IGNORE_CASE)
        fun adultName(name: String): Boolean = ADULT.containsMatchIn(name)
        fun validPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }
        private fun categoryKey(id: String, kind: String): String? = when {
            id.isBlank() -> null
            kind == "live" -> id
            kind == "movie" || kind == "series" -> "$kind:$id"
            else -> null
        }

        private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val key = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).encoded }
            finally { key.clearPassword() }
        }
        private fun verify(pin: String, stored: String): Boolean {
            if (!validPin(pin)) return false
            return runCatching {
                val fields = stored.split(':')
                if (fields.size != 4 || fields[0] != "v1") return false
                val iterations = fields[1].toInt().takeIf { it in PIN_ITERATIONS..2_000_000 } ?: return false
                val salt = Base64.getUrlDecoder().decode(fields[2]).takeIf { it.size == 16 } ?: return false
                val expected = Base64.getUrlDecoder().decode(fields[3]).takeIf { it.size == 32 } ?: return false
                MessageDigest.isEqual(expected, derive(pin, salt, iterations))
            }.getOrDefault(false)
        }

        private fun <T> ordered(items: List<T>, ids: List<String>, key: (T) -> String): List<T> {
            if (ids.isEmpty()) return items
            val positions = ids.withIndex().associate { it.value to it.index }
            val original = items.withIndex().associate { key(it.value) to it.index }
            return items.sortedWith(compareBy<T>({ positions[key(it)] ?: Int.MAX_VALUE }, { original[key(it)] ?: Int.MAX_VALUE }))
        }
    }
}
