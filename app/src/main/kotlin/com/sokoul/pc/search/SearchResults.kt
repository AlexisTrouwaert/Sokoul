package com.sokoul.pc.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class SearchSourceResults<T>(
    val available: Boolean,
    val failed: Boolean = false,
    val items: List<T> = emptyList(),
)

data class SearchResults<TTitles, TLive, TAnime, TManga>(
    val titles: SearchSourceResults<TTitles>,
    val live: SearchSourceResults<TLive>,
    val anime: SearchSourceResults<TAnime>,
    val manga: SearchSourceResults<TManga>,
) {
    val isEmpty: Boolean get() = titles.items.isEmpty() && live.items.isEmpty() && anime.items.isEmpty() && manga.items.isEmpty()
}

/** Queries independent catalogs concurrently and preserves successful sources when another one fails. */
class SearchAggregator<TTitles, TLive, TAnime, TManga>(
    private val titlesAvailable: suspend () -> Boolean = { true },
    private val liveAvailable: suspend () -> Boolean = { true },
    private val animeAvailable: suspend () -> Boolean = { true },
    private val mangaAvailable: suspend () -> Boolean = { true },
    private val searchTitles: suspend (String) -> List<TTitles>,
    private val searchLive: suspend (String) -> List<TLive>,
    private val searchAnime: suspend (String) -> List<TAnime>,
    private val searchManga: suspend (String) -> List<TManga>,
) {
    suspend fun search(query: String): SearchResults<TTitles, TLive, TAnime, TManga> = coroutineScope {
        val titles = async { load(titlesAvailable, searchTitles, query) }
        val live = async { load(liveAvailable, searchLive, query) }
        val anime = async { load(animeAvailable, searchAnime, query) }
        val manga = async { load(mangaAvailable, searchManga, query) }
        SearchResults(titles.await(), live.await(), anime.await(), manga.await())
    }

    private suspend fun <T> load(
        available: suspend () -> Boolean,
        search: suspend (String) -> List<T>,
        query: String,
    ): SearchSourceResults<T> {
        return try {
            withContext(Dispatchers.IO) {
                if (!available()) SearchSourceResults<T>(available = false)
                else SearchSourceResults(available = true, items = search(query))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SearchSourceResults(available = true, failed = true)
        }
    }
}
