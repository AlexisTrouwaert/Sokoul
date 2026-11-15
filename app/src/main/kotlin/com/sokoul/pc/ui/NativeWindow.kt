package com.sokoul.pc.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * The window's real rectangle, in screen pixels. Java's own bounds cannot give it back after full screen on a scaled
 * display: it restores its numbers as if they were pixels, and at 150 % the window comes back two thirds of its size.
 */
object NativeWindow {
    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int)

    fun rect(window: Window): Rect? = runCatching {
        val r = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(handle(window), r)) return null
        Rect(r.left, r.top, r.right - r.left, r.bottom - r.top)
    }.getOrNull()

    fun restore(window: Window, rect: Rect): Boolean = runCatching {
        User32.INSTANCE.SetWindowPos(handle(window), null, rect.left, rect.top, rect.width, rect.height, WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE)
    }.getOrDefault(false)

    private fun handle(window: Window) = WinDef.HWND(Pointer.createConstant(Native.getWindowID(window)))
}
