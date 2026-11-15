package com.sokoul.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat

/**
 * Compose treats accessibility as on as soon as any accessibility service is enabled, even one that never reads the
 * screen, such as the TV's volume-key service (`com.sokoul.tvvolume`, it only filters keys). It then rebuilt the
 * semantics tree of the whole page at every frame: up to 32 ms a frame, 1.3 s in 9 s of scrolling down the home page
 * on the Google TV Streamer (perfetto, 2026-11-10).
 *
 * When no enabled service gives spoken, braille or audible feedback and touch exploration is off, the list of services
 * Compose keeps is emptied, so it skips that work. A screen reader (TalkBack) keeps everything as before. Applied again
 * whenever the system's accessibility state changes, since Compose refills the list then.
 */
object A11yGate {
    private const val READER_FEEDBACK = AccessibilityServiceInfo.FEEDBACK_SPOKEN or
        AccessibilityServiceInfo.FEEDBACK_BRAILLE or AccessibilityServiceInfo.FEEDBACK_AUDIBLE

    fun install(activity: Activity) {
        val manager = activity.getSystemService(AccessibilityManager::class.java) ?: return
        val apply = { activity.window.decorView.post { apply(activity, manager) } }
        manager.addAccessibilityStateChangeListener { apply() }
        manager.addTouchExplorationStateChangeListener { apply() }
        apply()
    }

    private fun readerOn(manager: AccessibilityManager): Boolean =
        manager.isTouchExplorationEnabled ||
            manager.getEnabledAccessibilityServiceList(READER_FEEDBACK).isNotEmpty()

    private fun apply(activity: Activity, manager: AccessibilityManager) {
        if (!manager.isEnabled || readerOn(manager)) return
        val view = composeView(activity.window.decorView) ?: return
        runCatching {
            val delegate = ViewCompat.getAccessibilityDelegate(view) ?: return
            val field = delegate.javaClass.getDeclaredField("enabledServices").apply { isAccessible = true }
            field.set(delegate, emptyList<AccessibilityServiceInfo>())
        }.onFailure { com.sokoul.pc.data.PcLog.event("a11y gate not applied: ${it.javaClass.simpleName}") }
    }

    /** Compose's own view (AndroidComposeView), found by class name: it is internal to Compose. */
    private fun composeView(root: View): View? {
        if (root.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return root
        if (root is ViewGroup) for (i in 0 until root.childCount) composeView(root.getChildAt(i))?.let { return it }
        return null
    }
}
