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
