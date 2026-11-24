package com.sokoul.pc.control

import com.sokoul.desktop.data.AudioPreference
import com.sokoul.desktop.data.BufferProfile
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.desktop.data.MaxQuality
import com.sokoul.desktop.data.MaxSize
import com.sokoul.desktop.data.PreferredOrigin
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.data.StartMode
import com.sokoul.desktop.data.SubtitlePreference
import com.sokoul.iptv.data.net.TmdbInfo
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.iptv.data.net.TmdbPerson
import com.sokoul.pc.AppDeps
import com.sokoul.pc.collections.roomsOf
import com.sokoul.pc.data.SagaRef
import com.sokoul.pc.data.SagaTile
import com.sokoul.pc.data.kind
import com.sokoul.pc.data.trailerVideos
import com.sokoul.pc.playback.session.Phase
import com.sokoul.pc.playback.session.PlayFailure
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.session.PlayVersion
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What the interface can be asked to do. The main screen provides it, as it holds the navigation. */
interface AppNavigation {
    fun openAnimeCalendar()
    fun openReader(request: com.sokoul.pc.data.anime.ReaderRequest)
    fun playLive(channel: com.sokoul.pc.playback.session.LiveChannel, around: List<com.sokoul.pc.playback.session.LiveChannel>)
    val section: String
    /** The pages open above the section, the top one last. */
    val pages: List<String>
    fun goTo(section: String)
    fun openTitle(items: List<TmdbItem>, index: Int)
    fun openSaga(tile: SagaTile)
    fun openPerson(person: TmdbPerson)
    fun play(request: PlayRequest)
    fun playTrailer(title: String, key: String)
    fun back()
}

/**
 * The app as tools and a description of its state: the same door for the live checks and for a future assistant.
 * Addresses are never included, because a stream's link can carry a token, and no tool reads or changes a key.
 */
class AppBridge(private val deps: AppDeps) {
    var navigation: AppNavigation? = null

    private fun nav() = navigation ?: throw ToolProblem("The interface is not ready")

    private fun failureName(f: PlayFailure?): String? = when (f) {
        null -> null
        is PlayFailure.Refused -> "Refused(${f.code})"
        is PlayFailure.ConnectionsFull -> "ConnectionsFull(${f.active}/${f.max})"
        is PlayFailure.Cooldown -> "Cooldown(${f.seconds})"
        is PlayFailure.NotReady -> "NotReady(${f.percent})"
        else -> f.javaClass.simpleName
    }

    private fun JsonObjectBuilder.phase(p: Phase) {
        put("name", p.javaClass.simpleName)
        when (p) {
            is Phase.Searching -> put("found", p.found)
            is Phase.Choosing -> put("failure", failureName(p.failure))
            is Phase.Preparing -> {
                put("version", p.version.summary())
                put("position", p.position)
                put("total", p.total)
            }
            is Phase.Downloading -> {
                put("version", p.version.summary())
                put("position", p.position)
                put("total", p.total)
                put("percent", p.transfer.percent)
                put("speedBps", p.transfer.speedBps)
                put("seeders", p.transfer.seeders)
                put("etaSeconds", p.transfer.etaSeconds)
            }
            is Phase.Tuning -> put("channel", p.title)
            is Phase.Opening -> {
                put("version", p.version.summary())
                put("position", p.position)
                put("total", p.total)
            }
            is Phase.Playing -> put("version", p.version?.summary())
            is Phase.Failed -> {
                put("failure", failureName(p.failure))
                put("version", p.version?.summary())
            }
            Phase.Idle, Phase.Finished -> Unit
        }
    }

    private fun tracks(list: List<Pair<Int, String>>) = buildJsonArray {
        list.forEach { (id, label) ->
            add(
                buildJsonObject {
                    put("id", id)
                    put("label", label)
                },
            )
        }
    }

