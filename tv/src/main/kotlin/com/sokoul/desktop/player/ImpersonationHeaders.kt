package com.sokoul.desktop.player

import java.net.URI

/** Shared origin parsing for the native browser profile. */
internal object ImpersonationHeaders {
    const val ACCEPT_LANGUAGE = "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7"
    fun hostnameOf(raw: String): String? = runCatching { URI(raw).host?.lowercase()?.trimEnd('.') }.getOrNull()
    fun apply(url: String, headers: Map<String, String>): Map<String, String> = headers
}
