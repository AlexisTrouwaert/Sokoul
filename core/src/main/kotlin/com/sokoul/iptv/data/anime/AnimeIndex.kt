package com.sokoul.iptv.data.anime

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedReader
import java.io.InputStream
import java.io.Writer
import java.text.Normalizer

/**
 * The anime lists kept on the device, reduced to what the app looks up: ids, type, place in the catalog, names. The
 * downloaded files (7 MB for Fribb, 60 MB for manami) are read as a stream and never held whole in memory, which a TV
 * could not afford; only this compact index is kept.
 */
class AnimeIndex private constructor(private val rows: List<Pair<AnimeEntry, List<String>>>) {
    private val byTmdbTv = rows.filter { it.first.tmdbTv != null }.groupBy { it.first.tmdbTv!! }
    private val byTmdbMovie = HashMap<Int, MutableList<AnimeEntry>>().also { m -> rows.forEach { (e, _) -> e.tmdbMovies.forEach { m.getOrPut(it) { mutableListOf() } += e } } }
    private val byImdb = HashMap<String, MutableList<AnimeEntry>>().also { m -> rows.forEach { (e, _) -> e.imdb.forEach { m.getOrPut(it) { mutableListOf() } += e } } }
    private val byAnilist = rows.filter { it.first.anilist != null }.associateBy { it.first.anilist!! }
    private val byName = HashMap<String, MutableList<AnimeEntry>>().also { m -> rows.forEach { (e, names) -> names.forEach { m.getOrPut(it) { mutableListOf() } += e } } }

    val size get() = rows.size

    /** Every entry the catalog's series holds: its seasons, and the films and specials placed in it. */
    fun series(tmdbId: Int): List<AnimeEntry> = byTmdbTv[tmdbId].orEmpty().map { it.first }

    fun movie(tmdbId: Int): List<AnimeEntry> = byTmdbMovie[tmdbId].orEmpty()

    fun imdb(id: String): List<AnimeEntry> = byImdb[id].orEmpty()

    fun anilist(id: Int): AnimeEntry? = byAnilist[id]?.first

    /** Entries whose name is exactly [name], once accents, case and punctuation are set aside. */
    fun named(name: String): List<AnimeEntry> = byName[plain(name)].orEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val SEP = '\t'
        private const val LIST = ','
        private const val NAMES = '\u001f'

        fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace('&', ' ').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** Fribb's list (a JSON array) → index lines. Returns how many entries were written. */
        @OptIn(ExperimentalSerializationApi::class)
        fun writeFribb(input: InputStream, out: Writer): Int {
            var n = 0
            for (o in json.decodeToSequence<JsonObject>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
                val e = AnimeIdsParse.fribb(o)
                if (e.anilist == null && e.mal == null && e.kitsu == null && e.anidb == null) continue
                out.write(line(e, emptyList()))
                n++
            }
            return n
        }

        /** manami's database (`{"data":[…]}`) → index lines, with every name and synonym. */
        @OptIn(ExperimentalSerializationApi::class)
        fun writeManami(input: InputStream, out: Writer): Int {
            var n = 0
            for (o in json.decodeToSequence<JsonObject>(DataArray(input), DecodeSequenceMode.ARRAY_WRAPPED)) {
                val (e, names) = AnimeIdsParse.manami(o) ?: continue
                out.write(line(e, names.map(::plain).filter { it.isNotEmpty() }.distinct()))
                n++
            }
            return n
        }

        fun read(reader: BufferedReader): AnimeIndex = AnimeIndex(reader.lineSequence().mapNotNull(::parse).toList())

        fun of(entries: List<Pair<AnimeEntry, List<String>>>) = AnimeIndex(entries.map { (e, n) -> e to n.map(::plain) })

        private fun line(e: AnimeEntry, names: List<String>): String = listOf(
            e.type.name, e.anilist, e.mal, e.kitsu, e.anidb, e.tvdb, e.imdb.joinToString(LIST.toString()), e.tmdbTv,
            e.tmdbMovies.joinToString(LIST.toString()), e.tmdbSeason, e.tmdbOffset, e.episodes, e.year,
            names.joinToString(NAMES.toString()) { it.replace(SEP, ' ').replace('\n', ' ') },
        ).joinToString(SEP.toString()) { it?.toString().orEmpty() } + "\n"

        private fun parse(l: String): Pair<AnimeEntry, List<String>>? {
            val p = l.split(SEP)
            if (p.size < 14) return null
            fun i(k: Int) = p[k].toIntOrNull()
            fun list(k: Int) = p[k].split(LIST).filter { it.isNotEmpty() }
            val type = runCatching { AnimeType.valueOf(p[0]) }.getOrNull() ?: return null
            return AnimeEntry(type, i(1), i(2), i(3), i(4), i(5), list(6), i(7), list(8).mapNotNull { it.toIntOrNull() }, i(9), i(10) ?: 0, i(11), i(12)) to
                p[13].split(NAMES).filter { it.isNotEmpty() }
        }
    }

    /**
     * The `data` array of a JSON object, as a stream of its own: starts at its `[` and ends after the matching `]`, so
     * that the sequence decoder sees a plain array. Strings are followed, so brackets inside a title do not count.
     */
    private class DataArray(private val input: InputStream) : InputStream() {
        private var depth = 0
        private var started = false
        private var done = false
        private var inString = false
        private var escaped = false
        // Declared before init: an initializer placed after it would reset the bracket read there.
        private var pending = -1

        init {
            val key = "\"data\"".toByteArray()
            var matched = 0
            while (true) {
                val b = input.read()
                if (b < 0) { done = true; break }
                if (matched == key.size) {
                    if (b == '['.code) { started = true; depth = 1; pending = b; break }
                    continue
                }
                matched = if (b.toByte() == key[matched]) matched + 1 else if (b.toByte() == key[0]) 1 else 0
            }
        }

        override fun read(): Int {
            if (pending >= 0) { val b = pending; pending = -1; return b }
            if (done || !started) return -1
            val b = input.read()
            if (b < 0) { done = true; return -1 }
            if (inString) {
                when {
                    escaped -> escaped = false
                    b == '\\'.code -> escaped = true
                    b == '"'.code -> inString = false
                }
            } else when (b) {
                '"'.code -> inString = true
                '['.code, '{'.code -> depth++
                ']'.code, '}'.code -> { depth--; if (depth == 0) done = true }
            }
            return b
        }
    }
}
