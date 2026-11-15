package com.sokoul.pc.dev

import com.sokoul.desktop.data.AppDirs
import com.sokoul.pc.data.PcLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import kotlin.concurrent.thread

/**
 * Live checks without taking over the user's keyboard: when SOKOUL_DEBUG is set, commands written to debug-keys.txt in
 * the data folder go straight to this window. One command per line: UP DOWN LEFT RIGHT ENTER ESC SPACE, a single letter,
 * PINCH_IN or PINCH_OUT (Ctrl + wheel, which is what a touchpad pinch sends), CLICK:x:y or HOVER:x:y (1920-wide coordinates), or WAIT:millis.
 */
object DebugDriver {
    private const val HOVER_STEPS = 8
    private const val HOVER_STEP_PX = 6
    private const val HOVER_GAP_MS = 15L
    private const val HOVER_AWAY = 6

    private val file by lazy { File(AppDirs.data, "debug-keys.txt") }

    fun start(window: Window) {
        if (System.getenv("SOKOUL_DEBUG").isNullOrBlank()) return
        thread(isDaemon = true, name = "sokoul-debug-driver") {
            while (true) {
                Thread.sleep(POLL_MS)
                val lines = runCatching { if (file.isFile) file.readLines().also { file.delete() } else emptyList() }.getOrDefault(emptyList())
                lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { run(window, it) }
            }
        }
    }

    /** The component Compose listens on: the one with a key listener, deepest first. */
    private fun target(component: Component): Component? {
        if (component is Container) component.components.forEach { c -> target(c)?.let { return it } }
        return component.takeIf { it.keyListeners.isNotEmpty() }
    }

    /** The app's tools, for the live checks in tools/verify.mjs: `TOOL id name {json}`, answered in `debug-results/id.json`. */
    @Volatile var registry: (() -> com.sokoul.pc.control.ToolRegistry?)? = null
    private val toolScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Swing)

    private fun tool(command: String) {
        val (id, name, args) = command.split(' ', limit = 4).drop(1).let { Triple(it[0], it[1], it.getOrElse(2) { "{}" }) }
        val dir = File(AppDirs.data, "debug-results").apply { mkdirs() }
        toolScope.launch {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(args) as kotlinx.serialization.json.JsonObject
            val answer = registry?.invoke()?.call(name, json, com.sokoul.pc.control.ToolPolicy.Everything)
            val text = kotlinx.serialization.json.buildJsonObject {
                when (answer) {
                    is com.sokoul.pc.control.ToolResult.Done -> put("value", answer.value)
                    is com.sokoul.pc.control.ToolResult.Failed -> put("error", kotlinx.serialization.json.JsonPrimitive(answer.message))
                    is com.sokoul.pc.control.ToolResult.Refused -> put("error", kotlinx.serialization.json.JsonPrimitive(answer.reason))
                    null -> put("error", kotlinx.serialization.json.JsonPrimitive("not ready"))
                }
            }.toString()
            File(dir, "$id.tmp").writeText(text)
            File(dir, "$id.tmp").renameTo(File(dir, "$id.json"))
        }
    }

    private fun run(window: Window, command: String) {
        if (command.startsWith("TOOL ")) {
            runCatching { tool(command) }
            return
        }
        if (command.startsWith("WAIT:")) {
            Thread.sleep(command.removePrefix("WAIT:").toLongOrNull() ?: 0)
            return
        }
        PcLog.event("driver $command")
        EventQueue.invokeLater {
            val c = target(window) ?: window
            when {
                command.startsWith("CLICK:") -> {
                    val (x, y) = command.removePrefix("CLICK:").split(':').map { it.toInt() }
                    click(c, x * c.width / 1920, y * c.width / 1920)
                }
                command.startsWith("HOVER:") -> {
                    val (x, y) = command.removePrefix("HOVER:").split(':').map { it.toInt() }
                    hover(c, x * c.width / 1920, y * c.width / 1920)
                }
                command.startsWith("WHEEL:") -> repeat(command.removePrefix("WHEEL:").toInt()) {
                    c.dispatchEvent(MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, c.width / 2, c.height / 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 3))
                }
                command == "PINCH_IN" -> wheel(c, -1)
                command == "PINCH_OUT" -> wheel(c, 1)
                else -> key(c, command)
            }
        }
        Thread.sleep(KEY_GAP_MS)
    }

    private fun key(c: Component, name: String) {
        val code = when (name) {
            "UP" -> KeyEvent.VK_UP
            "DOWN" -> KeyEvent.VK_DOWN
            "LEFT" -> KeyEvent.VK_LEFT
            "RIGHT" -> KeyEvent.VK_RIGHT
            "ENTER" -> KeyEvent.VK_ENTER
            "ESC" -> KeyEvent.VK_ESCAPE
            "SPACE" -> KeyEvent.VK_SPACE
            "F11" -> KeyEvent.VK_F11
            "HOME" -> KeyEvent.VK_HOME
            "END" -> KeyEvent.VK_END
            "BACKSPACE" -> KeyEvent.VK_BACK_SPACE
            "PLUS" -> KeyEvent.VK_ADD
            "MINUS" -> KeyEvent.VK_SUBTRACT
            else -> KeyEvent.getExtendedKeyCodeForChar(name.first().code)
        }
        val char = if (name.length == 1) name.first() else KeyEvent.CHAR_UNDEFINED
        val now = System.currentTimeMillis()
        c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_PRESSED, now, 0, code, char))
        if (name.length == 1) c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, char))
        c.dispatchEvent(KeyEvent(c, KeyEvent.KEY_RELEASED, now + 30, 0, code, char))
    }

    private fun click(c: Component, x: Int, y: Int) {
        val now = System.currentTimeMillis()
        fun send(id: Int, button: Int, mask: Int, count: Int) = c.dispatchEvent(MouseEvent(c, id, now, mask, x, y, count, false, button))
        send(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON, 0, 0)
        send(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK, 1)
        send(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, 0, 1)
    }

    /** A pointer that walks in from the left, as a hand does: the page sees it move, then arrive. */
    private fun hover(c: Component, x: Int, y: Int) {
        for (step in -HOVER_STEPS..0) {
            c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x + step * HOVER_STEP_PX, y, 0, false, MouseEvent.NOBUTTON))
            Thread.sleep(HOVER_GAP_MS)
        }
        // Compose gives focus on a press: press here, then let go far away so nothing is clicked.
        val now = System.currentTimeMillis()
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1))
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_DRAGGED, now, InputEvent.BUTTON1_DOWN_MASK, HOVER_AWAY, HOVER_AWAY, 0, false, MouseEvent.BUTTON1))
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, 0, HOVER_AWAY, HOVER_AWAY, 1, false, MouseEvent.BUTTON1))
    }

    private fun wheel(c: Component, direction: Int) {
        val x = c.width / 2
        val y = c.height / 2
        repeat(PINCH_STEPS) {
            c.dispatchEvent(
                MouseWheelEvent(
                    c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), InputEvent.CTRL_DOWN_MASK,
                    x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, direction,
                ),
            )
        }
    }

    private const val POLL_MS = 100L
    private const val KEY_GAP_MS = 350L
    private const val PINCH_STEPS = 6
}
