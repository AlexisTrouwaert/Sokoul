package com.sokoul.iptv.data.debrid

import com.sokoul.iptv.domain.AudioLanguage
import com.sokoul.iptv.domain.HdrFormat
import com.sokoul.iptv.domain.VideoQuality

/** Origine du fichier, de la meilleure à la moins bonne. */
enum class RipSource(val label: String) { REMUX("Remux"), BLURAY("Blu-ray"), WEBDL("WEB"), WEBRIP("WEBRip"), HDTV("HDTV") }

/** Tout ce qu'on lit dans le nom d'une version (« Dune.2021.MULTi.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC… »). */
data class Release(
    val quality: VideoQuality? = null,
    val hdr: HdrFormat? = null,
    val language: AudioLanguage? = null,
    /** Variante française précise : « TRUEFRENCH », « VFF », « VFQ »… (pour départager deux VF). */
    val frenchVariant: String? = null,
    val source: RipSource? = null,
    val codec: String? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    /** Film filmé en salle (CAM, TS, TC, SCR) : jamais proposé. */
    val cam: Boolean = false,
    /** Déjà prêt chez Real-Debrid (« [RD+] », « ⚡ »), pas prêt (« [RD download] »), ou inconnu. */
    val cached: Boolean? = null,
    /** Épisodes contenus (pack de saison) : S02E05 → (2, 5) ; vide = inconnu ou film. */
    val season: Int? = null,
    val episode: Int? = null,
    /** Langues audio lues dans le nom (codes ISO : « FR », « EN », « IT »…), dans l'ordre trouvé ; vide = non indiqué. */
    val audio: List<String> = emptyList(),
    /**
     * Doublage étranger à côté de la VO, sans français : « Dual Audio » (langue locale + anglais) ou deux autres langues
     * annoncées (« spa, eng », « ITA.ENG »). Une telle version n'est pas la VO seule.
     */
    val dub: Boolean = false,
)

/**
 * Lecture des noms de versions (fichiers, extensions Stremio type Torrentio / Comet / MediaFusion).
 * Logique pure, testée sur de vrais noms. Inspiré de `legacy/GinBox/sokoul-backend/src/parser.rs`.
 */
object ReleaseName {
    private val tokenSep = Regex("[^A-Za-z0-9+]+")

