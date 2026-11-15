package com.sokoul.pc.dev

import com.sokoul.desktop.data.AppDirs
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.data.ProviderProbe
import com.sokoul.pc.data.CredentialValidator
import com.sokoul.pc.data.LegacyImport
import com.sokoul.pc.importOnce
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real-account check. Prints yes/no and counts only: never a key, a password or a server address.
 * It also searches the project for any known key value, and reports how many files contain one, never which value.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val db = DesktopDb.open()
    importOnce(db, LegacyImport.defaultSource(), AppDirs.data)
    val playlists = db.playlists()
    println("playlists: ${playlists.size}")
    println("keys: " + ProviderConfig.statuses(db).joinToString { "${it.id}=${it.configured}" })
    val validator = CredentialValidator(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build())
    ProviderConfig.providers
        .map { it.first }
        .filterNot { it == "mainapi" || it == "mainapi_url" }
        .forEach { id ->
            if (ProviderConfig.key(db, id) != null) {
                val result = ProviderProbe.check(db, id)
                println("$id check: ${result.message}")
            }
        }
    playlists.firstOrNull()?.let { p ->
        println("iptv type: ${p.type}")
        println("iptv check: ${validator.iptv(p.url, p.username.orEmpty(), p.password.orEmpty(), p.userAgent)}")
    }

    val secrets = (ProviderConfig.providers.mapNotNull { (id, _) -> ProviderConfig.key(db, id) } + playlists.mapNotNull { it.password })
        .filter { it.length >= 6 }
    db.close()
    val root = File(args.first())
    val skipped = setOf("renders", "build-output", "node_modules", ".git", ".gradle")
    var scanned = 0
    var hits = 0
    root.walkTopDown().onEnter { it.name !in skipped }.filter { it.isFile && it.length() < 5_000_000 }.forEach { file ->
        scanned++
        val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
        if (secrets.any { text.contains(it) }) hits++
    }
    println("project scan: files=$scanned filesWithAKey=$hits secretsCompared=${secrets.size}")
    kotlin.system.exitProcess(0)
}
