package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.domain.PlaybackSource
import com.sokoul.iptv.domain.SourceOrigin
import com.sokoul.iptv.domain.SourceState
import com.sokoul.iptv.parser.long
import com.sokoul.iptv.parser.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Une version proposée par l'extension de sources, lisible via Real-Debrid. */
data class DebridStream(
    /** Clé stable : « hash:fichier », ou empreinte du lien de l'extension. */
    val key: String,
    /** Nom brut de la version (affiché en détail, jamais analysé ailleurs). */
    val title: String,
    /** Lien déjà résolu par l'extension (extension configurée avec Real-Debrid). */
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val filename: String?,
    val release: Release,
    /** Déjà dans le compte Real-Debrid de l'utilisateur, prêt (« Mes fichiers ») : lecture immédiate. */
    val inAccount: Boolean = false,
    /** En cours de téléchargement dans le compte (pourcentage), null sinon. */
    val preparing: Int? = null,
    /** Anime: what the identity rules say of it. Null for other titles, whose wrong versions are simply dropped. */
    val verdict: Verdict? = null,
    /** Kept by the SeaDex community as the best release (or an alternative) of this anime. */
    val community: Boolean = false,
    /** Catalog identity retained until the actual file in a collection is selected. */
    val movieTitles: List<String> = emptyList(),
    val movieYear: Int? = null,
) {
    val magnet get() = infoHash?.let { "magnet:?xt=urn:btih:$it" }

    /** Sans doute prête : dans le compte, ou annoncée en cache par l'extension (indice pour le classement seulement). */
    val readyNow: Boolean? get() = if (inAccount) true else if (preparing != null) false else release.cached

    /**
     * Affichée « instantanée » seulement quand c'est sûr (dans le compte) : depuis que Real-Debrid ne dit plus ce qu'il
     * a en cache, le repère « ⚡ / [RD+] » des extensions peut se tromper.
     */
    fun toSource(): PlaybackSource = PlaybackSource(
        id = key, origin = SourceOrigin.DEBRID, quality = release.quality, hdr = release.hdr, language = release.language,
        state = if (inAccount) SourceState.CACHED_INSTANT else SourceState.AVAILABLE,
        sizeBytes = release.sizeBytes, rip = release.source?.label,
    )
}

/** Œuvre à chercher : film, ou épisode d'une série (identifiants TMDB). */
data class DebridQuery(
    val tmdbId: Int,
    val series: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    /** Anime: the number since the first episode, which anime files carry ("One Piece - 062" is S02E01). */
    val absolute: Int? = null,
    /** Anime: the Kitsu entry and its episode, which search services answer with the right file of a pack. */
    val kitsu: com.sokoul.iptv.data.anime.EntryEpisode? = null,
) {
    /** Forme sérialisable pour une route : « m:157336 », « s:1396:2:5 ». The anime numbers are found again on arrival. */
    fun encode() = if (series) "s:$tmdbId:$season:$episode" else "m:$tmdbId"

    /** Chemin Stremio de l'œuvre (`stream/movie/tt….json`, `stream/series/tt…:S:E.json`). */
    fun streamPath(imdb: String) = if (series) "stream/series/$imdb:$season:$episode.json" else "stream/movie/$imdb.json"

    /** The same episode by its Kitsu id (`stream/series/kitsu:12:62.json`), null when not known. */
    fun kitsuPath(): String? = kitsu?.let { "stream/series/kitsu:${it.id}:${it.episode}.json" }?.takeIf { series }

    companion object {
        fun decode(s: String?): DebridQuery? {
            val p = s?.split(':') ?: return null
            return when (p.firstOrNull()) {
                "m" -> p.getOrNull(1)?.toIntOrNull()?.let { DebridQuery(it, false) }
                "s" -> {
                    val id = p.getOrNull(1)?.toIntOrNull() ?: return null
                    DebridQuery(id, true, p.getOrNull(2)?.toIntOrNull(), p.getOrNull(3)?.toIntOrNull())
                }
                else -> null
            }
        }
    }
}