    // Compiled once: parse runs for each of some 800 versions, again at each answer of a service, on a TV too.
    private val rxVideoExtLine = Regex("(?im)\\.(mkv|mp4|avi|ts|m2ts|m4v|mov|webm)$")
    private val rxExplicitAudio = Regex("(?i)\\b(?:vf[0-9]*|vff|vfq|vfi|vfb|truefrench)\\b")
    private val rxSeedersIcon = Regex("👤\\s*(\\d+)")
    private val rxSeedersWord = Regex("(?i)seeders?:?\\s*(\\d+)")
    private val rxHdCam = Regex("(?i)\\bHD-?(CAM|TS|TC)\\b")
    private val rxDual = Regex("(?i)\\bdual\\b")
    /** One flag emoji: two regional indicator letters. */
    private val rxFlag = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
    private val rxSize = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|GO|MB|MO|GIB|MIB)\\b")
    // PR #7: "S04E006_Episode 6.mkv": "_" is a word character, so a "\\b" after the number would not read it.
    private val rxSxE = Regex("(?i)(?<![a-z0-9])S(\\d{1,2})[ ._-]?E(\\d{1,3})(?![0-9])")
    private val rxNxN = Regex("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9a-z])")
    private val rxSample = Regex("(?i)\\bsample\\b")
    private val rxExtras = Regex("(?i)(/|\\b)(extras?|bonus|featurettes?)(/|\\b)")
    /** A service's own "instant" label, never the word inside a title ("Instant Family"). */
    private val rxInstantLabel = Regex("(?i)\\[(?:RD\\s*)?INSTANT\\]|\\bINSTANT\\s+(?:RD|AVAILABLE|PLAY)\\b")

    fun parse(vararg texts: String?): Release {
        // extension retirée : « .ts » n'est pas « TS » (telesync)
        val all = texts.filterNotNull().joinToString("\n").replace(rxVideoExtLine, " ")
        val tokens = all.split(tokenSep).filter { it.isNotEmpty() }.map { it.uppercase() }.toSet()
        val up = all.uppercase()
        val subtitleLines = all.lineSequence().filter { subtitleLabel.containsMatchIn(it) && !audioLabel.containsMatchIn(it) }.toList()
        val audioText = all.lineSequence().map { line ->
            if (line in subtitleLines) {
                val at = subtitleLabel.find(line)!!.range.first
                val explicitAudio = rxExplicitAudio.findAll(line.substring(at)).joinToString(" ") { it.value }
                line.substring(0, at) + " " + explicitAudio
            } else line
        }.joinToString("\n")
            // "Dual Audio" is two tracks: more than two flags beside it name the subtitles (Torrentio puts them on the
            // same line: "Dubbed / Multi Subs / Dual Audio / 🇬🇧 / 🇷🇺 / … / 🇫🇷"), not a French track.
            .let { text -> if (rxDual.containsMatchIn(text) && flags(text).distinct().size > 2) text.replace(rxFlag, " ") else text }
        val audioTokens = audioText.split(tokenSep).map { it.uppercase() }.toSet()
        // Read once, used by the language, the announced tracks and the dub marker.
        // A name that says VOSTFR has French subtitles: a 🇫🇷 flag beside it (Torrentio flags subtitles too) is no French
        // track; only a French word in the name ("VOSTFR.FRENCH", "VF") still announces one.
        val vostfr = audioTokens.any { it in VOSTFR_TAGS }
        val french = hasFrenchTag(audioText, audioTokens) && (!vostfr || hasFrenchTag(audioText.replace("🇫🇷", " "), audioTokens))
        val others = audio(audioText)
        val announced = language(audioText, audioText.uppercase(), audioTokens, others, french)
        val frenchSubtitles = subtitleLines.any { line -> "FR" in flags(line) || line.split(tokenSep).any { it.uppercase() in FRENCH_TAGS } }
        val episodeTag = episodeTag(all)
        return Release(
            quality = quality(up, tokens),
            hdr = hdr(up, tokens),
            language = if (frenchSubtitles && (announced == null || announced == AudioLanguage.VO)) AudioLanguage.VOSTFR else announced,
            frenchVariant = listOf("TRUEFRENCH", "VFF", "VFQ", "VFI", "VF2", "VFB").firstOrNull { it in tokens },
            source = source(up, tokens),
            codec = when {
                "AV1" in tokens -> "AV1"
                tokens.any { it in setOf("HEVC", "X265", "H265") } || "H.265" in up -> "HEVC"
                tokens.any { it in setOf("X264", "H264", "AVC") } || "H.264" in up -> "H.264"
                else -> null
            },
            sizeBytes = size(all),
            seeders = rxSeedersIcon.find(all)?.groupValues?.get(1)?.toIntOrNull()
                ?: rxSeedersWord.find(all)?.groupValues?.get(1)?.toIntOrNull(),
            cam = tokens.any { it in CAM } || rxHdCam.containsMatchIn(all),
            cached = cached(all, up),
            season = episodeTag?.first,
            episode = episodeTag?.second,
            audio = (if (french) listOf("FR") else emptyList()) + others,
            dub = !french && (rxDual.containsMatchIn(audioText) || others.size >= 2),
        )
    }

    private val CAM = setOf("CAM", "CAMRIP", "HDCAM", "TS", "HDTS", "TELESYNC", "TC", "TELECINE", "SCR", "SCREENER", "DVDSCR", "HDTC")

    /**
     * Résolution écrite d'abord : « UHD.Bluray.1080p » est un 1080p (vu sur un fichier classé 4K à tort) ;
     * « UHD », « 4K » et « FHD » ne décident que si aucune résolution n'est écrite.
     */
    private fun quality(up: String, t: Set<String>): VideoQuality? = when {
        t.any { it in setOf("2160P", "2160") } || "3840X2160" in up -> VideoQuality.UHD_4K
        t.any { it in setOf("1080P", "1080I", "1080") } || "1920X1080" in up -> VideoQuality.FHD_1080
        t.any { it in setOf("720P", "720") } || "1280X720" in up -> VideoQuality.HD_720
        t.any { it in setOf("480P", "576P") } -> VideoQuality.SD
        t.any { it in setOf("4K", "UHD") } -> VideoQuality.UHD_4K
        "FHD" in t -> VideoQuality.FHD_1080
        t.any { it in setOf("SD", "DVDRIP", "XVID") } -> VideoQuality.SD
        else -> null
    }

    /** Explicit French tags (whole tokens only: « FREE » or « FRANCE » are not language tags). */
    private val FRENCH_TAGS = setOf("FRENCH", "TRUEFRENCH", "VFF", "VFQ", "VFI", "VF", "VF2", "VFB", "FR", "FRE", "FRA")
    private val subtitleLabel = Regex("(?i)\\b(multi[ ._-]?subs?|subbed|subtitles?|sous[ ._-]?titres)\\b")
    private val audioLabel = Regex("(?i)\\b(audio|dub|dubbed)\\b")
    private val EXPLICIT_FRENCH_TAGS = FRENCH_TAGS - "FRENCH"
    private val VOSTFR_TAGS = setOf("VOSTFR", "SUBFRENCH", "STFR")
    // An episode number also ends the title: "Dragon Ball Super E001 FRENCH 720p" is French, not VO.
    private val releaseMarker = Regex("(?i)\\b(19\\d{2}|20\\d{2})\\b|\\bS\\d{1,2}[ ._-]?E\\d{1,3}\\b|\\bE\\d{1,4}\\b|\\b(?:episode|ep)[ ._-]?\\d{1,4}\\b|\\s-\\s\\d{2,4}\\b|\\b\\d{3,4}\\b|\\b\\d{2}\\b(?=.*\\b(?:480|720|1080|2160)p\\b)")
    private val frenchPrefix = Regex("(?i)^\\s*(?:\\[(?:french|truefrench|vff|vfq|vfi|vf2|vfb|vf|fr|fre|fra)\\]|(?:truefrench|vff|vfq|vfi|vf2|vfb|vf|fr|fre|fra)\\s*[-:|]\\s*|french\\s+(?:dub|audio|track)\\b)")

    /** The plain word « French » is part of many titles; accept it as a language tag only in an unambiguous label position. */
    private fun hasFrenchTag(all: String, tokens: Set<String>): Boolean =
        "🇫🇷" in all || tokens.any { it in EXPLICIT_FRENCH_TAGS } || all.lineSequence().any { line ->
            val suffixFrench = releaseMarker.find(line)?.let { match ->
                line.substring(match.range.last + 1).split(tokenSep).any { it.equals("FRENCH", ignoreCase = true) }
            } == true
            suffixFrench || frenchPrefix.containsMatchIn(line)
        }

    private fun hdr(up: String, t: Set<String>): HdrFormat? = when {
        t.any { it in setOf("DV", "DOVI", "DOLBYVISION") } || "DOLBY VISION" in up || "DOLBY.VISION" in up -> HdrFormat.DOLBY_VISION
        t.any { it in setOf("HDR", "HDR10", "HDR10+", "HDR10PLUS") } -> HdrFormat.HDR10
        else -> null
    }

    private fun source(up: String, t: Set<String>): RipSource? = when {
        "REMUX" in t || "BDREMUX" in t -> RipSource.REMUX
        t.any { it in setOf("BLURAY", "BDRIP", "BRRIP", "BD") } || "BLU-RAY" in up -> RipSource.BLURAY
        t.any { it in setOf("WEBDL", "WEB") } || "WEB-DL" in up -> RipSource.WEBDL
        "WEBRIP" in t -> RipSource.WEBRIP
        t.any { it in setOf("HDTV", "PDTV", "DSR", "TVRIP") } -> RipSource.HDTV
        else -> null
    }

    private val rxMultiAudio = Regex("(?i)(?<![a-z0-9])multi(?![a-z0-9])(?![ ._-]?sub)")

    private fun language(all: String, up: String, t: Set<String>, others: List<String>, french: Boolean): AudioLanguage? {
        // « Multi-Sub », « Multi Subs » : des sous-titres en plusieurs langues, pas des pistes (groupes d'animés).
        val multi = rxMultiAudio.containsMatchIn(all) || "MULTI AUDIO" in up || t.any { it in setOf("MULTIAUDIO", "MULTILANG", "MULTILANGUAGE") }
        // « DUAL » = langue locale + anglais (groupes turcs, latino-américains…) : MULTI seulement avec le français
        val dual = rxDual.containsMatchIn(all)
        return when {
            french && t.any { it in VOSTFR_TAGS } -> AudioLanguage.MULTI
            t.any { it in VOSTFR_TAGS } -> AudioLanguage.VOSTFR
            (multi || dual) && french -> AudioLanguage.MULTI
            // « FRENCH.ENG » : deux pistes, dont le français
            french && others.any { it != "FR" } -> AudioLanguage.MULTI
            french -> AudioLanguage.VF
            // « MULTI » avec des langues annoncées mais sans le français (🇬🇧 / 🇮🇳) : pas une version française
            multi && others.isNotEmpty() -> null
            multi -> AudioLanguage.MULTI
            "VO" in t -> AudioLanguage.VO
            else -> null
        }
    }

    /**
     * Langues autres que le français : mots d'étiquette (« ITA », « GERMAN », « Latino ») lus seulement après l'année
     * ou le numéro d'épisode (« The.Italian.Job.2003 » n'est pas italien), et drapeaux (« 🇮🇹 / 🇬🇧 ») partout.
     */
    private fun audio(all: String): List<String> {
        val out = LinkedHashSet<String>()
        all.lineSequence().forEach { line ->
            val tags = releaseMarker.find(line)?.let { line.substring(it.range.last + 1) } ?: line
            tags.split(tokenSep).forEach { w -> LANG_WORDS[w.uppercase()]?.let(out::add) }
        }
        flags(all).forEach { c -> FLAG_LANG[c]?.takeIf { it != "FR" }?.let(out::add) }
        return out.toList()
    }

    /** Drapeaux emoji (deux indicateurs régionaux) : « 🇮🇹 » → « IT ». */
    fun flags(text: String): List<String> {
        val cps = text.codePoints().toArray()
        val out = mutableListOf<String>()
        var i = 0
        while (i < cps.size - 1) {
            val a = cps[i]
            val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                out += "" + ('A' + (a - 0x1F1E6)) + ('A' + (b - 0x1F1E6))
                i += 2
            } else i++
        }
        return out
    }

    private val FLAG_LANG = mapOf(
        "FR" to "FR", "GB" to "EN", "US" to "EN", "IT" to "IT", "DE" to "DE", "ES" to "ES", "MX" to "ES", "PT" to "PT", "BR" to "PT",
        "RU" to "RU", "JP" to "JA", "KR" to "KO", "CN" to "ZH", "TW" to "ZH", "HK" to "ZH", "SA" to "AR", "AE" to "AR", "EG" to "AR",
        "IN" to "HI", "TR" to "TR", "PL" to "PL", "NL" to "NL", "SE" to "SV", "DK" to "DA", "NO" to "NO", "FI" to "FI", "UA" to "UK",
        "IL" to "HE", "CZ" to "CS", "GR" to "EL", "TH" to "TH", "VN" to "VI", "HU" to "HU", "RO" to "RO", "IR" to "FA", "ID" to "ID",
    )

    /** Mots d'étiquette sans ambiguïté (pas « FIN », « DAN », « IT », qui sont aussi des mots de titre). */
    private val LANG_WORDS = mapOf(
        "POL" to "PL", "CES" to "CS", "ELL" to "EL", "GRE" to "EL", "HUN" to "HU", "RON" to "RO", "RUM" to "RO",
        "THA" to "TH", "VIE" to "VI", "FAS" to "FA", "PER" to "FA", "IND" to "ID", "TUR" to "TR",
        "ZHO" to "ZH", "CHI" to "ZH",
        "ENG" to "EN", "ENGLISH" to "EN", "ITA" to "IT", "ITALIAN" to "IT", "GER" to "DE", "DEU" to "DE", "GERMAN" to "DE",
        "SPA" to "ES", "ESP" to "ES", "SPANISH" to "ES", "CASTELLANO" to "ES", "LATINO" to "ES", "POR" to "PT", "PORTUGUESE" to "PT",
        "RUS" to "RU", "RUSSIAN" to "RU", "JPN" to "JA", "JAPANESE" to "JA", "KOR" to "KO", "KOREAN" to "KO", "CHINESE" to "ZH",
        "MANDARIN" to "ZH", "CANTONESE" to "ZH", "ARA" to "AR", "ARABIC" to "AR", "HIN" to "HI", "HINDI" to "HI", "TURKISH" to "TR",
        "POLISH" to "PL", "DUT" to "NL", "NLD" to "NL", "DUTCH" to "NL", "SWE" to "SV", "SWEDISH" to "SV", "DANISH" to "DA",
        "NORWEGIAN" to "NO", "FINNISH" to "FI", "UKR" to "UK", "UKRAINIAN" to "UK", "HEB" to "HE", "HEBREW" to "HE", "CZE" to "CS",
        "CZECH" to "CS", "GREEK" to "EL", "THAI" to "TH", "VIETNAMESE" to "VI", "HUNGARIAN" to "HU", "ROMANIAN" to "RO", "PERSIAN" to "FA",
    )

    /** « 💾 18.3 GB », « 4.7 Go », « 700 MB ». */
    fun size(text: String): Long? {
        val m = rxSize.find(text) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].uppercase()) { "TB" -> 1e12; "GB", "GO", "GIB" -> 1e9; else -> 1e6 }
        return (v * mult).toLong()
    }

    private fun cached(all: String, up: String): Boolean? = when {
        "[RD+]" in up || "RD+" in up || "⚡" in all || "[RD⚡]" in up || rxInstantLabel.containsMatchIn(all) -> true
        "[RD DOWNLOAD]" in up || "RD DOWNLOAD" in up || "⏳" in all -> false
        else -> null
    }

    /** S02E05, 2x05. */
    fun episodeTag(text: String): Pair<Int, Int>? {
        rxSxE.find(text)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        rxNxN.find(text)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        return null
    }

    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "m4v", "ts", "m2ts", "mov", "webm")

    fun isVideo(path: String) = path.substringAfterLast('.', "").lowercase() in VIDEO_EXT && !rxSample.containsMatchIn(path)

    /** Under this size, a video beside a much larger one is a sample or a teaser. */
    private const val SAMPLE_MAX_BYTES = 50L * 1024 * 1024

    /**
     * A sample without the word in its name: small in absolute terms and next to a video at least ten times larger. A
     * short or very compressed episode alone, or among others of its size, is never one.
     */
    fun sampleBySize(bytes: Long, largest: Long) = bytes in 1 until SAMPLE_MAX_BYTES && bytes < largest / 10

    /**
     * Fichier à lire dans un torrent : l'épisode demandé dans un pack de saison, sinon la plus grosse vidéo.
     * Un « extra » (sample, bonus) n'est jamais choisi.
     */
    fun pickFile(files: List<RdFile>, season: Int?, episode: Int?, absolute: Int? = null, movieTitles: List<String> = emptyList(), movieYear: Int? = null): RdFile? {
        val named = files.filter { isVideo(it.path) && !rxExtras.containsMatchIn(it.path) }
        val largest = named.maxOfOrNull { it.bytes } ?: 0L
        val videos = named.filterNot { sampleBySize(it.bytes, largest) }
        if (season != null && episode != null && absolute != null) {
            // Anime packs number from the first episode: "One Piece - 062" is S02E01. Never a file named for another one.
            return videos.filter { DebridStreams.episodeMatches(it.name, season, episode, absolute) == true }.maxByOrNull { it.bytes }
                ?: videos.singleOrNull()?.takeIf { DebridStreams.episodeMatches(it.name, season, episode, absolute) == null }
        }
        if (season != null && episode != null) {
            return videos.filter { f -> episodeTag(f.name)?.let { it == season to episode } == true }.maxByOrNull { it.bytes }
                // One video with no episode in its name may be it. One named for another episode is not.
                ?: videos.singleOrNull()?.takeIf { episodeTag(it.name) == null }
        }
        if (movieTitles.isNotEmpty() || movieYear != null) {
            val compatible = videos.filterNot { DebridStreams.wrongMovie(it.name, movieTitles, movieYear) }
            return compatible.filter { DebridStreams.movieTitleMatches(it.name, movieTitles) }.maxByOrNull { it.bytes }
                ?: compatible.singleOrNull()?.takeIf { videos.size == 1 }
        }
        return videos.maxByOrNull { it.bytes }
    }
}
