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
             french: Boolean, caps: DeviceCaps, language: (PlayVersion) -> AudioLanguage?, failed: (PlayVersion) -> Boolean): List<PlayVersion> {
        fun guard(v: PlayVersion): List<Any?> = listOf(v.origin, v.rejected, failed(v), language(v),
            VersionRanking.heard(v, prefs, french), VersionRanking.overLimits(v, prefs), v.readiness,
            SourceRules.pictureRank(v.hdr, caps), v.confidence, v.quality, v.community)
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
