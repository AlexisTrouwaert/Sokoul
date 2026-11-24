package com.sokoul.pc.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

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

enum class SearchScope { All, Titles, Live, Anime, Manga }

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

    /**
     * The same search, shown as it comes: [onUpdate] gets the results after each catalogue answers, so films are on
     * screen while the anime and live TV catalogues are still answering (all four were awaited before, the slowest
     * holding the films back). A catalogue not answered yet is empty in the updates before its own.
     */
    suspend fun searchEach(
        query: String,
        searchTitlesProgressively: (suspend (String, suspend (List<TTitles>) -> Unit) -> List<TTitles>)? = null,
        scope: SearchScope = SearchScope.All,
        onUpdate: (SearchResults<TTitles, TLive, TAnime, TManga>) -> Unit,
    ): SearchResults<TTitles, TLive, TAnime, TManga> = coroutineScope {
        fun includes(source: SearchScope) = scope == SearchScope.All || scope == source
        var now = SearchResults<TTitles, TLive, TAnime, TManga>(
            SearchSourceResults(includes(SearchScope.Titles)), SearchSourceResults(includes(SearchScope.Live)),
            SearchSourceResults(includes(SearchScope.Anime)), SearchSourceResults(includes(SearchScope.Manga)),
        )
        val updates = Mutex()
        val updateDispatcher = coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
        suspend fun publish(change: (SearchResults<TTitles, TLive, TAnime, TManga>) -> SearchResults<TTitles, TLive, TAnime, TManga>) = withContext(updateDispatcher) {
            updates.withLock {
                now = change(now)
                onUpdate(now)
            }
        }
        listOfNotNull(
            if (includes(SearchScope.Titles)) async {
                val result = load(titlesAvailable, { term ->
                    if (searchTitlesProgressively == null) searchTitles(term)
                    else searchTitlesProgressively(term) { first ->
                        if (first.isNotEmpty()) publish { it.copy(titles = SearchSourceResults(true, items = first)) }
                    }
                }, query)
                publish { current ->
                    val titles = if (result.failed) result.copy(items = current.titles.items) else result
                    current.copy(titles = titles)
                }
            } else null,
            if (includes(SearchScope.Live)) async { load(liveAvailable, searchLive, query).let { r -> publish { it.copy(live = r) } } } else null,
            if (includes(SearchScope.Anime)) async { load(animeAvailable, searchAnime, query).let { r -> publish { it.copy(anime = r) } } } else null,
            if (includes(SearchScope.Manga)) async { load(mangaAvailable, searchManga, query).let { r -> publish { it.copy(manga = r) } } } else null,
        ).forEach { it.await() }
        now
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