    /** What the app shows and does now. */
    fun state(): JsonObject = buildJsonObject {
        val n = navigation
        put("section", n?.section)
        put("pages", buildJsonArray { n?.pages?.forEach { add(it) } })
        val session = deps.playback
        putJsonObject("player") {
            putJsonObject("phase") { phase(session.phase) }
            put("title", session.request?.title)
            put("current", session.current?.summary())
            put("versions", session.versions.size)
            put("searching", session.searching)
            put("failures", session.failures.size)
            // The active version lacks every wanted language: the first wanted one and the track heard.
            session.languageNotice?.let { n -> putJsonObject("languageNotice") { put("wanted", n.wanted); put("heard", n.heard) } }
            put("skips", session.skips.joinToString { (if (it.opening) "op " else "ed ") + it.start.toInt() + "-" + it.end.toInt() + "/" + it.episodeLength.toInt() })
            session.upgrade?.let { u ->
                putJsonObject("upgrade") {
                    put("version", u.version.summary())
                    put("percent", u.transfer?.percent)
                    put("ready", u.stream != null)
                }
            }
            deps.engineStatus()?.let { st ->
                putJsonObject("engine") {
                    put("session", st.session)
                    put("volume", st.volume)
                    put("muted", st.muted)
                    put("rate", st.rate)
                    put("playing", st.playing)
                    put("paused", st.paused)
                    put("buffering", st.buffering)
                    put("bufferPercent", st.bufferPercent)
                    put("ended", st.ended)
                    put("hasError", st.error != null)
                    put("timeMs", st.timeMs)
                    put("lengthMs", st.lengthMs)
                    put("video", st.video)
                    put("volume", st.volume)
                    put("muted", st.muted)
                    put("audioId", st.audioId)
                    put("audio", tracks(st.audio))
                    put("subtitleId", st.subtitleId)
                    put("subtitles", tracks(st.subtitles))
                }
            }
        }
        deps.subtitles?.let { s ->
            putJsonObject("subtitles") {
                put("available", s.available())
                put("searching", s.searching)
                put("found", s.results.size)
                put("active", s.active?.let { "${it.service}:${it.language}" })
                put("delayMs", s.delayMs)
                put("line", deps.engineStatus()?.timeMs?.let(s::lineAt))
            }
        }
        putJsonObject("live") {
            put("channel", deps.playback.live?.title)
            put("previewKey", deps.playback.previewKey)
            put("notice", deps.playback.liveNotice)
            put("suggestion", deps.playback.liveSuggestion?.title)
            deps.tvReport?.let { r ->
                putJsonObject("guide") {
                    put("group", r.group)
                    put("level", r.level)
                    put("drawerOpen", r.drawerOpen)
                    put("row", r.row)
                    put("channels", r.channels)
                    put("channel", r.channel)
                    put("program", r.program)
                    put("focused", r.focused)
                }
            }
        }
        val reading = deps.playback.reading
        putJsonObject("stream") {
            put("health", reading.health.name)
            reading.sample?.let { s ->
                put("width", s.width)
                put("height", s.height)
                put("fps", s.framesPerSecond)
                put("videoCodec", s.videoCodec)
                put("audioCodec", s.audioCodec)
                put("audioHz", s.audioHz)
                put("audioChannels", s.audioChannels)
                put("kbps", reading.averageKilobits)
                put("displayed", s.displayed)
                put("lost", s.lost)
            }
        }
        val t = deps.trailer
        putJsonObject("trailer") {
            put("playing", t.playing)
            put("pending", t.pending)
            put("key", t.currentStream?.key)
            put("method", t.currentStream?.method)
            put("height", t.currentStream?.height)
        }
        put("reminders", deps.reminders.items.size)
        deps.animeReport?.let { r ->
            putJsonObject("anime") {
                put("view", r.view)
                put("categories", r.categories)
                put("shownInGrid", r.shownInGrid)
                put("pinned", r.pinned.joinToString())
                put("detail", r.detail)
            }
        }
        deps.readerReport?.let { r ->
            putJsonObject("reader") {
                put("chapter", r.chapter)
                put("page", r.page)
                put("total", r.total)
                put("mode", r.mode)
                put("rightToLeft", r.rightToLeft)
                put("fit", r.fit)
                put("controls", r.controls)
                put("panel", r.panel)
                put("autoSpeed", r.autoSpeed)
                put("dim", r.dim)
                put("zoom", r.zoom)
                put("chapterIndex", r.chapterIndex)
                put("chapters", r.chapters)
            }
        }
        put("clip", deps.playback.clip?.title)
        put("preferences", preferences())
        put("services", buildJsonObject { ProviderConfig.statuses(deps.db).forEach { put(it.id, it.configured) } })
        val runtime = Runtime.getRuntime()
        put("heapMb", (runtime.totalMemory() - runtime.freeMemory()) / MB)
    }

