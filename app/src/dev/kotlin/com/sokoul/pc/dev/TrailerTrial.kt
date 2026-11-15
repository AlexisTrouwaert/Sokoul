package com.sokoul.pc.dev

import com.sokoul.desktop.data.Catalog
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.player.DesktopPlayer
import com.sokoul.desktop.player.StreamRequest
import com.sokoul.pc.data.TrailerResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Real-account trial. Prints statuses only: never a key, a token or a full link. Audio stays muted. */
fun main(): Unit = runBlocking {
    val db = DesktopDb.open()
    val catalog = Catalog(db)
    val resolver = TrailerResolver(catalog.http, { ProviderConfig.key(db, "realdebrid") })
    val player = DesktopPlayer.create()
    println("libVLC loaded: ${player != null}")
    player?.setVolume(0)
    val titles = catalog.tmdb.trendingDay(catalog.key(), "all").take(12).mapNotNull { item ->
        val kind = item.mediaType ?: "movie"
        catalog.tmdb.details(catalog.key(), kind, item.id, full = true)?.trailerYoutubeKey?.let { item.title to it }
    }.take(3)
    println("titles with a trailer: ${titles.size}")
    for ((title, key) in titles) {
        val started = System.currentTimeMillis()
        val stream = resolver.resolve(key)
        val took = System.currentTimeMillis() - started
        if (stream == null) {
            println("[$title] unresolved after ${took}ms")
            continue
        }
        println("[$title] ${stream.method} in ${took}ms host=${stream.videoUrl.toHttpUrlOrNull()?.host} separateAudio=${stream.audioUrl != null}")
        if (player == null) continue
        player.play(StreamRequest(stream.videoUrl, null, null, title, live = false, audioUrl = stream.audioUrl))
        player.setVolume(0)
        delay(14_000)
        val s = player.status.value
        println("[$title] playing=${s.playing} timeMs=${s.timeMs} lengthMs=${s.lengthMs} video=${s.video} audioTracks=${s.audio.size} error=${s.error != null}")
        player.stop()
        delay(800)
    }
    player?.release()
    db.close()
    kotlin.system.exitProcess(0)
}
