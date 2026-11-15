package com.sokoul.desktop.player

/**
 * Piste audio à l'ouverture : libVLC prend la première piste quand aucune n'est marquée dans la langue voulue (vu sur
 * une version « MULTI » turque et anglaise : le film démarrait en turc). On préfère la langue des Réglages (français
 * par défaut), sinon l'anglais (VO la plus répandue), sinon on laisse le choix de libVLC. Même règle pour les
 * sous-titres par défaut, sans repli.
 */
object AudioChoice {
    /** ISO codes and localized names cover tracks beyond the settings' short language list. */
    private val localeNames by lazy {
        buildMap<String, String> {
            java.util.Locale.getISOLanguages().forEach { code ->
                val locale = java.util.Locale.forLanguageTag(code)
                put(code, code)
                runCatching { locale.isO3Language }.getOrNull()?.let { put(it, code) }
                listOf(java.util.Locale.ENGLISH, java.util.Locale.FRENCH, java.util.Locale.getDefault(), locale).forEach {
                    put(locale.getDisplayLanguage(it).lowercase(java.util.Locale.ROOT), code)
                }
            }
        }
    }
    /** Mots qui désignent chaque langue des Réglages dans les noms de pistes (libVLC, étiquettes des fichiers). */
    private val words = mapOf(
        "fr" to "fr|fre|fra|french|fran[cç]ais|vf|vff|vfq|vfi|truefrench",
        "en" to "en|eng|english|anglais",
        "ar" to "ar|ara|arabic|arabe",
        "es" to "es|spa|spanish|espa[nñ]ol|espagnol|castellano|latino",
        "de" to "de|ger|deu|german|deutsch|allemand",
        "it" to "it|ita|italian|italiano|italien",
        "pt" to "pt|por|portuguese|portugu[eê]s|portugais",
        "tr" to "tr|tur|turkish|t[uü]rk[cç]e|turc",
        "nl" to "nl|dut|nld|dutch|nederlands|n[eé]erlandais",
        "ja" to "ja|jpn|jap|japanese|japonais",
        "ko" to "ko|kor|korean|cor[eé]en",
        "zh" to "zh|zho|chi|chinese|chinois|mandarin|cantonese",
        "ru" to "ru|rus|russian|russe",
        "hi" to "hi|hin|hindi",
    ).mapValues { (_, w) -> Regex("""\b($w)\b""", RegexOption.IGNORE_CASE) }

    private fun matches(lang: String, label: String) = words[lang]?.containsMatchIn(label) == true || languageOf(label) == lang

    /** Langue des Réglages reconnue dans le nom d'une piste (« [French] » → « fr »), ou null. */
    fun languageOf(label: String): String? = words.entries.firstOrNull { it.value.containsMatchIn(label) }?.key
        ?: label.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}]+"))
            .firstNotNullOfOrNull { localeNames[it] }

    /** Une piste française parmi [tracks] (id, description). */
    fun hasFrench(tracks: List<Pair<Int, String>>): Boolean = hasLanguage(tracks, "fr")

    fun hasLanguage(tracks: List<Pair<Int, String>>, lang: String): Boolean = tracks.any { matches(lang, it.second) }

    /** Identifiant de la piste audio à prendre parmi [tracks] (id, description), ou null pour garder celle de libVLC. */
    fun pick(tracks: List<Pair<Int, String>>, preferred: String = "fr"): Int? =
        tracks.firstOrNull { matches(preferred, it.second) }?.first
            ?: tracks.firstOrNull { matches("en", it.second) }?.first

    /** Sous-titres par défaut : la piste de la langue choisie, sinon aucune (null = laisser tel quel). */
    fun pickSubtitle(tracks: List<Pair<Int, String>>, lang: String): Int? =
        if (lang.isBlank()) null else tracks.firstOrNull { it.first >= 0 && matches(lang, it.second) }?.first
}