    private fun preferences() = buildJsonObject {
        val p = deps.prefs
        put("start", p.startMode.name)
        val intent = p.audioIntent
        // Wanted audio languages in order ("it,original"), what happens when none is there, and the subtitle mode.
        put("audio", intent.encode())
        put("audioFallback", intent.fallback.name)
        put("subtitles", intent.subtitles)
        put("maxQuality", p.maxQuality.name)
        put("maxSize", p.maxSize.name)
        put("buffer", p.buffer.name)
        put("origin", p.preferredOrigin.name)
    }

    private fun itemOf(kind: String, id: Int, info: TmdbInfo) =
        TmdbItem(id, info.title, info.originalTitle, info.year, info.poster, info.backdrop, info.rating, info.overview, info.genreIds, info.originalLanguage, info.voteCount, kind)

    private suspend fun infoOf(args: JsonObject): Pair<String, TmdbInfo> {
        val kind = args.text("kind")
        return kind to (deps.catalog.details(kind, args.number("id")) ?: throw ToolProblem("No such title"))
    }

    private fun clean(name: String) = name.lowercase().trim()

    /** The curated sagas and their parts first, then the full list. */
    private suspend fun findSaga(name: String): SagaTile {
        val wanted = clean(name)
        val page = deps.catalog.collections()
        (page.curated + page.universes.flatMap { it.tiles }).firstOrNull { clean(it.name) == wanted || clean(it.key) == wanted }?.let { return it }
        val ref: SagaRef = deps.catalog.allSagas().firstOrNull { clean(com.sokoul.pc.data.CollectionRules.cleanName(it.name)) == wanted || it.id.toString() == name }
            ?: throw ToolProblem("No saga named $name")
        return deps.catalog.sagaTile(ref) ?: throw ToolProblem("Saga $name did not load")
    }

    private fun versionAt(args: JsonObject): PlayVersion =
        deps.playback.versions.getOrNull(args.number("index")) ?: throw ToolProblem("No version at this index")

    private val ok: JsonElement = JsonPrimitive("ok")

    val registry: ToolRegistry by lazy { ToolRegistry(readTools() + navigationTools() + playbackTools() + settingTools()) }

