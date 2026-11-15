package com.sokoul.desktop.data

/** Keys of the PC's settings (table `settings`). */
object Settings {
    const val ACTIVE_PLAYLIST = "active_playlist"
    const val PIN = "parental_pin"
    const val TMDB_KEY = "tmdb_key"
    const val USER_AGENT = "user_agent"
    const val EPG_OFFSET = "epg_offset_minutes"
    const val WINDOW = "window_bounds"
    const val LAST_SCREEN = "last_screen"
    const val LAST_GROUP = "last_group"
    /** The same User-Agent as the Android app: the provider sees the same client. */
    const val DEFAULT_USER_AGENT = "SokoulTV/2.0 (Linux; Android) ExoPlayer"
}
