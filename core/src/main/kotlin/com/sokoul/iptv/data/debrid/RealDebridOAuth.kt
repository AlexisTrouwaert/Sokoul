package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.data.net.await
import com.sokoul.iptv.parser.long
import com.sokoul.iptv.parser.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Real-Debrid's device sign-in for open-source apps: the screen shows a short code, the person confirms it on
 * real-debrid.com/device from any phone, and the app receives its own credentials and a token. No API key is typed.
 * Nothing here is logged.
 */
class RealDebridOAuth(
    private val http: OkHttpClient,
    private val base: String = BASE,
) {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        /** Opens the confirmation page with the code already filled in, when Real-Debrid gives one. */
        val directUrl: String?,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    data class Credentials(val clientId: String, val clientSecret: String)

    data class Token(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

    suspend fun deviceCode(): DeviceCode {
        val o = getJson("$base/device/code?client_id=$OPEN_SOURCE_CLIENT_ID&new_credentials=yes")
            ?: throw RdException(0, "Unexpected Real-Debrid answer")
        return DeviceCode(
            deviceCode = o.str("device_code") ?: throw RdException(0, "No device code"),
            userCode = o.str("user_code") ?: throw RdException(0, "No user code"),
            verificationUrl = o.str("verification_url") ?: "https://real-debrid.com/device",
            directUrl = o.str("direct_verification_url"),
            intervalSeconds = (o.long("interval") ?: 5).toInt().coerceIn(2, 30),
            expiresInSeconds = (o.long("expires_in") ?: 600).toInt(),
        )
    }

    /** Null while the person has not confirmed the code yet. */
    suspend fun credentials(deviceCode: String): Credentials? {
        val o = getJson("$base/device/credentials?client_id=$OPEN_SOURCE_CLIENT_ID&code=$deviceCode", pendingIsNull = true) ?: return null
        val id = o.str("client_id") ?: return null
        val secret = o.str("client_secret") ?: return null
        return Credentials(id, secret)
    }

    /** First token from the device code, or a new one from a refresh token (same call, as Real-Debrid defines it). */
    suspend fun token(credentials: Credentials, code: String): Token {
        val body = FormBody.Builder()
            .add("client_id", credentials.clientId)
            .add("client_secret", credentials.clientSecret)
            .add("code", code)
            .add("grant_type", DEVICE_GRANT)
            .build()
        val request = Request.Builder().url("$base/token").post(body).build()
        http.newCall(request).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RdException(r.code, "Token refused")
            val o = Json.parseToJsonElement(text) as? JsonObject ?: throw RdException(0, "Unexpected Real-Debrid answer")
            return Token(
                accessToken = o.str("access_token") ?: throw RdException(0, "No access token"),
                refreshToken = o.str("refresh_token") ?: throw RdException(0, "No refresh token"),
                expiresInSeconds = o.long("expires_in") ?: 3600,
            )
        }
    }

    private suspend fun getJson(url: String, pendingIsNull: Boolean = false): JsonObject? {
        http.newCall(Request.Builder().url(url).get().build()).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (pendingIsNull && r.code in 400..499) return null
                throw RdException(r.code, "HTTP ${r.code}")
            }
            return Json.parseToJsonElement(text) as? JsonObject
        }
    }

    companion object {
        const val BASE = "https://api.real-debrid.com/oauth/v2"
        /** The client id Real-Debrid publishes for open-source apps; each device then receives its own credentials. */
        const val OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"
        private const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
    }
}