    private fun readTools() = listOf(
        AppTool("get_state", "What the app shows and does right now.", ToolRisk.Read) { state() },
        AppTool("search_titles", "Search films and series by name.", ToolRisk.Read, listOf(ToolParam("query", "Words to search for"))) { a ->
            buildJsonArray {
                // What the search screen shows (three pages), so the tool sees what the viewer sees.
                deps.catalog.searchMore(a.text("query")).take(3 * MAX_LISTED).forEach { i ->
                    add(
                        buildJsonObject {
                            put("kind", i.kind())
                            put("id", i.id)
                            put("title", i.title)
                            put("year", i.year)
                        },
                    )
                }
            }
        },
        AppTool("title_info", "Everything known about a film or series: story, ratings, trailers, saga, where to watch.", ToolRisk.Read, titleParams()) { a ->
            val (kind, info) = infoOf(a)
            val id = a.number("id")
            val extras = deps.catalog.titleExtras(kind, id, info)
            val saga = deps.catalog.sagaOfTitle(kind, id, info)
            buildJsonObject {
                put("title", info.title)
                put("originalLanguage", info.originalLanguage)
                put("year", info.year)
                put("overview", info.overview)
                put("runtimeMin", info.runtimeMin)
                put("seasons", info.seasons)
                put("genres", buildJsonArray { info.genres.forEach { add(it) } })
                put("director", info.director)
                put("saga", saga?.name)
                put("imdb", extras.omdb?.imdb)
                put("rottenTomatoes", extras.omdb?.rottenTomatoes)
                put("metacritic", extras.omdb?.metacritic)
                put("offers", buildJsonArray { extras.offers.forEach { add(it.service) } })
                put("nextEpisode", extras.next?.let { "S${it.season}E${it.episode} ${it.date}" })
                put(
                    "trailers",
                    buildJsonArray {
                        info.trailerVideos().forEach { v ->
                            add(
                                buildJsonObject {
                                    put("key", v.key)
                                    put("name", v.name)
                                    put("type", v.type)
                                    put("language", v.language)
                                    put("size", v.size)
                                    put("official", v.official)
                                },
                            )
                        }
                    },
                )
            }
        },
        AppTool("season_info", "The episodes of a season.", ToolRisk.Read, listOf(ToolParam("id", "TMDB id of the series", "integer"), ToolParam("season", "Season number", "integer"))) { a ->
            buildJsonArray {
                deps.catalog.season(a.number("id"), a.number("season")).forEach { e ->
                    add(
                        buildJsonObject {
                            put("episode", e.episode)
                            put("name", e.name)
                            put("hasOverview", !e.overview.isNullOrBlank())
                            put("hasImage", e.still != null)
                        },
                    )
                }
            }
        },
        AppTool("saga_info", "Everything gathered about a saga.", ToolRisk.Read, listOf(ToolParam("name", "Name of the saga"))) { a ->
            val tile = findSaga(a.text("name"))
            val p = deps.catalog.sagaProfile(tile)
            val series = deps.catalog.sagaDetail(tile).series
            buildJsonObject {
                put("name", tile.name)
                put("category", tile.category.name)
                put("hasLogo", tile.logo != null)
                put("storyOrder", buildJsonArray { if (tile.chronological.isNotEmpty()) com.sokoul.pc.data.SagaTimeline.ordered(deps.catalog.sagaDetail(tile).films, tile.chronological, true).forEach { add(it.title) } })
                put("films", buildJsonArray { p.films.forEach { add(it.film.title) } })
                put("rooms", buildJsonArray { roomsOf(p, series).forEach { add(it.name) } })
                put("overview", p.overview)
                put("overviewSource", p.overviewSource)
                put("boxOffice", p.boxOffice)
                put("budget", p.budget)
                put("runtimeMin", p.runtimeMin)
                put("imdb", p.imdb)
                put("rottenTomatoes", p.rottenTomatoes)
                put("metacritic", p.metacritic)
                put("oscarsWon", p.oscarsWon)
                put("awardsWon", p.awardsWon)
                put("cast", p.cast.size)
                put("directors", p.directorPeople.size)
                put("trailers", p.trailers.size)
                put("gallery", p.gallery.size)
                put("similar", p.similar.size)
                put("series", series.size)
            }
        },
        AppTool("list_sagas", "Every saga, by category.", ToolRisk.Read) {
            val page = deps.catalog.collections()
            buildJsonObject {
                put("curated", buildJsonArray { page.curated.forEach { add(it.name) } })
                put(
                    "shelves",
                    buildJsonObject {
                        page.shelves.forEach { shelf ->
                            put(shelf.category.name, buildJsonArray { shelf.entries.forEach { e -> add(e.tile?.name ?: e.ref?.let { r -> com.sokoul.pc.data.CollectionRules.cleanName(r.name) }) } })
                        }
                    },
                )
            }
        },
        AppTool("list_versions", "The versions of the title being played, best first, with their state.", ToolRisk.Read) {
            val session = deps.playback
            buildJsonArray {
                session.versions.forEachIndexed { i, v ->
                    add(
                        buildJsonObject {
                            put("index", i)
                            put("summary", v.summary())
                            put("name", v.name)
                            put("rejected", v.rejected?.name)
                            put("confidence", v.confidence)
                            put("origin", v.origin.name)
                            put("readiness", v.readiness.name)
                            put("seeders", v.seeders)
                            put("failure", failureName(session.failures[v.key]))
                            put("current", session.current?.key == v.key)
                        },
                    )
                }
            }
        },
        AppTool("source_timings", "When each step of the last search for versions ended.", ToolRisk.Read) {
            buildJsonArray { deps.sourceTimeline().forEach { add(it) } }
        },
        AppTool("list_subtitles", "The subtitles found online for the title being played.", ToolRisk.Read) {
            val s = deps.subtitles ?: throw ToolProblem("No subtitle service")
            buildJsonArray {
                s.results.forEachIndexed { i, r ->
                    add(
                        buildJsonObject {
                            put("index", i)
                            put("service", r.service)
                            put("language", r.language)
                            put("release", r.release)
                        },
                    )
                }
            }
        },
    )

