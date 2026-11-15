package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.data.anime.Homonym

/** What a version is, judged against the work and the episode asked for. */
sealed interface Verdict {
    /** A readable number in the file says it is this episode. */
    data object Confirmed : Verdict
    /** Nothing readable either way: kept, after the confirmed ones. [doubt] says why it is lower still. */
    data class Unknown(val doubt: String? = null) : Verdict
    /** Its identity contradicts the request: never played on its own. */
    data class Rejected(val reason: Reason) : Verdict

    enum class Reason { OtherWork, OtherEpisode, NotAnEpisode }
}

/** The work and the episode a version is judged against. */
data class AnimeCheck(
    val titles: List<String>,
    val workYear: Int?,
    val season: Int?,
    val episode: Int?,
    val absolute: Int?,
    val homonyms: List<Homonym> = emptyList(),
)

/**
 * The rules that keep another work or another episode from playing for an anime. Built on the general rules of
 * [DebridStreams], with what the identity adds: the years and the platforms of the works with the same name.
 */
object AnimeReleaseRules {
    private val tagStart = Regex("""^\s*(\[[^\]]*\]|\([^)]*\)|\{[^}]*\})\s*""")
    private val yearToken = Regex("(?<!\\d)(19[3-9]\\d|20[0-4]\\d)(?!\\d)")
    private val platformTags = mapOf(
        "netflix" to "NF", "amazon" to "AMZN", "prime video" to "AMZN", "disney+" to "DSNP", "disney plus" to "DSNP",
        "apple tv+" to "ATVP", "apple tv" to "ATVP", "hbo" to "HMAX", "max" to "HMAX", "hulu" to "HULU", "paramount+" to "PMTP",
    )
    // Brackets escaped for Android's regex engine.
    private val animeMarks = Regex(
        "(?i)(^\\s*\\[[^\\]]+\\])|(\\s-\\s\\d{2,4}(v\\d)?(\\s|\\[|\\(|$))|\\b(vf|vff|truefrench|french|multi|vostfr|jpn|japanese|jap|dual[ ._-]?audio|multi[ ._-]?subs?|bd(rip)?|nyaa|subsplease|erai|horriblesubs)\\b",
    )
    private val liveActionMarks = Regex("(?i)(?<![a-z0-9])(2160p|4k|uhd|dv|dovi|hdr10(plus)?|hdr|sdr|atmos|ddp5[ ._]?1|ddp|eac3|dd5[ ._]?1)(?![a-z0-9])")
    private val notEpisode = Regex("(?i)\\b(movie|film|gekijouban|the movie)\\b")

    fun verdict(name: String, file: String?, check: AnimeCheck): Verdict {
        val line = name.lineSequence().firstOrNull().orEmpty()
        if (namesNone(line, file, check.titles)) return Verdict.Rejected(Verdict.Reason.OtherWork)
        if (homonymYear(line, check)) return Verdict.Rejected(Verdict.Reason.OtherWork)
        if (DebridStreams.otherYear(line, check.titles, check.workYear, check.season)) return Verdict.Rejected(Verdict.Reason.OtherWork)
        val target = file ?: line
        val match = DebridStreams.episodeMatches(target, check.season, check.episode, check.absolute)
        if (match == false) return Verdict.Rejected(Verdict.Reason.OtherEpisode)
        if (match != true && check.season != null && notEpisode.containsMatchIn(target.substringAfterLast('/'))) return Verdict.Rejected(Verdict.Reason.NotAnEpisode)
        // "One.Piece.S01E01.1080p.NF.WEB-DL" is the right number of the live-action series: the platform outweighs it.
        val doubt = platformDoubt(line, check)
        if (doubt != null) return Verdict.Unknown(doubt)
        return if (match == true) Verdict.Confirmed else Verdict.Unknown()
    }

    /**
     * A year next to the title that is the year of a work with the same name and not of the anime: "One Piece 2023"
     * for a request of the 1999 anime, whatever the season.
     */
    private fun homonymYear(line: String, check: AnimeCheck): Boolean {
        val work = check.workYear ?: return false
        val others = check.homonyms.filter { !it.animated }.mapNotNull { it.year }.filter { kotlin.math.abs(it - work) > 1 }
        if (others.isEmpty()) return false
        var raw = line
        while (true) { val m = tagStart.find(raw) ?: break; raw = raw.substring(m.range.last + 1) }
        val title = check.titles.map(::plain).filter { it.isNotEmpty() }.sortedByDescending { it.length }.firstOrNull { plain(raw).startsWith(it) } ?: return false
        // Only the words right after the title: further on, a year can be when a late season came out.
        val head = plain(raw).removePrefix(title).trim().split(' ').take(HEAD_WORDS).joinToString(" ")
        return yearToken.findAll(head).any { m -> m.value.toInt() in others }
    }

    /**
     * None of the work's names is in the release or its file: "Blue Box S01E01" for One Piece. Judged only with names
     * written in Latin letters, the ones release names use; without any, nothing is decided here.
     */
    private fun namesNone(line: String, file: String?, titles: List<String>): Boolean {
        val known = titles.map(::plain).filter { it.length >= MIN_NAME }.distinct()
        if (known.isEmpty()) return false
        val text = " " + plain(line) + " " + plain(file.orEmpty()) + " "
        return known.none { text.contains(" $it ") }
    }

    /** The tag of the platform a homonym was made for, in a name with no sign of an anime release. */
    private fun platformDoubt(line: String, check: AnimeCheck): String? {
        val live = check.homonyms.filter { !it.animated }
        if (live.isEmpty() || animeMarks.containsMatchIn(line)) return null
        val tags = live.flatMap { h -> h.networks.mapNotNull { platformTags[it.lowercase()] } }.toSet()
        val words = line.split(rxWordSplit).toSet()
        tags.firstOrNull { it in words }?.let { return "platform:$it" }
        // "ONE.PIECE.S02E01.2160p.SDR" (9.5 GB): what a live-action series is released as, not a 1999 anime.
        if (liveActionMarks.containsMatchIn(line)) return "live-action"
        // "One.Piece.S02E01.MULTi.1080p.WEB.H265" (1.8 GB) for the anime's episode 62: anime releases past the first
        // season carry the number since the first episode, while the live-action series is released by its own seasons.
        return if (seasonNumbered(line, check)) "live-action" else null
    }

    private val rxSeasonEpisode = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?![0-9])")

    /** Named by the catalog's season and episode, where the anime's own number is another one. */
    private fun seasonNumbered(line: String, check: AnimeCheck): Boolean {
        val absolute = check.absolute ?: return false
        if (check.season == null || check.episode == null || absolute == check.episode) return false
        return rxSeasonEpisode.findAll(line).any { m -> m.groupValues[1].toInt() == check.season && m.groupValues[2].toInt() == check.episode }
    }

    private val rxWordSplit = Regex("[^A-Za-z0-9+]+")
    private val rxMarks = Regex("\\p{M}+")
    private val rxNonAlnum = Regex("[^a-z0-9]+")
    private fun plain(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(rxMarks, "").lowercase()
        .replace('&', ' ').replace(rxNonAlnum, " ").trim()

    private const val HEAD_WORDS = 3
    private const val MIN_NAME = 3
}
