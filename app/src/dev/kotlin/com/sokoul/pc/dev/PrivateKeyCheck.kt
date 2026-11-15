package com.sokoul.pc.dev

import com.sokoul.pc.data.CredentialValidator
import com.sokoul.pc.data.Verdict
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Owner-only build preparation. Does not open or modify a desktop database. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 3) { "Expected private input, output directory and checkout path" }
    val input = File(args[0]).canonicalFile
    val output = File(args[1]).canonicalFile
    val checkout = File(args[2]).canonicalFile.toPath()
    require(!input.toPath().startsWith(checkout) && !output.toPath().startsWith(checkout)) { "Private files must remain outside the checkout" }
    val properties = Properties().apply { input.inputStream().use(::load) }
    val validator = CredentialValidator(OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build())
    val gate = Semaphore(2)
    val verdicts = properties.stringPropertyNames().filter { it.startsWith("sokoul.key.") }.sorted().map { name ->
        async { gate.withPermit {
            val id = name.removePrefix("sokoul.key.")
            val verdict = validator.key(id, properties.getProperty(name))
            println("$id: ${verdict.name}")
            id to verdict
        } }
    }.awaitAll().toMap()
    output.mkdirs()
    val accepted = Properties().apply {
        verdicts.filterValues { it == Verdict.Accepted }.keys.forEach { id -> setProperty("sokoul.key.$id", properties.getProperty("sokoul.key.$id")) }
    }
    File(output, "accepted-keys.properties").outputStream().use { accepted.store(it, "Private validated build input") }
    File(output, "key-verdicts.json").writeText(buildJsonObject { verdicts.forEach { (id, verdict) -> put(id, verdict.name) } }.toString())
    require(verdicts["tmdb"] == Verdict.Accepted) { "TMDB must validate before this private build is installed" }
    println("Accepted ${accepted.size} provider keys; values remain private.")
}
