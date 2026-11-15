package com.sokoul.pc.live

import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.DesktopDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class TvZapScope { All, CurrentGroup, Favorites, ActiveCollection }

@Serializable
data class TvCollectionMember(
    val id: String, val channelId: String, val nameKey: String, val epgKey: String?,
    val region: String, val label: String,
)

@Serializable
data class TvCollection(val id: String, val name: String, val members: List<TvCollectionMember> = emptyList())

@Serializable
data class TvPersonalState(
    val version: Int = 1,
    val collections: List<TvCollection> = emptyList(),
    val activeCollectionId: String? = null,
    val favoritesPinned: Boolean = true,
    val zapScope: TvZapScope = TvZapScope.CurrentGroup,
)

/** Local TV preferences. A single settings value makes every edit atomic without a schema migration. */
class TvPersonalization(
    private val db: DesktopDb,
    private val tv: TvCatalog,
    private val profileId: () -> String,
    private val kids: () -> Boolean = { false },
) {
    data class Member(val reference: TvCollectionMember, val channel: Channel?)
    private val json = Json { ignoreUnknownKeys = true }
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private fun key(): String? = tv.playlist()?.let { "tv.personal.v1.${it.id}@${profileId()}" }

    @Synchronized fun state(): TvPersonalState = key()?.let { db.setting(it) }?.let {
        runCatching { json.decodeFromString<TvPersonalState>(it) }.getOrNull()
    }?.takeIf { it.version == 1 } ?: TvPersonalState()

    @Synchronized private fun edit(update: (TvPersonalState) -> TvPersonalState) {
        val key = key() ?: return
        val before = state()
        val after = update(before)
        if (before == after) return
        db.putSetting(key, json.encodeToString(after))
        changes.value++
    }

    private fun checkedName(name: String, except: String? = null): String {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 40 && clean.none { it.isISOControl() }) { "Invalid collection name" }
        require(state().collections.none { it.id != except && it.name.equals(clean, true) }) { "Collection name already exists" }
        return clean
    }

    @Synchronized fun create(name: String): String {
        val clean = checkedName(name)
        val id = UUID.randomUUID().toString()
        edit { it.copy(collections = it.collections + TvCollection(id, clean)) }
        return id
    }

    @Synchronized fun rename(id: String, name: String) {
        val clean = checkedName(name, id)
        edit { s -> s.copy(collections = s.collections.map { if (it.id == id) it.copy(name = clean) else it }) }
    }

    @Synchronized fun delete(id: String) = edit { s -> s.copy(
        collections = s.collections.filterNot { it.id == id },
        activeCollectionId = s.activeCollectionId?.takeUnless { it == id },
    ) }

    @Synchronized fun add(id: String, channel: Channel) {
        if (channel.playlistId != tv.playlist()?.id || !tv.organization.canAccess(channel)) return
        edit { s -> s.copy(collections = s.collections.map { collection ->
            if (collection.id != id || collection.members.any { member ->
                member.channelId == channel.id || (member.nameKey == channel.nameKey && member.region == TvCatalog.region(channel.name))
            }) collection else collection.copy(members = collection.members + TvCollectionMember(
                UUID.randomUUID().toString(), channel.id, channel.nameKey, channel.epgKey,
                TvCatalog.region(channel.name), channel.name,
            ))
        }) }
    }

    @Synchronized fun remove(id: String, memberId: String) = edit { s -> s.copy(collections = s.collections.map {
        if (it.id == id) it.copy(members = it.members.filterNot { m -> m.id == memberId }) else it
    }) }

    /** Commit a provisional order only if no concurrent membership change would be lost. */
    @Synchronized fun reorder(id: String, memberIds: List<String>): Boolean {
        val collection = state().collections.firstOrNull { it.id == id } ?: return false
        if (memberIds.size != collection.members.size || memberIds.toSet() != collection.members.map { it.id }.toSet()) return false
        val byId = collection.members.associateBy { it.id }
        edit { s -> s.copy(collections = s.collections.map { if (it.id == id) it.copy(members = memberIds.map(byId::getValue)) else it }) }
        return true
    }

    @Synchronized fun activate(id: String?) = edit { s -> s.copy(activeCollectionId = id?.takeIf { candidate -> s.collections.any { it.id == candidate } }) }
    fun setPinned(pinned: Boolean) = edit { it.copy(favoritesPinned = pinned) }
    fun setZapScope(scope: TvZapScope) = edit { it.copy(zapScope = scope) }

    /** Never guess across a source, region, or ambiguous programme identifier. Missing members remain editable. */
    fun members(id: String): List<Member> {
        val pid = tv.playlist()?.id ?: return emptyList()
        val collection = state().collections.firstOrNull { it.id == id } ?: return emptyList()
        val exact = collection.members.map { it.channelId }.chunked(400).flatMap { db.channelsByIds(pid, it) }.associateBy { it.id }
        return collection.members.mapNotNull { ref ->
            val candidate = exact[ref.channelId] ?: run {
                val family = db.channelsByNameKey(pid, ref.nameKey).filter { TvCatalog.region(it.name) == ref.region }
                val byEpg = ref.epgKey?.takeIf { it.isNotBlank() }?.let { db.channelsByEpgKey(pid, it) }
                    .orEmpty().filter { TvCatalog.region(it.name) == ref.region }
                val pool = family.ifEmpty { byEpg }
                if (pool.map { it.nameKey }.distinct().size == 1) tv.preferredChannels(pool).firstOrNull() else null
            }
            when {
                candidate != null && !tv.organization.canAccess(candidate) -> null
                candidate == null && kids() -> null
                else -> Member(ref, candidate)
            }
        }
    }

    fun channels(id: String): List<Channel> = members(id).mapNotNull { it.channel }.distinctBy { it.id }

    fun groups(): List<com.sokoul.desktop.data.LiveLogic.Group> {
        if (tv.playlist() == null) return emptyList()
        val state = state()
        val favorite = com.sokoul.desktop.data.LiveLogic.Group(com.sokoul.desktop.data.SpecialGroup.FAVORITES, "Favorites", false)
        val regular = tv.groups().filterNot { it.id == favorite.id }
        val collections = state.collections.map { com.sokoul.desktop.data.LiveLogic.Group(group(it.id), it.name, false) }
        return if (state.favoritesPinned) listOf(favorite) + collections + regular else collections + regular + favorite
    }

    companion object {
        const val GROUP_PREFIX = "__collection:"
        fun group(id: String) = GROUP_PREFIX + id
        fun collectionId(group: String?) = group?.takeIf { it.startsWith(GROUP_PREFIX) }?.removePrefix(GROUP_PREFIX)
    }
}
