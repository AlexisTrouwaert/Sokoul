package com.sokoul.pc.data

/** A platform's subscription catalog in one region, from TMDB's watch providers (JustWatch data). */
object PlatformCatalog {
    fun params(platform: Platform, region: String): Map<String, String> = mapOf(
        "with_watch_providers" to platform.providerId.toString(),
        "watch_region" to region,
        "with_watch_monetization_types" to "flatrate",
        "sort_by" to "popularity.desc",
    )

    /** Films and series alternate, each list keeping TMDB's popularity order. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
        return out
    }
}
