package com.sokoul.pc.live

import com.sokoul.desktop.data.Program
import com.sokoul.iptv.data.net.TmdbItem
import kotlinx.coroutines.CancellationException
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture of a programme: the guide's own, else a TMDB picture when the title names one work and one only, else
 * nothing (the channel's logo then stands in). Never the picture of another work: "Journal" or "Météo" stay without.
 */
object ProgramArt {
    /** Titles every channel uses for its own show: no film or series can be told from them. */
    private val GENERIC = setOf(
        "journal", "le journal", "jt", "le jt", "info", "infos", "les infos", "meteo", "la meteo", "teleachat", "tele achat",
        "programmes de la nuit", "fin des programmes", "magazine", "documentaire", "film", "serie", "series", "dessin anime",
        "dessins animes", "sport", "football", "rugby", "tennis", "cinema", "telefilm", "emission", "jeu", "music", "musique", "clips",
    )
    private const val MIN_LENGTH = 4

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace('&', ' ').replace(Regex("[^a-z0-9]+"), " ").trim()

    /** The one work the [title] names among TMDB's [results], with a picture; null when none or several do. */
    fun pick(title: String, results: List<TmdbItem>): TmdbItem? {
        val t = norm(title)
        if (t.length < MIN_LENGTH || t in GENERIC) return null
        val same = results.filter { norm(it.title) == t || it.originalTitle?.let(::norm) == t }.distinctBy { (it.mediaType ?: "") + it.id }
        return same.singleOrNull()?.takeIf { it.backdrop != null }
    }
}

/**
 * Programme pictures, asked of TMDB only for the programme on screen and kept per title: a guide holds thousands of
 * programmes nobody looks at. A failed search is not kept, so it is asked again next time.
 */
class ProgramArtCache(private val search: suspend (String) -> List<TmdbItem>) {
    private val byTitle = ConcurrentHashMap<String, String>()
    private val none = ConcurrentHashMap.newKeySet<String>()

    suspend fun imageOf(program: Program): String? {
        program.icon?.let { return it }
        val key = ProgramArt.norm(program.title)
        byTitle[key]?.let { return it }
        if (key in none) return null
        val results = try {
            search(program.title)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        // 780 px wide is enough for the banner and the panels, and loads in a fraction of the 1280 one's time on a TV.
        val found = ProgramArt.pick(program.title, results)?.backdrop?.replace("/w1280/", "/w780/")
        if (found != null) byTitle[key] = found else none += key
        return found
    }
}