    private fun titleParams() = listOf(ToolParam("kind", "Film or series", choices = listOf("movie", "tv")), ToolParam("id", "TMDB id", "integer"))

    private fun navigationTools() = listOf(
        AppTool("go_to", "Open a section of the app.", ToolRisk.Navigate, listOf(ToolParam("section", "The section", choices = listOf("home", "library", "movies", "series", "anime", "collections", "tv", "sports", "search", "settings")))) { a ->
            nav().goTo(a.text("section"))
            ok
        },
        AppTool("open_title", "Open the page of a film or series.", ToolRisk.Navigate, titleParams()) { a ->
            val (kind, info) = infoOf(a)
            nav().openTitle(listOf(itemOf(kind, a.number("id"), info)), 0)
            ok
        },
        AppTool("open_saga", "Open the page of a saga.", ToolRisk.Navigate, listOf(ToolParam("name", "Name of the saga"))) { a ->
            nav().openSaga(findSaga(a.text("name")))
            ok
        },
        AppTool("open_person", "Open the page of an actor or director.", ToolRisk.Navigate, listOf(ToolParam("id", "TMDB id of the person", "integer"), ToolParam("name", "Their name"))) { a ->
            nav().openPerson(TmdbPerson(a.text("name"), null, null, a.number("id")))
            ok
        },
        AppTool("back", "Close the page on top.", ToolRisk.Navigate) {
            nav().back()
            ok
        },
    )

