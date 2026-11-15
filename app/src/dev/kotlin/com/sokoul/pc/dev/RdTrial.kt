package com.sokoul.pc.dev

import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.iptv.data.debrid.RealDebridApi
import com.sokoul.pc.data.providers.ProviderHttp
import com.sokoul.pc.data.providers.SubtitlesClient
import com.sokoul.pc.data.providers.TvdbClient
import com.sokoul.pc.data.providers.WatchmodeClient
import com.sokoul.pc.data.providers.WikipediaClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlin.system.exitProcess

/**
 * Leaves the Real-Debrid account as it was after a live trial. `snapshot <file>` notes the torrents present, and
 * `cleanup <file>` removes only those added since. Prints counts, never a name, an id or the token.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val db = DesktopDb.open()
    val api = RealDebridApi(OkHttpClient(), { ProviderConfig.key(db, "realdebrid") })
    if (args[0] == "status") {
        ProviderConfig.statuses(db).forEach { println("${it.id}=${it.configured}") }
        db.close()
        exitProcess(0)
    }
    if (args[0] == "apis") {
        // Real answers of the services, as counts and public names only.
        val p = ProviderHttp(OkHttpClient(), db::tmdbGet, db::tmdbPut)
        val subs = SubtitlesClient(p, { ProviderConfig.key(db, "opensubtitles") }, { ProviderConfig.key(db, "subdl") }, db::tmdbGet, db::tmdbPut)
        val inception = 27205
        val found = subs.search("movie", inception, null, null, listOf("fr", "en"))
        println("subtitles: ${found.groupingBy { it.service to it.language }.eachCount()}")
        found.firstOrNull { it.service == SubtitlesClient.SERVICE_OS }?.let { println("opensubtitles file: ${subs.cues(it).size} lines") }
        found.firstOrNull { it.service == SubtitlesClient.SERVICE_SUBDL }?.let { println("subdl file: ${subs.cues(it).size} lines") }
        val series = subs.search("tv", 1396, 1, 1, listOf("fr"))
        println("subtitles of an episode: ${series.groupingBy { it.service }.eachCount()}")
        val eps = TvdbClient(p) { ProviderConfig.key(db, "tvdb") }.episodes(81189, "fra")
        println("tvdb: ${eps.size} episodes, with text ${eps.count { it.overview != null }}, with image ${eps.count { it.image != null }}")
        val sd = subs.subdl("movie", inception, null, null, listOf("fr", "en"))
        println("subdl: ${sd.size} results")
        sd.firstOrNull()?.let { r ->
            println("subdl file: ${subs.cues(r).size} lines, path shape ${r.ref.replace(Regex("[0-9]+"), "#").take(40)}")
            val answer = OkHttpClient().newCall(okhttp3.Request.Builder().url("https://dl.subdl.com${r.ref}").header("User-Agent", "SokoulPC/1.0").build()).execute()
            answer.use { a ->
                val bytes = a.body?.bytes() ?: ByteArray(0)
                println("subdl raw: ${a.code} ${a.header("Content-Type")} ${bytes.size} bytes, starts ${bytes.take(2).map { it.toInt().toChar() }}")
                if (bytes.size > 2 && bytes[0] == 'P'.code.toByte()) {
                    java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> println("subdl entries: " + generateSequence { z.nextEntry }.map { it.name.substringAfterLast('.') }.toList()) }
                }
            }
        }
        val wm = WatchmodeClient(p) { ProviderConfig.key(db, "watchmode") }
        listOf("FR", "US").forEach { r -> println("watchmode $r: " + wm.offers("movie", inception, r).joinToString { it.service }) }
        val wiki = WikipediaClient(p) { true }
        listOf("Star Wars", "Toy Story", "Rocky", "Le Seigneur des anneaux", "Fast & Furious", "Saw").forEach { println("wikipedia $it: " + wiki.page(it)?.let { (t, s) -> "$t, ${s.length} characters" }) }
        db.close()
        exitProcess(0)
    }
    if (args[0] == "forget") {
        // The trial's own trace in "Continue Watching".
        val name = args.drop(1).joinToString(" ")
        val mine = db.resumeList(RESUME_LIMIT).filter { it.title == name }
        mine.forEach { db.deleteResume(it.key) }
        println("forgot ${mine.size}")
        db.close()
        exitProcess(0)
    }
    val file = File(args[1])
    val current = api.torrents(LIMIT)
    when (args[0]) {
        "snapshot" -> {
            file.writeText(current.joinToString("\n") { it.id })
            println("noted ${current.size}")
        }
        "cleanup" -> {
            val known = file.readLines().toSet()
            val added = current.filter { it.id !in known }
            added.forEach { api.deleteTorrent(it.id) }
            println("removed ${added.size}, left ${api.torrents(LIMIT).size}")
        }
    }
    db.close()
    exitProcess(0)
}

private const val LIMIT = 100
private const val RESUME_LIMIT = 200
