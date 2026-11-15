package com.sokoul.pc

import com.sokoul.pc.data.Platform
import com.sokoul.pc.data.PlatformCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformCatalogTest {

    @Test
    fun asks_tmdb_for_the_subscription_catalog_of_the_region() {
        val params = PlatformCatalog.params(Platform.Netflix, "FR")
        assertEquals("8", params["with_watch_providers"])
        assertEquals("FR", params["watch_region"])
        assertEquals("flatrate", params["with_watch_monetization_types"])
        assertEquals("popularity.desc", params["sort_by"])
    }

    @Test
    fun alternates_films_and_series_keeping_their_order() {
        assertEquals(listOf("f1", "s1", "f2", "s2", "s3"), PlatformCatalog.interleave(listOf(listOf("f1", "f2"), listOf("s1", "s2", "s3"))))
        assertEquals(emptyList<String>(), PlatformCatalog.interleave(emptyList<List<String>>()))
    }
}