    private fun playbackTools() = listOf(
        AppTool(
            "play", "Play a film or an episode. Uses an IPTV connection when that source is chosen.", ToolRisk.Playback,
            titleParams() + listOf(
                ToolParam("season", "Season of an episode", "integer", required = false),
                ToolParam("episode", "Episode number", "integer", required = false),
                ToolParam("choose_first", "Open the list of versions instead of starting", "boolean", required = false),
            ),
        ) { a ->
            val (kind, info) = infoOf(a)
            val series = kind == "tv"
            nav().play(
                PlayRequest(
                    kind, a.number("id"), a.numberOr("season") ?: if (series) 1 else null, a.numberOr("episode") ?: if (series) 1 else null,
                    info.title, info.title, info.backdrop, chooseFirst = a.textOr("choose_first") == "true",
                ),
            )
            ok
        },
        AppTool("list_channels", "The live TV groups, or the channels of one group with what is on now.", ToolRisk.Read, listOf(ToolParam("group", "Id of a group", required = false))) { a ->
            val group = a.textOr("group")
            if (group == null) {
                buildJsonArray { deps.tv.groups().forEach { g -> add(buildJsonObject { put("id", g.id); put("name", g.name) }) } }
            } else {
                val channels = deps.tv.channels(group)
                val programs = deps.tv.programs(channels.take(CHANNEL_LIST_LIMIT))
                buildJsonArray {
                    channels.take(CHANNEL_LIST_LIMIT).forEach { c ->
                        add(buildJsonObject { put("id", c.id); put("number", c.number); put("name", c.name); put("now", programs[c.id]?.now?.title) })
                    }
                }
            }
        },
        AppTool("play_channel", "Watch a live channel full screen.", ToolRisk.Playback, listOf(ToolParam("group", "Id of its group"), ToolParam("id", "Id of the channel"))) { a ->
            val around = deps.tv.channels(a.text("group")).mapNotNull(deps.tv::toLive)
            val channel = around.firstOrNull { it.key == a.text("id") } ?: throw ToolProblem("No such channel")
            nav().playLive(channel, around)
            ok
        },
        AppTool("iptv_connections", "How many connections the IPTV account allows and has in use.", ToolRisk.Read, emptyList()) { _ ->
            val use = deps.connectionUse()
            buildJsonObject {
                put("active", use?.first)
                put("max", use?.second)
            }
        },
        AppTool("channel_streams", "The other streams of the channel playing, in every quality the provider has.", ToolRisk.Read, emptyList()) { _ ->
            buildJsonArray { deps.playback.live?.variants()?.forEach { add(buildJsonObject { put("id", it.key); put("name", it.title) }) } }
        },
        AppTool("short_guide_check", "Of the channels of a group without a guide, how many the provider's short guide covers.", ToolRisk.Read, listOf(ToolParam("group", "Id of a group"))) { a ->
            val channels = deps.tv.channels(a.text("group"))
            val known = deps.tv.schedules(channels)
            val lacking = channels.filter { known[it.id].isNullOrEmpty() }
            val found = deps.tv.fillShortGuide(lacking) { emptyList() }
            buildJsonObject {
                put("channels", channels.size)
                put("lacking", lacking.size)
                put("asked", minOf(lacking.size, 8))
                put("found", found.size)
                put("example", found.entries.firstOrNull()?.let { (id, list) -> "${channels.first { it.id == id }.name}: ${list.first().title}" })
            }
        },
        AppTool("anime_check", "What the anime sources give for a work, by name.", ToolRisk.Read, listOf(ToolParam("name", "Name of the work", required = false))) { a ->
            val anime = deps.anime ?: throw ToolProblem("No anime catalog")
            val name = a.textOr("name")
            val trending = anime.client.titles(com.sokoul.pc.data.anime.AniQuery(com.sokoul.pc.data.anime.MediaKind.ANIME))
            val work = name?.let { anime.client.find(it, com.sokoul.pc.data.anime.MediaKind.ANIME) } ?: trending.first()
            val link = anime.linkOf(work)
            val chapters = link.manga?.let { anime.chaptersOf(it) }.orEmpty()
            val kinds = anime.episodeKinds(link)
            buildJsonObject {
                put("work", work.title)
                put("categories", com.sokoul.pc.data.anime.AnimeCategories.all(anime.client.tags()).size)
                put("manga", link.manga?.title)
                put("readableChapters", chapters.size)
                put("firstChapter", chapters.firstOrNull()?.id)
                put("themes", link.themes.size)
                put("firstTheme", link.themes.firstOrNull()?.let { "${it.slug} ${it.song} ep ${it.episodes}" })
                put("seasons", link.seasons.size)
                put("wiki", anime.wikiOf(work))
                put("episode1Chapters", anime.chaptersOf(link, 1).joinToString(","))
                put("episode30Chapters", anime.chaptersOf(link, 30).joinToString(","))
                put("fillerEpisodes", kinds.count { it.value == com.sokoul.pc.data.anime.EpisodeKind.Filler })
                put("knownEpisodeKinds", kinds.size)
                put("officialLinks", link.mangaLinks.joinToString(", ") { it.site })
            }
        },
        AppTool("open_anime_calendar", "Open the week of anime episodes.", ToolRisk.Navigate) {
            nav().openAnimeCalendar()
            ok
        },
        AppTool("forget_reading", "Forget manga being read, by the names in the row.", ToolRisk.Navigate, listOf(ToolParam("names", "Comma-separated titles"))) { a ->
            val anime = deps.anime ?: throw ToolProblem("No anime catalog")
            val names = a.text("names").split(',').map { it.trim() }
            anime.forgetReading(anime.readingList().filter { it.title in names }.map { it.key }.toSet())
            ok
        },
        AppTool("open_manga", "Open a manga in the reader, by the name of its work.", ToolRisk.Navigate, listOf(ToolParam("name", "Name of the work"), ToolParam("chapter", "Chapter index", "integer", required = false))) { a ->
            val anime = deps.anime ?: throw ToolProblem("No anime catalog")
            val book = anime.client.find(a.text("name"), com.sokoul.pc.data.anime.MediaKind.MANGA) ?: throw ToolProblem("No such manga")
            val chapters = anime.chaptersOf(book)
            if (chapters.isEmpty()) throw ToolProblem("No readable chapter")
            nav().openReader(anime.readerRequest(book, chapters, (a.numberOr("chapter") ?: 0).coerceIn(chapters.indices), 0))
            ok
        },
        AppTool("search_tv", "Find channels and programmes by name.", ToolRisk.Read, listOf(ToolParam("query", "What to look for"))) { a ->
            buildJsonArray {
                deps.tv.search(a.text("query")).take(MAX_LISTED).forEach { h ->
                    add(buildJsonObject { put("channel", h.channel.name); put("program", h.program?.title); put("start", h.program?.start) })
                }
            }
        },
        AppTool("zap", "Go to the next or previous channel.", ToolRisk.Playback, listOf(ToolParam("delta", "1 for next, -1 for previous", "integer"))) { a ->
            deps.playback.zap(a.number("delta"))
            ok
        },
        AppTool("preview_channel", "Show a channel muted in the TV section.", ToolRisk.Playback, listOf(ToolParam("group", "Id of its group"), ToolParam("id", "Id of the channel"))) { a ->
            val channel = deps.tv.channels(a.text("group")).firstOrNull { it.id == a.text("id") }?.let(deps.tv::toLive) ?: throw ToolProblem("No such channel")
            deps.playback.preview(channel)
            ok
        },
        AppTool("play_trailer", "Play a trailer of a title full screen, the best one unless told which.", ToolRisk.Playback, titleParams() + ToolParam("index", "Which trailer", "integer", required = false)) { a ->
            val (_, info) = infoOf(a)
            val video = info.trailerVideos().getOrNull(a.numberOr("index") ?: 0) ?: throw ToolProblem("No such trailer")
            nav().playTrailer(info.title, video.key)
            buildJsonObject {
                put("key", video.key)
                put("language", video.language)
            }
        },
        AppTool("choose_version", "Play a version from the list, waiting for it if it must download.", ToolRisk.Playback, listOf(ToolParam("index", "Its place in list_versions", "integer"))) { a ->
            deps.playback.choose(versionAt(a))
            ok
        },
        AppTool("next_version", "Move on to the next version, at the same position.", ToolRisk.Playback) {
            deps.playback.nextVersion()
            ok
        },
        AppTool("retry", "Try again with every version back in the running.", ToolRisk.Playback) {
            deps.playback.retry()
            ok
        },
        AppTool("refresh_versions", "Search for versions again, ignoring what was remembered.", ToolRisk.Playback) {
            deps.playback.refresh()
            ok
        },
        AppTool("accept_upgrade", "Move to the better version on offer, at the same position.", ToolRisk.Playback) {
            deps.playback.acceptUpgrade()
            ok
        },
        AppTool("pause", "Pause or resume.", ToolRisk.Playback) {
            (deps.player ?: throw ToolProblem("No player")).togglePause()
            ok
        },
        AppTool("seek", "Move in the film by a number of seconds.", ToolRisk.Playback, listOf(ToolParam("seconds", "Positive goes forward", "integer"))) { a ->
            (deps.player ?: throw ToolProblem("No player")).seekBy(a.number("seconds") * MS)
            ok
        },
        AppTool("choose_audio", "Choose an audio track of the stream.", ToolRisk.Playback, listOf(ToolParam("id", "Its id in the state", "integer"))) { a ->
            (deps.player ?: throw ToolProblem("No player")).setAudio(a.number("id"))
            ok
        },
        AppTool("load_subtitles", "Search for subtitles online for the title being played.", ToolRisk.Playback) {
            val target = deps.playback.request ?: throw ToolProblem("Nothing is playing")
            (deps.subtitles ?: throw ToolProblem("No subtitle service")).load(target)
            ok
        },
        AppTool("choose_subtitle", "Show a subtitle found online. Uses a download of the day's quota.", ToolRisk.Playback, listOf(ToolParam("index", "Its place in list_subtitles", "integer"))) { a ->
            val s = deps.subtitles ?: throw ToolProblem("No subtitle service")
            val r = s.results.getOrNull(a.number("index")) ?: throw ToolProblem("No subtitle at this index")
            deps.player?.setSubtitles(-1)
            s.choose(r)
            ok
        },
        AppTool("clear_subtitles", "Stop showing subtitles.", ToolRisk.Playback) {
            deps.subtitles?.clear()
            deps.player?.setSubtitles(-1)
            ok
        },
        AppTool("shift_subtitles", "Show the subtitles later (positive) or earlier.", ToolRisk.Playback, listOf(ToolParam("ms", "Milliseconds", "integer"))) { a ->
            (deps.subtitles ?: throw ToolProblem("No subtitle service")).shift(a.number("ms").toLong())
            ok
        },
    )

