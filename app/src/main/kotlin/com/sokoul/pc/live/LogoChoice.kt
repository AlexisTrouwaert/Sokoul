package com.sokoul.pc.live

/**
 * How a channel's logo is shown, from its pixels: the box its visible pixels fill (transparent margins cut away), and
 * whether it needs a light plate to stand out on the app's dark background. Pure: tested on synthetic images.
 */
object LogoChoice {
    enum class Plate { None, Light }

    data class Look(val left: Int, val top: Int, val width: Int, val height: Int, val plate: Plate)

    /** A pixel counts once it is more than this opaque (0..255): anti-aliasing fringes do not widen the box. */
    private const val VISIBLE_ALPHA = 8
    /** Relative luminance of the app's background (#2C2C2E), as WCAG defines it. */
    private const val BACKGROUND_LUMINANCE = 0.025
    /** Under this contrast with the background (WCAG ratio), a logo vanishes into it. Saturated red stays above. */
    const val MIN_CONTRAST = 1.8

    private fun linear(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** [argb] holds [width]×[height] pixels, row by row, as 0xAARRGGBB. */
    fun look(width: Int, height: Int, argb: IntArray): Look {
        require(argb.size >= width * height) { "pixels missing" }
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var lum = 0.0
        var weight = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val a = p ushr 24 and 0xFF
                if (a <= VISIBLE_ALPHA) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                val w = a / 255.0
                lum += w * (0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b))
                weight += w
            }
        }
        if (maxX < 0) return Look(0, 0, width, height, Plate.None)
        val mean = if (weight > 0) lum / weight else 1.0
        val contrast = (maxOf(mean, BACKGROUND_LUMINANCE) + 0.05) / (minOf(mean, BACKGROUND_LUMINANCE) + 0.05)
        return Look(minX, minY, maxX - minX + 1, maxY - minY + 1, if (contrast < MIN_CONTRAST) Plate.Light else Plate.None)
    }

    /** The sharpest of several logos of one channel: the widest; on a tie, the first (playlist, then iptv-org). */
    fun sharpest(widths: List<Int?>): Int? =
        widths.withIndex().filter { it.value != null }.maxWithOrNull(compareBy<IndexedValue<Int?>> { it.value!! }.thenByDescending { it.index })?.index

    /** Up to three letters to stand in for a missing logo: "TF1" stays, "France 2" gives "F2". */
    fun initials(name: String): String {
        val words = name.replace(Regex("^[A-Z]{2,3}\\s*\\|\\s*"), "").split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.isNotEmpty() }
            .filterNot { it.uppercase() in setOf("HD", "FHD", "UHD", "4K", "SD") }
        if (words.isEmpty()) return "?"
        if (words.size == 1) return words[0].take(3).uppercase()
        return words.take(3).joinToString("") { w -> if (w.all(Char::isDigit)) w else w.take(1) }.uppercase()
    }
}
