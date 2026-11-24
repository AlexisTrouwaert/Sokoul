package com.sokoul.pc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The kind of screen the shared interface draws on. The PC and the TV are wide; a phone may also stand upright. */
enum class FormFactor { Desktop, Tv, PhoneLandscape, PhonePortrait }

/**
 * The current form factor, set by the platform (the Android activity on each configuration change). A state, so metrics
 * read through it follow a rotation at once.
 */
object Form {
    var current by mutableStateOf(FormFactor.Desktop)

    /**
     * A touch tablet (not a television, smallest width of 600 dp or more). Its screens still draw as [FormFactor.Tv]
     * until each one is adapted; the first launch already has its own tablet layout.
     */
    var tablet by mutableStateOf(false)

    /**
     * A tablet held upright. The browse pages then lay out as the Apple TV and Disney+ apps do on a tablet: a square
     * hero, then rows of larger cards in mixed formats, at their own scale ([UPRIGHT_WIDTH] dp across).
     */
    var tabletPortrait by mutableStateOf(false)

    /** A touch screen held in the hand: no remote, no hidden bars, larger text. */
    val isPhone: Boolean get() = current == FormFactor.PhoneLandscape || current == FormFactor.PhonePortrait

    /** A phone held upright: one column, tabs at the bottom. */
    val isPortrait: Boolean get() = current == FormFactor.PhonePortrait

    /** Picks a size for the TV/PC, a phone held sideways and a phone held upright. */
    fun <T> pick(wide: T, phone: T = wide, portrait: T = phone): T = when (current) {
        FormFactor.PhonePortrait -> portrait
        FormFactor.PhoneLandscape -> phone
        else -> wide
    }
}
