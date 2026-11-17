package com.sokoul.pc.playback.sources
import com.sokoul.pc.playback.session.PlayVersion
import com.sokoul.pc.playback.session.Readiness
import com.sokoul.pc.playback.session.VersionOrigin
import com.sokoul.desktop.data.AudioPreference
import com.sokoul.desktop.data.MaxQuality
import com.sokoul.desktop.data.MaxSize
import com.sokoul.desktop.data.PlaybackPrefs
import com.sokoul.desktop.data.PreferredOrigin
import com.sokoul.desktop.data.StartMode
import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.iptv.domain.HdrFormat
import com.sokoul.iptv.domain.VideoQuality

/**
 * The order of versions, pure and tested: playable here, then language, then ready or quality according to the start
 * mode. SDR comes before HDR because the picture goes through Compose as RV32 with no tone mapping, which would wash out
 * HDR colors.
 */
object VersionRanking {
    /** Most wanted first. "According to the system" follows the system's language. */
    fun audioOrder(preference: AudioPreference, frenchSystem: Boolean): List<AudioLanguage> = when (preference) {
        AudioPreference.System -> if (frenchSystem) listOf(AudioLanguage.VF, AudioLanguage.MULTI, AudioLanguage.VOSTFR, AudioLanguage.VO) else listOf(AudioLanguage.VO, AudioLanguage.MULTI, AudioLanguage.VOSTFR, AudioLanguage.VF)
        AudioPreference.Vf -> listOf(AudioLanguage.VF, AudioLanguage.MULTI, AudioLanguage.VOSTFR, AudioLanguage.VO)
        AudioPreference.Multi -> listOf(AudioLanguage.MULTI, AudioLanguage.VF, AudioLanguage.VO, AudioLanguage.VOSTFR)
        AudioPreference.Vostfr -> listOf(AudioLanguage.VOSTFR, AudioLanguage.VO, AudioLanguage.MULTI, AudioLanguage.VF)
        AudioPreference.Vo -> listOf(AudioLanguage.VO, AudioLanguage.VOSTFR, AudioLanguage.MULTI, AudioLanguage.VF)
    }

    fun overLimits(v: PlayVersion, prefs: PlaybackPrefs): Boolean =
        (v.sizeBytes ?: 0) > prefs.maxSize.bytes || qualityValue(v.quality) > prefs.maxQuality.height

    /** How wanted what one hears is, lower first. A MULTI file holds the French and the original track: it is as good as whichever of the two is wanted most. */
    fun heard(v: PlayVersion, prefs: PlaybackPrefs, frenchSystem: Boolean): Int {
        val audio = audioOrder(prefs.audio, frenchSystem)
        val exact = v.language?.let(audio::indexOf)?.takeIf { it >= 0 } ?: audio.size
        val frenchWanted = prefs.audio == AudioPreference.Vf || (prefs.audio == AudioPreference.System && frenchSystem)
        return if (v.language == AudioLanguage.MULTI && (!frenchWanted || "FR" in v.announcedAudio)) minOf(audio.indexOf(AudioLanguage.VF), audio.indexOf(AudioLanguage.VO), exact) else exact
    }