    private fun settingTools() = listOf(
        AppTool(
            "set_preference", "Change a playback preference.", ToolRisk.Setting,
            listOf(
                ToolParam("name", "The preference", choices = listOf("start", "audio", "audioFallback", "subtitles", "maxQuality", "maxSize", "buffer", "origin")),
                ToolParam("value", "The value as get_state shows it; audio is ISO 639-1 codes in order, \"original\" for the work's language (\"it,original\"); subtitles is off, auto or a code"),
            ),
        ) { a ->
            setPreference(deps.prefs, a.text("name"), a.text("value"))
            preferences()
        },
        // A TV cannot read the PC's access.key: this sets the Sokoul server it uses, without typing it with the remote.
        AppTool(
            "set_sokoul_server", "Set the address and the access key of the Sokoul server (Mainapi). The key is never returned.", ToolRisk.Setting,
            listOf(ToolParam("url", "For instance http://192.168.0.20:25565"), ToolParam("key", "The server's access key")),
        ) { a ->
            val url = a.text("url").trim().trimEnd('/')
            require(url.startsWith("http://") || url.startsWith("https://")) { "The address must start with http:// or https://" }
            ProviderConfig.setKey(deps.db, "mainapi_url", url)
            ProviderConfig.setKey(deps.db, "mainapi", a.text("key"))
            JsonPrimitive(ProviderConfig.key(deps.db, "mainapi") != null)
        },
        AppTool("toggle_my_list", "Add a title to the user's list, or remove it.", ToolRisk.Setting, titleParams()) { a ->
            val (kind, info) = infoOf(a)
            val item = itemOf(kind, a.number("id"), info)
            deps.myList.toggle(item)
            JsonPrimitive(deps.myList.contains(item))
        },
        AppTool(
            "forget_watching", "Remove the resume point a test play left in Continue Watching. Only points under two minutes go.", ToolRisk.Setting,
            titleParams() + listOf(ToolParam("season", "Season of an episode", "integer", required = false), ToolParam("episode", "Episode number", "integer", required = false)),
        ) { a ->
            val kind = a.text("kind")
            val id = a.number("id")
            val season = a.numberOr("season")
            val episode = a.numberOr("episode")
            // A real viewing is further on than a test play: it is never removed here.
            val mine = deps.db.resumeList(FORGET_SCAN).filter {
                it.kind == kind && it.tmdbId == id && (season == null || it.season == season) && (episode == null || it.episode == episode) && it.positionMs < FORGET_UNDER_MS
            }
            mine.forEach { deps.db.deleteResume(it.key) }
            JsonPrimitive(mine.size)
        },
    )