/** Lecture et tri des réponses des extensions de sources (format Stremio), communs à l'Android et au PC. */
object DebridStreams {
    /** A release year contradicts the requested movie even when its title is a different sequel or alias. */
    fun wrongMovie(name: String, titles: List<String>, year: Int?): Boolean {
        val file = name.substringAfterLast('/').substringAfterLast('\\')
        val head = norm(file.lineSequence().firstOrNull().orEmpty())
        // Numeric titles (1917, 1984, 2001) are not release years.
        val title = titles.map(::norm).filter { it.isNotBlank() }.sortedByDescending { it.length }
            .firstOrNull { head == it || head.startsWith("$it ") }
        val announcedYear = rxYear.find(if (title == null) head else head.removePrefix(title))?.value?.toIntOrNull()
        return (year != null && announcedYear != null && kotlin.math.abs(year - announcedYear) > 1) ||
            otherWork(file, titles)
    }

    /**
     * An audiobook, an e-book or a soundtrack: never a video. Seen for a film still in cinemas (2026-11-18), where
     * "The Hunger Games Trilogy + Prequel (Audiobook)" was the first version and was tried before anything else.
     */
    fun notVideo(name: String): Boolean = rxNotVideo.containsMatchIn(name)

    /**
     * A box set of a franchise for a film of this year or the last: it was put together before the film came out, so
     * it does not hold it ("The Hunger Games Quadrilogy" for the 2026 prequel). An older film's box set is kept: its
     * file is chosen by name in the pack.
     */
    fun packBeforeFilm(name: String, titles: List<String>, year: Int?, thisYear: Int = java.time.Year.now().value): Boolean {
        if (year == null || year < thisYear - 1) return false
        val word = rxBoxSet.find(name)?.value?.lowercase() ?: return false
        // "The Twilight Saga…": the word belongs to the film's own title.
        return titles.none { it.lowercase().contains(word) }
    }

    /** Exact title head, allowing release tags after the title but never another sequel's subtitle. */
    fun movieTitleMatches(name: String, titles: List<String>): Boolean {
        val n = norm(name.substringAfterLast('/').substringAfterLast('\\').replace(rxVideoExt, ""))
        return titles.map(::norm).filter { it.isNotBlank() }.any { title ->
            n == title || (n.startsWith("$title ") && titleEnd.find(n.removePrefix(title).trim())?.range?.first == 0)
        }
    }
    /**
     * Version sans aucune langue dans son nom : c'est la langue d'origine du film (les releases étrangères ne
     * l'indiquent pas). VO, ou VF si l'œuvre est française ; rangée dans sa section au lieu de « Autres ».
     */
    private val tagStart = Regex("""^\s*(\[[^\]]*\]|\([^)]*\)|\{[^}]*\})\s*""")
    private val titleEnd = Regex(
        // "vf" and episode words end a title too: "One Piece 1100 VF 1080p" was dropped as another work while its VOSTFR twin stayed.
        "\\b(s\\d{1,2}(e\\d{1,3})?|e\\d{1,4}|ep\\d{1,4}|episode|ep|season|saison|integrale|complete|(19|20)\\d{2}|\\d{3,4}p|4k|uhd|multi|truefrench|french|fr|fre|fra|vf|vf2|vfi|vfb|vff|vfq|vostfr|bluray|web|webrip|hdtv)\\b",
    )
    /** Mots qu'on peut trouver après le titre sans changer d'œuvre (« The Office US »). */
    private val sameWork = setOf("us", "uk", "au", "nz", "ca", "fr", "the", "series", "serie", "tv")