    /**
     * @param caps what this device can show
     * @param fact what a past viewing proved about a version: it counts over what its name announces
     * @param expectedMinutes the length of the work, to judge a size
     */
    fun rank(
        versions: List<PlayVersion>,
        prefs: PlaybackPrefs,
        frenchSystem: Boolean,
        readyFirst: Boolean = prefs.startMode != StartMode.WaitBest,
        caps: DeviceCaps = DeviceCaps.Pc,
        fact: (PlayVersion) -> SourceFact? = { null },
        expectedMinutes: Int? = null,
    ): List<PlayVersion> {
        val audio = audioOrder(prefs.audio, frenchSystem)
        // "Dual Audio", or two other languages named ("[spa, eng]", "ITA.ENG", flags): a foreign dub beside the original.
        fun dub(v: PlayVersion) = v.foreignDub || v.announcedAudio.count { it != "FR" } >= 2
        // Such a file is not proof of the original, even if a past viewing remembered it as VO.
        fun language(v: PlayVersion) = (fact(v)?.language ?: v.language).takeUnless { it == AudioLanguage.VO && dub(v) }
        fun exact(v: PlayVersion) = language(v)?.let(audio::indexOf)?.takeIf { it >= 0 } ?: audio.size
        val heard = { v: PlayVersion ->
            val l = language(v)
            val e = exact(v)
            val frenchWanted = prefs.audio == AudioPreference.Vf || (prefs.audio == AudioPreference.System && frenchSystem)
            val frenchEvidence = "FR" in v.announcedAudio || fact(v)?.language == AudioLanguage.MULTI
            if (l == AudioLanguage.MULTI && (!frenchWanted || frenchEvidence)) minOf(audio.indexOf(AudioLanguage.VF), audio.indexOf(AudioLanguage.VO), e) else e
        }
        // Original wanted: a release of unknown language that names two other languages or more ("spa, eng",
        // "ITA.ENG", flags 🇪🇸 / 🇬🇧) is a foreign dub with the original beside it. It comes after a plain release, which is
        // most often the original alone, even when it is ready and the plain one is not.
        val foreignDub = { v: PlayVersion ->
            val frenchWanted = prefs.audio == AudioPreference.Vf || (prefs.audio == AudioPreference.System && frenchSystem)
            if (!frenchWanted && language(v) == null && dub(v)) 1 else 0
        }
        val now = System.currentTimeMillis()
        // A version that failed for a lasting reason keeps its place in the list, after all the others.
        // A version of another work or episode (anime) goes after everything, even what failed.
        val base = compareBy<PlayVersion> { if (it.rejected != null) 1 else 0 }
            .thenBy { if (fact(it)?.let { f -> f.failure != null && f.until > now } == true) 1 else 0 }
            // The wanted language first: a VF above the size or quality limits still comes before a VOSTFR within them.
            .thenBy(heard)
            .thenBy(foreignDub)
            .thenBy { if (overLimits(it, prefs)) 1 else 0 }
            .thenBy { originRank(it, prefs.origin) }
            // Anime, at the same language: a file that says it is the episode, then one that cannot be read, then a doubtful one.
            .thenBy { it.confidence }
            .thenBy { if (SourceRules.suspicious(it, expectedMinutes)) 1 else 0 }
        // IPTV is always there but counts on the account's connections: it comes after what Real-Debrid has ready.
        val byReady = compareBy<PlayVersion> { if (it.origin == VersionOrigin.Iptv) IPTV_READY_RANK else readyRank(it.readiness) }
        // Windows wants SDR first. A TV that shows HDR wants the sharpest, then the richest; a format it cannot show goes after SDR.
        val byPicture = if (caps.hdr10 || caps.dolbyVision) {
            compareBy<PlayVersion> { if (SourceRules.pictureRank(it.hdr, caps) > 2) 1 else 0 }
                .thenBy { qualityRank(SourceRules.usefulQuality(it.quality, caps)) }
                .thenBy { SourceRules.pictureRank(it.hdr, caps) }
        } else {
            compareBy<PlayVersion> { if (it.hdr == null) 0 else hdrRank(it.hdr) }.thenBy { qualityRank(SourceRules.usefulQuality(it.quality, caps)) }
        }
        // At the same language and picture, the release the SeaDex community keeps comes first.
        val rest = compareBy<PlayVersion> { if (it.community) 0 else 1 }
            .thenBy(::exact)
            .thenBy { it.ripRank }
            .thenBy { it.frenchVariantRank }
            .thenByDescending { it.seeders ?: 0 }
            .thenByDescending { it.sizeBytes ?: 0 }
            .thenBy { it.key }
        val order = (if (readyFirst) base.then(byReady).then(byPicture) else base.then(byPicture).then(byReady)).then(rest)
        return versions.sortedWith(order)
    }

    private fun originRank(v: PlayVersion, preferred: PreferredOrigin) = when (preferred) {
        PreferredOrigin.Automatic -> 0
        PreferredOrigin.Debrid -> if (v.origin == VersionOrigin.Debrid) 0 else 1
        PreferredOrigin.Iptv -> if (v.origin == VersionOrigin.Iptv) 0 else 1
    }

    private const val IPTV_READY_RANK = 2

    private fun readyRank(r: Readiness) = when (r) {
        Readiness.Ready -> 0
        Readiness.Likely -> 1
        Readiness.Unknown -> 3
        Readiness.Preparing -> 4
        Readiness.NotCached -> 5
    }

    private fun qualityRank(q: VideoQuality?) = when (q) {
        VideoQuality.UHD_4K -> 0
        VideoQuality.FHD_1080 -> 1
        VideoQuality.HD_720, null -> 2
        VideoQuality.SD -> 3
    }

    private fun qualityValue(q: VideoQuality?) = when (q) {
        VideoQuality.UHD_4K -> 2160
        VideoQuality.FHD_1080 -> 1080
        VideoQuality.HD_720, null -> 720
        VideoQuality.SD -> 480
    }

    private fun hdrRank(h: HdrFormat) = when (h) {
        HdrFormat.HDR10 -> 1
        HdrFormat.DOLBY_VISION -> 2
    }

