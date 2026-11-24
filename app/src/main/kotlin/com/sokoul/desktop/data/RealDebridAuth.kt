package com.sokoul.desktop.data

import com.sokoul.iptv.data.debrid.RealDebridOAuth

/**
 * Keeps a Real-Debrid sign-in made with a device code alive. The access token becomes the "realdebrid" key every other
 * part of the app already reads; the device's own credentials and the refresh token stay in the protected database.
 */
class RealDebridAuth(private val db: DesktopDb, private val oauth: RealDebridOAuth) {

    fun signedInWithDevice(): Boolean = db.setting(K_REFRESH) != null

    /** Stores a fresh sign-in. */
    fun store(credentials: RealDebridOAuth.Credentials, token: RealDebridOAuth.Token, now: Long = System.currentTimeMillis()) {
        db.putSetting(K_CLIENT_ID, credentials.clientId)
        db.putSetting(K_CLIENT_SECRET, credentials.clientSecret)
        save(token, now)
    }

    /** Renews the access token a day before it ends. Returns false only when Real-Debrid refused the renewal. */
    suspend fun refreshIfNeeded(now: Long = System.currentTimeMillis()): Boolean {
        val refresh = db.setting(K_REFRESH) ?: return true
        val expiresAt = db.setting(K_EXPIRES_AT)?.toLongOrNull() ?: 0L
        if (expiresAt - now > REFRESH_MARGIN_MS) return true
        val id = db.setting(K_CLIENT_ID) ?: return false
        val secret = db.setting(K_CLIENT_SECRET) ?: return false
        return runCatching { save(oauth.token(RealDebridOAuth.Credentials(id, secret), refresh), now) }.isSuccess
    }

    /** Forgets the device sign-in (the key falls back to a typed or built-in one). */
    fun signOut() {
        listOf(K_CLIENT_ID, K_CLIENT_SECRET, K_REFRESH, K_EXPIRES_AT).forEach { db.putSetting(it, null) }
        ProviderConfig.setKey(db, "realdebrid", null)
    }

    private fun save(token: RealDebridOAuth.Token, now: Long) {
        ProviderConfig.setKey(db, "realdebrid", token.accessToken)
        db.putSetting(K_REFRESH, token.refreshToken)
        db.putSetting(K_EXPIRES_AT, (now + token.expiresInSeconds * 1000).toString())
    }

    private companion object {
        const val K_CLIENT_ID = "rd_oauth_client_id"
        const val K_CLIENT_SECRET = "rd_oauth_client_secret"
        const val K_REFRESH = "rd_oauth_refresh"
        const val K_EXPIRES_AT = "rd_oauth_expires_at"
        const val REFRESH_MARGIN_MS = 24L * 60 * 60 * 1000
    }
}
