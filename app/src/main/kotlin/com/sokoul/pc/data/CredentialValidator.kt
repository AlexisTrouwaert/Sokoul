package com.sokoul.pc.data

import com.sokoul.desktop.data.ProviderProbe
import com.sokoul.iptv.data.net.XtreamAuth
import com.sokoul.iptv.data.net.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

enum class Verdict { Accepted, Refused, Unreachable }

/** What an IPTV account says about itself, shown once it is accepted. */
data class IptvCheck(val verdict: Verdict, val expiresAt: Long? = null, val maxConnections: Int? = null, val trial: Boolean = false)

/** The Real-Debrid account behind a token. */
data class DebridCheck(val verdict: Verdict, val username: String? = null, val premiumDaysLeft: Int? = null)

/** Asks each service about a credential before anything is stored. Verdicts never contain the credential. */
class CredentialValidator(
    private val http: OkHttpClient,
    private val tmdbBase: String = "https://api.themoviedb.org",
    private val realDebridBase: String = "https://api.real-debrid.com",
    private val otherKey: suspend (id: String, key: String) -> Boolean = { id, key -> ProviderProbe.checkKey(id, key).ok },
) {
    private val xtream = XtreamClient(http, Json { ignoreUnknownKeys = true; isLenient = true })

    suspend fun iptv(server: String, username: String, password: String, userAgent: String?): Verdict = withContext(Dispatchers.IO) {
        runCatching {
            if (xtream.account(XtreamAuth(server.trimEnd('/'), username, password, userAgent)).isActive) Verdict.Accepted else Verdict.Refused
        }.getOrElse { Verdict.Refused }
    }

    /** Like [iptv], but keeps what the provider says about the subscription. A wrong server is unreachable, not refused. */
    suspend fun iptvAccount(server: String, username: String, password: String, userAgent: String? = null): IptvCheck = withContext(Dispatchers.IO) {
        runCatching {
            val a = xtream.account(XtreamAuth(server.trimEnd('/'), username, password, userAgent))
            if (a.isActive) IptvCheck(Verdict.Accepted, a.expiresAt, a.maxConnections, a.isTrial) else IptvCheck(Verdict.Refused)
        }.getOrElse { e ->
            if (e is java.io.IOException) IptvCheck(Verdict.Unreachable) else IptvCheck(Verdict.Refused)
        }
    }

    /** An M3U link answers with a playlist: its first bytes are "#EXTM3U". */
    suspend fun m3u(url: String): Verdict = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url.trim()).header("Range", "bytes=0-4095").build()).execute().use { r ->
                when {
                    r.code == 401 || r.code == 403 || r.code == 404 -> Verdict.Refused
                    !r.isSuccessful -> Verdict.Unreachable
                    r.body?.source()?.let { s -> s.request(16); s.buffer.snapshot().utf8().trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U") } == true -> Verdict.Accepted
                    else -> Verdict.Refused
                }
            }
        }.getOrElse { Verdict.Unreachable }
    }

    /** Checks a Real-Debrid token and reads the account it belongs to. */
    suspend fun realDebrid(token: String, now: Long = System.currentTimeMillis()): DebridCheck = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$realDebridBase/rest/1.0/user").header("Authorization", "Bearer $token").build()).execute().use { r ->
                when {
                    r.isSuccessful -> {
                        val o = Json.parseToJsonElement(r.body?.string().orEmpty()) as? kotlinx.serialization.json.JsonObject
                        val premium = (o?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content == "premium"
                        val expiration = (o?.get("expiration") as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                        DebridCheck(
                            Verdict.Accepted,
                            username = (o?.get("username") as? kotlinx.serialization.json.JsonPrimitive)?.content,
                            premiumDaysLeft = if (premium && expiration != null) ((expiration - now) / 86_400_000L).toInt().coerceAtLeast(0) else 0,
                        )
                    }
                    r.code == 401 || r.code == 403 -> DebridCheck(Verdict.Refused)
                    else -> DebridCheck(Verdict.Unreachable)
                }
            }
        }.getOrElse { DebridCheck(Verdict.Unreachable) }
    }

    /** The Sokoul server answers on its address (any HTTP reply means the address is right). */
    suspend fun reachable(url: String): Verdict = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url.trim().trimEnd('/') + "/").build()).execute().use { Verdict.Accepted }
        }.getOrElse { Verdict.Unreachable }
    }

    suspend fun key(id: String, key: String): Verdict = withContext(Dispatchers.IO) {
        when (id) {
            "tmdb" -> status(Request.Builder().url("$tmdbBase/3/configuration?api_key=$key").build())
            "realdebrid" -> status(Request.Builder().url("$realDebridBase/rest/1.0/user").header("Authorization", "Bearer $key").build())
            else -> runCatching { if (otherKey(id, key)) Verdict.Accepted else Verdict.Refused }.getOrElse { Verdict.Unreachable }
        }
    }

    private fun status(request: Request): Verdict = runCatching {
        http.newCall(request).execute().use { r ->
            when {
                r.isSuccessful -> Verdict.Accepted
                r.code == 401 || r.code == 403 -> Verdict.Refused
                else -> Verdict.Unreachable
            }
        }
    }.getOrElse { Verdict.Unreachable }
}