    /** True when [a] shows a sharper picture than [b]. */
    fun sharper(a: PlayVersion, b: PlayVersion) = qualityRank(a.quality) < qualityRank(b.quality)
}

/** A version to try, and whether to wait for its download if it is not ready. */
data class StartChoice(val version: PlayVersion, val keep: Boolean)

/** Which version comes next, according to the start preference. Pure and tested. */
object StartPolicy {
    /** Tries without waiting, each costing seconds on the account. A version known to be ready is always worth it. */
    private const val MAX_LIKELY_TRIES = 10
    private const val MAX_UNKNOWN_TRIES = 5

    /**
     * @param ranked the versions, best first
     * @param failed versions that must not be tried again
     * @param quickTried versions already tried without waiting
     * @param blocked origins that cannot work at the moment
     */
    fun next(
        ranked: List<PlayVersion>,
        mode: StartMode,
        failed: Set<String>,
        quickTried: Set<String>,
        blocked: Set<VersionOrigin>,
        failedHosts: Set<String> = emptySet(),
    ): StartChoice? {
        if (mode == StartMode.Ask) return null
        // A contradicted version (anime) is only ever played when the viewer picks it.
        val open = ranked.filter { it.key !in failed && it.origin !in blocked && it.rejected == null }
        // After a service gave a bad link, its other links are likely as bad: those of other services come first.
        val usable = open.filter { it.providerHost == null || it.providerHost !in failedHosts } + open.filter { it.providerHost != null && it.providerHost in failedHosts }
        if (mode == StartMode.WaitBest) return usable.firstOrNull()?.let { StartChoice(it, keep = true) }
        val quick = usable.firstOrNull { it.key !in quickTried && worthATry(it.readiness, quickTried.size) }
        if (quick != null) return StartChoice(quick, keep = false)
        // Nothing can start right away: the best version is downloaded, with its progress on screen.
        return usable.firstOrNull { it.origin == VersionOrigin.Debrid }?.let { StartChoice(it, keep = true) }
    }

    /**
     * The versions to try at once, when the next choice would be a quick try of a Real-Debrid version that is not in the
     * account: it and the next ones of the same kind, [size] at most. Empty when the choice is anything else (a version
     * in the account, IPTV, a download to wait for): that one goes alone, as before.
     */
    fun race(
        ranked: List<PlayVersion>,
        mode: StartMode,
        failed: Set<String>,
        quickTried: Set<String>,
        blocked: Set<VersionOrigin>,
        failedHosts: Set<String>,
        size: Int,
    ): List<PlayVersion> {
        if (size < 2) return emptyList()
        val first = next(ranked, mode, failed, quickTried, blocked, failedHosts) ?: return emptyList()
        if (first.keep || first.version.origin != VersionOrigin.Debrid || first.version.readiness == Readiness.Ready) return emptyList()
        val open = ranked.filter { it.key !in failed && it.origin !in blocked && it.rejected == null }
        val usable = open.filter { it.providerHost == null || it.providerHost !in failedHosts } + open.filter { it.providerHost != null && it.providerHost in failedHosts }
        val group = usable.filter {
            it.origin == VersionOrigin.Debrid && it.key !in quickTried && it.readiness != Readiness.Ready && worthATry(it.readiness, quickTried.size)
        }.take(size)
        return if (group.size > 1) group else emptyList()
    }

    private fun worthATry(readiness: Readiness, tries: Int) = when (readiness) {
        Readiness.Ready -> true
        Readiness.Likely -> tries < MAX_LIKELY_TRIES
        Readiness.Unknown -> tries < MAX_UNKNOWN_TRIES
        Readiness.Preparing, Readiness.NotCached -> false
    }

    /** The version worth fetching behind the film: sharper than the one playing, in a language at least as wanted. */
    fun upgrade(best: List<PlayVersion>, playing: PlayVersion, failed: Set<String>, heard: (PlayVersion) -> Int): PlayVersion? =
        best.firstOrNull { it.key != playing.key && it.key !in failed && it.origin == VersionOrigin.Debrid && heard(it) <= heard(playing) }
            ?.takeIf { VersionRanking.sharper(it, playing) }
}

/** Keeps the name of the size limits in one place for the settings. */
val MaxSize.gigabytes: Int? get() = if (bytes == Long.MAX_VALUE) null else (bytes / 1_000_000_000L).toInt()

val MaxQuality.label: String get() = when (this) {
    MaxQuality.P720 -> "720p"
    MaxQuality.P1080 -> "1080p"
    MaxQuality.P2160 -> "4K"
}