    private inline fun <reified E : Enum<E>> choice(value: String): E =
        enumValues<E>().firstOrNull { it.name == value } ?: throw ToolProblem("Unknown value: $value")

    private fun setPreference(p: DesktopPrefs, name: String, value: String) {
        when (name) {
            "start" -> p.startMode = choice<StartMode>(value)
            "audio" -> p.audioIntent = p.audioIntent.copy(languages = com.sokoul.desktop.data.AudioIntent.decode(value) ?: throw ToolProblem("Give ISO 639-1 codes or original, comma separated"))
            "audioFallback" -> p.audioIntent = p.audioIntent.copy(fallback = choice<com.sokoul.desktop.data.AudioFallback>(value))
            "subtitles" -> p.audioIntent = p.audioIntent.copy(subtitles = value.trim().lowercase().takeIf { it == "off" || it == "auto" || Regex("[a-z]{2}").matches(it) } ?: throw ToolProblem("Give off, auto or an ISO 639-1 code"))
            "maxQuality" -> p.maxQuality = choice<MaxQuality>(value)
            "maxSize" -> p.maxSize = choice<MaxSize>(value)
            "buffer" -> p.buffer = choice<BufferProfile>(value)
            "origin" -> p.preferredOrigin = choice<PreferredOrigin>(value)
            else -> throw ToolProblem("Unknown preference: $name")
        }
    }

    private companion object {
        const val FORGET_SCAN = 500
        const val FORGET_UNDER_MS = 120_000L
        const val MB = 1024 * 1024
        const val MS = 1000L
        const val MAX_LISTED = 20
        const val CHANNEL_LIST_LIMIT = 200
    }
}
