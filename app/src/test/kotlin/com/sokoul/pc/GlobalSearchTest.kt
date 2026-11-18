package com.sokoul.pc

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import com.sokoul.pc.search.SearchAggregator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GlobalSearchTest {
    @Test
    fun live_position_reads_legacy_and_programme_anchored_settings() {
        val db = com.sokoul.desktop.data.DesktopDb(Files.createTempDirectory("sokoul-live-position").resolve("test.db").toFile())
        try {
            val catalog = com.sokoul.pc.live.TvCatalog(db)
            db.putSetting("pc.live_last", "News\nchannel-1")
            assertEquals(com.sokoul.pc.live.TvCatalog.Position("News", "channel-1", null), catalog.lastPosition())

            catalog.rememberPosition("News", "channel-2", 1_798_000_000_000L)
            assertEquals(com.sokoul.pc.live.TvCatalog.Position("News", "channel-2", 1_798_000_000_000L), catalog.lastPosition())
        } finally {
            db.close()
        }
    }

    @Test
    fun partial_source_failure_keeps_other_catalog_results() = runBlocking<Unit> {
        val search = SearchAggregator<String, String, String, String>(
            titlesAvailable = { true },
            liveAvailable = { true },
            animeAvailable = { true },
            mangaAvailable = { false },
            searchTitles = { listOf("Dune") },
            searchLive = { listOf("TF1") },
            searchAnime = { throw IllegalStateException("offline") },
            searchManga = { error("unavailable source must not be called") },
        )

        val results = search.search("dune")

        assertEquals(listOf("Dune"), results.titles.items)
        assertEquals(listOf("TF1"), results.live.items)
        assertTrue(results.anime.available)
        assertTrue(results.anime.failed)
        assertFalse(results.manga.available)
        assertFalse(results.isEmpty)
    }

    @Test(expected = CancellationException::class)
    fun cancellation_is_not_reported_as_a_provider_error() = runBlocking<Unit> {
        SearchAggregator<String, String, String, String>(
            searchTitles = { throw CancellationException("query changed") },
            searchLive = { emptyList() },
            searchAnime = { emptyList() },
            searchManga = { emptyList() },
        ).search("old query")
    }

    @Test
    fun films_show_before_a_slow_catalogue_answers() = runBlocking<Unit> {
        val anime = kotlinx.coroutines.CompletableDeferred<List<String>>()
        val seen = mutableListOf<List<String>>()
        val search = SearchAggregator<String, String, String, String>(
            searchTitles = { listOf("Dune") },
            searchLive = { emptyList() },
            searchAnime = { anime.await() },
            searchManga = { emptyList() },
        )
        val job = launch { search.searchEach("dune") { seen += it.titles.items } }
        // The films are there while the anime catalogue has still not answered.
        withTimeout(5_000) { while (seen.none { it == listOf("Dune") }) kotlinx.coroutines.delay(10) }
        assertTrue(job.isActive)
        anime.complete(listOf("Dune (anime)"))
        job.join()
    }
}
