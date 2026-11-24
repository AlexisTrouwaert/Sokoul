package com.sokoul.pc.playback.sources
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.desktop.data.PlaybackPrefs
import com.sokoul.iptv.domain.AudioLanguage

/** Explicit ordered preferences apply only within equally eligible playback candidates. */
data class SourcePreferenceOrder(
    val sources: List<String> = SourcePreferences.SOURCES,
    val hosters: List<String> = SourcePreferences.HOSTERS,
    val remembered: Map<String, String> = emptyMap(),
)

object SourcePreferences {
    val SOURCES = listOf("Anime-Sama", "PurStream", "FStream", "Cpasmal", "Wiflix", "Coflix", "Omega", "J1F", "SwiftFlow", "VoirDrama", "KissKH")
    val HOSTERS = listOf("vidmoly", "sibnet", "voe", "fsvid", "vidzy", "uqload", "veev", "doodstream", "lulustream", "vidara", "seekstreaming")
    fun normalize(values: List<String>, known: List<String>) = values.filter { it in known }.distinct() + known.filter { it !in values }
    fun moveFirst(values: List<String>, selected: String) = listOf(selected) + values.filterNot { it == selected }
    fun source(version: PlayVersion): String? = SOURCES.firstOrNull { version.name.substringBefore('·').trim().equals(it, true) }
    fun hoster(version: PlayVersion): String? {
        val value = version.providerHost.orEmpty().lowercase() + " " + version.name.lowercase()
        if ("ansembed" in value) return "vidmoly"
        return HOSTERS.firstOrNull { it in value }
    }
    fun anime(version: PlayVersion) = source(version) == "Anime-Sama" || version.anime

    fun rank(ranked: List<PlayVersion>, order: (Boolean) -> SourcePreferenceOrder, prefs: PlaybackPrefs,
             french: Boolean, caps: DeviceCaps, fact: (PlayVersion) -> SourceFact?, failed: (PlayVersion) -> Boolean,
             workLanguage: String? = null): List<PlayVersion> {
        // The order is read once per ranking: read at each comparison, it was ~14 database reads twice per comparison,
        // 1.6 s for 900 versions on the Chromecast (2026-11-22).
        val orders = HashMap<Boolean, SourcePreferenceOrder>(2)
        val order = { anime: Boolean -> orders.getOrPut(anime) { order(anime) } }
        // Only versions whose audio is judged the same (AudioMatch) are ordered by site and hoster: a foreign dub is
        // never lifted above a plain release by a site priority. Each version's guard is worked out once.
        val guards = java.util.IdentityHashMap<PlayVersion, List<Any?>>()
        fun guard(v: PlayVersion): List<Any?> = guards.getOrPut(v) {
            val audio = AudioMatch.verdict(v, prefs.intent, fact(v), workLanguage)
            listOf(v.origin, v.rejected, failed(v), audio.heard, audio.original, audio.foreignDub, audio.proof,
                VersionRanking.overLimits(v, prefs), v.readiness, SourceRules.pictureRank(v.hdr, caps), v.confidence, v.quality, v.community)
        }
        val result = ArrayList<PlayVersion>()
        var start = 0
        while (start < ranked.size) {
            val key = guard(ranked[start])
            var end = start + 1
            while (end < ranked.size && guard(ranked[end]) == key) end++
            val group = ranked.subList(start, end)
            result += group.sortedWith(compareBy<PlayVersion> { version ->
                val preferred = order(anime(version))
                preferred.sources.indexOf(source(version)).takeIf { it >= 0 } ?: preferred.sources.size
            }.thenBy { version ->
                val preferred = order(anime(version))
                val source = source(version)
                val host = hoster(version)
                if (source != null && host != null && preferred.remembered[source] == host) -1
                else preferred.hosters.indexOf(host).takeIf { it >= 0 } ?: preferred.hosters.size
            })
            start = end
        }
        return result
    }
}
