package com.sokoul.pc.title

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** What the main button of a page offers, from what has been watched. */
enum class PlayKind { Watch, Resume, Rewatch, Next, New }

/** The episode that follows the last one watched, when there is one. */
data class NextEp(val season: Int, val episode: Int, val runtimeMin: Int?, val airDate: String?)

object HeroLogic {
    private const val NEW_DAYS = 14L

    /** Not started: watch. Stopped halfway: resume. Finished: the next episode, a new one when it just aired, else watch again. */
    fun playKind(isTv: Boolean, watched: Boolean, finished: Boolean, next: NextEp?, today: LocalDate): PlayKind = when {
        !watched -> PlayKind.Watch
        !finished -> PlayKind.Resume
        !isTv || next == null -> PlayKind.Rewatch
        isRecent(next.airDate, today) -> PlayKind.New
        else -> PlayKind.Next
    }

    /** Aired within the last two weeks, and not in the future. */
    fun isRecent(airDate: String?, today: LocalDate): Boolean {
        val d = try {
            LocalDate.parse(airDate ?: return false)
        } catch (_: DateTimeParseException) {
            return false
        }
        return !d.isAfter(today) && !d.isBefore(today.minusDays(NEW_DAYS))
    }

    /**
     * The colour of a picture, for a soft glow behind the text: the mean of its lively pixels, so a grey sky does not
     * win over a red coat. Null when the picture has nothing lively.
     */
    fun dominant(argb: IntArray): Color? {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        for (p in argb) {
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            val max = maxOf(pr, pg, pb)
            val min = minOf(pr, pg, pb)
            if (max < MIN_VALUE || max == 0) continue
            val saturation = (max - min).toDouble() / max
            val weight = saturation * saturation * (max / 255.0)
            r += pr * weight
            g += pg * weight
            b += pb * weight
            total += weight
        }
        if (total < MIN_WEIGHT) return null
        // Glow, not paint: lift it to a clear but never white colour.
        val mr = r / total
        val mg = g / total
        val mb = b / total
        val top = maxOf(mr, mg, mb)
        val lift = if (top < LIFT_TO) LIFT_TO / top else 1.0
        return Color((mr * lift).toInt().coerceAtMost(255), (mg * lift).toInt().coerceAtMost(255), (mb * lift).toInt().coerceAtMost(255))
    }

    private const val MIN_VALUE = 60
    private const val MIN_WEIGHT = 0.5
    private const val LIFT_TO = 190.0

    /** "en" gives "English" in English and "anglais" in French, in the language of the interface. */
    fun languageName(code: String?): String? = code?.takeIf { it.isNotBlank() }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        ?.takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase() }
}