    // Compiled once: these run for each of some 300 versions, again at each answer of a service, on a TV.
    private val rxMarks = Regex("\\p{M}+")
    private val rxNonAlnum = Regex("[^a-z0-9]+")
    private val rxVideoExt = Regex("(?i)\\.(mkv|mp4|avi|ts|m2ts|m4v|mov|webm)$")
    private val rxChecksum = Regex("\\[[0-9A-Fa-f]{8}\\]")
    private val rxYear = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)")
    private val rxNotVideo = Regex("(?i)(?<![a-z])(audio ?book|livre audio|h[öo]rbuch|ebook|e-book|epub|m4b|soundtrack|ost|flac|mp3)(?![a-z])")
    private val rxBoxSet = Regex("(?i)(?<![a-z])(duology|trilogy|trilogie|quadrilogy|quadrilogie|pentalogy|hexalogy|anthology|collection|saga|integrale|intégrale|box ?set|coffret)(?![a-z])")
    private val rxInBrackets = Regex("\\((\\d{2,4})\\)")
    private val rxHash = Regex("[0-9a-f]{40}")
    private val rxHashInUrl = Regex("[/=]([0-9a-fA-F]{40})(?=[/?]|$)")
    private val rxHashAlone = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{40})(?![0-9a-fA-F])")
    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(rxMarks, "").lowercase().replace('&', ' ').replace(rxNonAlnum, " ").trim()

    /**
     * Le nom de la release est celui d'une autre œuvre qui commence par le même titre (« Lupin Zero » pour
     * « Lupin ») : seule la partie avant la saison, l'année ou la qualité compte. Un nom sans rapport (titre
     * anglais d'une série étrangère) n'est jamais écarté : on ne rejette que ce qui est sûr.
     */
    fun otherWork(name: String, titles: List<String>, series: Boolean = false): Boolean {
        // série : « Lupin Part 2 » est une saison ; film : « Dune Part Two » est un autre film
        val allowed = if (series) sameWork + setOf("part", "partie", "vol", "volume", "1", "2", "3", "4", "5", "6", "7", "8", "9") else sameWork
        var raw = name
        while (true) { val m = tagStart.find(raw) ?: break; raw = raw.substring(m.range.last + 1) }
        val n = norm(raw)
        val head = titleEnd.find(n)?.let { n.substring(0, it.range.first) }?.trim() ?: return false
        if (head.isEmpty()) return false
        val known = titles.map(::norm).filter { it.isNotEmpty() }
        if (known.any { it == head || it.startsWith("$head ") }) return false
        return known.any { t ->
            // In a series, a number after the title is the episode of an anime file ("One Piece - 001"), not another work.
            head.startsWith("$t ") && head.removePrefix("$t ").split(' ').any { it.isNotEmpty() && it !in allowed && !(series && it.all(Char::isDigit)) }
        }
    }

    private val yearToken = Regex("^(19[3-9]\\d|20[0-4]\\d)$")
    private val qualityToken = Regex("^(\\d{3,4}p|4k|uhd|hdr|web|webrip|bluray|bdrip|hdtv|nf|amzn|dsnp|multi|truefrench|french|vostfr)$")
    private val seasonToken = Regex("^s\\d{1,2}(e\\d{1,4})?$")

    /**
     * Another work with the same name, told by its year: "One Piece 2023 S01E01" is the 2023 series, not the anime of
     * 1999. A year right after the title always names the work ("Doctor Who 2005"); for a first season, so does any
     * year between the title and the quality, since a season 1 does not come out years after the series. [workYear] is
     * the work's year (film, or first airing). Without a readable year, nothing is dropped.
     */
    fun otherYear(name: String, titles: List<String>, workYear: Int?, season: Int? = null): Boolean {
        if (workYear == null) return false
        var raw = name.lineSequence().firstOrNull().orEmpty()
        while (true) { val m = tagStart.find(raw) ?: break; raw = raw.substring(m.range.last + 1) }
        val n = norm(raw)
        val t = titles.map(::norm).filter { it.isNotEmpty() }.sortedByDescending { it.length }.firstOrNull { n == it || n.startsWith("$it ") } ?: return false
        val rest = n.removePrefix(t).trim().split(' ').filter { it.isNotEmpty() }
        val zone = rest.takeWhile { !qualityToken.matches(it) }
        val far = { y: String -> kotlin.math.abs(y.toInt() - workYear) > 1 }
        // "One Piece (1999-2000)": the first year of the range counts
        if (zone.firstOrNull()?.let { yearToken.matches(it) && far(it) } == true) return true
        if (season != 1) return false
        val seasonAt = zone.indexOfFirst { seasonToken.matches(it) }
        return seasonAt >= 0 && zone.any { yearToken.matches(it) && far(it) }
    }

    private val sxe = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,4})(?!\\d)")
    private val frenchEpisode = Regex("""(?i)\bsaison\s*(\d{1,2})\s*(?:[- ._:]*(?:episode|\u00e9pisode|ep|e)\s*|[-:]\s*)(\d{1,4})(?!\d)""")
    private val nxn = Regex("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,4})(?![0-9a-z])")
    private val bare = Regex("(?i)(?<![a-z0-9])(?:ep?|episode|épisode)?[ ._-]?(\\d{1,4})(?:v\\d)?(?![0-9a-z])")
    private val notEpisode = setOf(480, 576, 720, 1080, 1440, 2160, 264, 265)

    /**
     * The file picked in a pack is not the episode asked for: "One Piece English Dub 319.mp4" for episode 1.
     * [absolute] is the number from the start of the series (anime: "One Piece - 062" is S02E01). Sure or nothing:
     * a name without a readable number is kept, and without [absolute] past season 1 only "SxxEyy" is judged.
     */
    fun wrongEpisode(file: String?, season: Int?, episode: Int?, absolute: Int? = null): Boolean =
        episodeMatches(file, season, episode, absolute) == false

    /**
     * Whether a file's name is the episode asked for: true when a number in it says so, false when its numbers say
     * another episode, null when it cannot be read. "[A&C] One Piece S10E01 (0337)" is episode 337 too.
     */
    fun episodeMatches(file: String?, season: Int?, episode: Int?, absolute: Int? = null): Boolean? {
        if (file.isNullOrBlank() || season == null || episode == null) return null
        val name = file.substringAfterLast('/').substringAfterLast('\\').replace(rxVideoExt, "")
        // The checksum "[FB72C13F]" and years are not episode numbers.
        // "\]" is escaped: Android's regex engine (ICU) may refuse a lone closing bracket.
        val clean = name.replace(rxChecksum, " ").replace(rxYear, " ")
        val pairs = (sxe.findAll(clean) + nxn.findAll(clean) + frenchEpisode.findAll(clean)).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        // "S01E62" counts from the first episode; "S10E01" is the first of season 10, not episode 1 of the series.
        if (pairs.any { (s, e) -> (s == season && e == episode) || (absolute != null && s == 1 && e == absolute) }) return true
        val wanted = setOfNotNull(absolute, episode.takeIf { season == 1 })
        // The number in brackets after the season tag ("S10E01 (0337)") is the absolute one.
        if (pairs.isNotEmpty() && absolute != null && rxInBrackets.findAll(clean).any { it.groupValues[1].toInt() == absolute }) return true
        if (pairs.isNotEmpty()) return false
        if (wanted.isEmpty()) return null
        val numbers = bare.findAll(clean).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it !in notEpisode }.toList()
        if (numbers.isEmpty()) return null
        return numbers.any { it in wanted }
    }

    /** A version's file: its name, or the line of the file picked in a pack (Torrentio: second line). */
    fun fileOf(s: DebridStream): String? = s.filename ?: s.title.lineSequence().drop(1).firstOrNull()?.takeIf { line ->
        line.isNotBlank() && !line.trimStart().startsWith("👤") && !line.trimStart().startsWith("💾")
    }

    fun untagged(streams: List<DebridStream>, originalLanguage: String?): List<DebridStream> {
        if (originalLanguage.isNullOrBlank()) return streams
        val lang = if (originalLanguage.equals("fr", ignoreCase = true)) com.sokoul.iptv.domain.AudioLanguage.VF else com.sokoul.iptv.domain.AudioLanguage.VO
        return streams.map { s ->
            if (s.release.language == null && s.release.audio.isEmpty()) s.copy(release = s.release.copy(language = lang)) else s
        }
    }

    /**
     * Résultats de plusieurs extensions → une liste sans doublon : même clé, même empreinte de torrent (et fichier),
     * never by filename and approximate size alone. The first response order is preserved.
     */
    fun combine(results: List<List<DebridStream>>): List<DebridStream> {
        val seen = HashMap<String, Int>()
        val out = mutableListOf<DebridStream>()
        for (list in results) for (s in list) {
            val ids = listOfNotNull(
                "k:" + s.key,
                s.infoHash?.let { "h:$it:" + (s.fileIdx ?: -1) },
            )
            val at = ids.firstNotNullOfOrNull { seen[it] }
            if (at != null) {
                // The same version told again, later: what it adds is kept (the link, the cache hint of the slow answer).
                out[at] = enrich(out[at], s)
                ids.forEach { seen.putIfAbsent(it, at) }
                continue
            }
            ids.forEach { seen[it] = out.size }
            out += s
        }
        return out
    }

    /** [first], with what [later] knows and it does not. */
    fun enrich(first: DebridStream, later: DebridStream): DebridStream {
        val a = first.release
        val b = later.release
        val fileQuality = ReleaseName.explicitQuality(first.filename) ?: ReleaseName.explicitQuality(later.filename)
        // French first, as ReleaseName reads it, then the others in a fixed order: the arrival order changes nothing.
        val audio = (a.audio + b.audio).distinct().sortedWith(compareBy<String> { if (it == "FR") 0 else 1 }.thenBy { it })
        val language = when {
            "FR" in audio && audio.any { it != "FR" } -> com.sokoul.iptv.domain.AudioLanguage.MULTI
            a.language == null -> b.language
            b.language == null -> a.language
            a.language == b.language -> a.language
            setOf(a.language, b.language).containsAll(setOf(com.sokoul.iptv.domain.AudioLanguage.VF, com.sokoul.iptv.domain.AudioLanguage.VO)) ->
                com.sokoul.iptv.domain.AudioLanguage.MULTI
            // Two other readings of the same file: the same answer whichever service spoke first.
            else -> listOfNotNull(a.language, b.language).minOrNull()
        }
        // A foreign dub only while no French is announced for this file, whichever answer said "Dual" or "FR".
        val dub = (a.dub || b.dub) && "FR" !in audio
        return first.copy(
            url = first.url ?: later.url,
            filename = first.filename ?: later.filename,
            fileIdx = first.fileIdx ?: later.fileIdx,
            release = a.copy(
                quality = fileQuality ?: a.quality ?: b.quality,
                hdr = a.hdr ?: b.hdr,
                language = language,
                frenchVariant = a.frenchVariant ?: b.frenchVariant,
                source = a.source ?: b.source,
                codec = a.codec ?: b.codec,
                sizeBytes = a.sizeBytes ?: b.sizeBytes,
                seeders = a.seeders ?: b.seeders,
                cached = a.cached ?: b.cached,
                season = a.season ?: b.season,
                episode = a.episode ?: b.episode,
                audio = audio,
                dub = dub,
            ),
            community = first.community || later.community,
        )
    }

    /**
     * Versions de l'extension + fichiers du compte : une version de l'extension déjà dans le compte (même empreinte)
     * est marquée « dans votre compte » (ou « en préparation ») ; les autres fichiers du compte s'ajoutent à la fin.
     */
    fun merge(addon: List<DebridStream>, account: List<DebridStream>): List<DebridStream> {
        val byHash = account.filter { it.infoHash != null }.associateBy { it.infoHash!!.lowercase() }
        val used = HashSet<String>()
        val out = addon.map { a ->
            val c = a.infoHash?.lowercase()?.let(byHash::get) ?: return@map a
            used += c.key
            a.copy(inAccount = c.inAccount, preparing = c.preparing)
        }
        return out + account.filter { it.key !in used }
    }

    /** Réponse Stremio `{"streams":[…]}` → versions lisibles (films filmés en salle écartés). */
    fun parse(text: String, json: Json = Json): List<DebridStream> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        return (root["streams"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val hints = o["behaviorHints"] as? JsonObject
            val url = o.str("url")?.takeIf { it.startsWith("http") }
            val hash = o.str("infoHash")?.lowercase()?.takeIf { it.matches(rxHash) }
                // extension réglée avec Real-Debrid : l'empreinte est dans le lien (« /resolve/realdebrid/…/<hash>/… »)
                ?: url?.let { u -> rxHashInUrl.find(u)?.groupValues?.get(1)?.lowercase() }
                // Comet : lien opaque (base64), l'empreinte est dans « bingeGroup » (« comet|<hash> »)
                ?: hints?.str("bingeGroup")?.let { b -> rxHashAlone.find(b)?.groupValues?.get(1)?.lowercase() }
            if (url == null && hash == null) return@mapNotNull null
            val name = o.str("name").orEmpty()
            val title = o.str("title") ?: o.str("description").orEmpty()
            val filename = hints?.str("filename")
            val release = ReleaseName.parse(name, title, filename).let { r ->
                if (r.sizeBytes == null) r.copy(sizeBytes = hints?.long("videoSize")) else r
            }
            if (release.cam) return@mapNotNull null
            val fileIdx = o.long("fileIdx")?.toInt()
            DebridStream(
                key = hash?.let { "$it:${fileIdx ?: -1}" } ?: ("u" + url.hashCode().toUInt().toString(16)),
                title = (filename ?: title.lineSequence().firstOrNull() ?: name).trim(),
                url = url, infoHash = hash, fileIdx = fileIdx, filename = filename, release = release,
            )
        }.distinctBy { it.key }
    }
}
