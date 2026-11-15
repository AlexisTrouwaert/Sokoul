package com.sokoul.pc.dev

import com.sokoul.desktop.data.Catalog
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.pc.data.YtDlp
import com.sokoul.pc.data.trailerKeys
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** Compares what YouTube offers for each trailer with what the app picks. Prints public video ids and formats only. */
private fun formats(exe: String, key: String, selector: String): String = runCatching {
    val p = ProcessBuilder(
        exe, "--no-warnings", "--no-playlist", "--js-runtimes", "node", "-f", selector, "-S", YtDlp.SORT,
        "--print", "%(height)sp %(fps)sfps %(vcodec).4s %(tbr).0fk", "--", key,
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!p.waitFor(90, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        "timeout"
    } else {
        p.inputStream.bufferedReader().readText().trim().ifEmpty { "none" }
    }
}.getOrDefault("error")

fun main(): Unit = runBlocking {
    val exe = YtDlp.locate() ?: error("yt-dlp not found")
    val db = DesktopDb.open()
    val catalog = Catalog(db)
    val key = catalog.key()
    val items = catalog.tmdb.trendingDay(key, "all").take(10)
    for (item in items) {
        val info = catalog.tmdb.details(key, item.mediaType ?: "movie", item.id, full = true) ?: continue
        println("== ${item.title} | videos=${info.videos.size}")
        info.trailerKeys().take(3).forEach { println("   $it -> " + formats(exe, it, YtDlp.FORMAT).replace('\n', ' ')) }
    }
    db.close()
    kotlin.system.exitProcess(0)
}
