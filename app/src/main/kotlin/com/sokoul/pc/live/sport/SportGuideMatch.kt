package com.sokoul.pc.live.sport

import com.sokoul.desktop.data.Channel
import com.sokoul.desktop.data.Program
import com.sokoul.pc.live.TvCatalog

/**
 * Which of the owner's channels shows a match, read from the provider's own guide (after Teamarr's EPG matching,
 * https://pharaoh-labs.github.io/teamarr/guide/epg-matching.html): a programme during the match whose title names
 * both clubs. A generic title ("Football", "Ligue 1") names no club and is not taken as proof.
 */
object SportGuideMatch {
    /** Words that do not tell a club from another. */
    private val COMMON = setOf(
        "fc", "afc", "cf", "sc", "ac", "as", "us", "rc", "og", "ogc", "the", "club", "sporting", "united", "city", "real",
        "stade", "olympique", "de", "la", "le", "les", "du", "des", "of", "and", "st", "saint", "racing",
    )

    /** The words that name a club, accents and case set aside ("Paris Saint-Germain" → paris, germain, psg). */
    fun clubWords(t: SportTeam): Set<String> =
        (sportKey(t.name).split(' ') + sportKey(t.abbr)).filter { it.length >= 3 && it !in COMMON }.toSet()

    /** Both clubs named in a programme title: "Marseille / Le Mans", "Football : OM - Le Mans", "Match : Lens-Lille". */
    fun names(title: String, m: SportMatch): Boolean {
        val words = sportKey(title).split(' ').toSet()
        return clubWords(m.home).any { it in words } && clubWords(m.away).any { it in words }
    }

    /** The window a channel may show the match in: the build-up before kick-off to the end of a long game. */
    fun window(m: SportMatch): LongRange = (m.start - BEFORE_MS)..(m.start + AFTER_MS)

    /**
     * The channels of the owner's guide that show [m], the one covering most of the window first. [search] is the TV
     * catalogue's programme search over a time window (title fragment, from, until).
     */
    fun find(m: SportMatch, search: (String, Long, Long) -> List<TvCatalog.Hit>): List<Pair<Channel, Program>> {
        val w = window(m)
        // One query per club's most telling word: a guide may write one club's name differently ("OM", "Olympique de Marseille").
        val probes = (clubWords(m.home).sortedByDescending { it.length }.take(1) + clubWords(m.away).sortedByDescending { it.length }.take(1))
        val hits = probes.flatMap { word -> runCatching { search(word, w.first, w.last) }.getOrDefault(emptyList()) }
        return hits.mapNotNull { h -> h.program?.let { p -> h.channel to p } }
            .filter { (_, p) -> names(p.title, m) && p.end > w.first && p.start < w.last }
            .distinctBy { (c, p) -> c.id to p.start }
            .sortedByDescending { (_, p) -> minOf(p.end, w.last) - maxOf(p.start, w.first) }
            .take(MAX)
    }

    /** The sports being played now on the owner's channels, from their guide: a programme titled as a game. */
    fun liveGames(search: (String, Long, Long) -> List<TvCatalog.Hit>, now: Long = System.currentTimeMillis()): List<Pair<Channel, Program>> {
        // "A / B", "A - B", "A vs B": two names around a separator, as guides title a game.
        val game = Regex("""\S.{1,40}\s(/|-|vs\.?|–)\s.{2,40}\S""", RegexOption.IGNORE_CASE)
        return SPORT_WORDS.flatMap { word -> runCatching { search(word, now, now + 1) }.getOrDefault(emptyList()) }
            .mapNotNull { h -> h.program?.let { h.channel to it } }
            .filter { (_, p) -> p.start <= now && p.end > now && (game.containsMatchIn(p.title) || SPORT_WORDS.any { sportKey(p.title).contains(it) }) }
            .distinctBy { (c, _) -> c.nameKey }
            .take(MAX_LIVE)
    }

    private val SPORT_WORDS = listOf("football", "ligue", "rugby", "tennis", "basket", "nba", "formule", "moto", "boxe", "ufc", "mma", "cyclisme", "handball", "golf")
    private const val BEFORE_MS = 30 * 60_000L
    private const val AFTER_MS = 3 * 3_600_000L
    private const val MAX = 8
    private const val MAX_LIVE = 20
}
