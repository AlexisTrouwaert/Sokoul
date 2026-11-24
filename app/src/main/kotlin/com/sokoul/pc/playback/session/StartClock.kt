package com.sokoul.pc.playback.session
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * When each step of the last start ended, from the press on Play: searches, identity, each try, first image. Read by the
 * tools to measure how long a start takes on the TV. Holds no address nor key, only names of steps.
 */
object StartClock {
    private val steps = ConcurrentLinkedQueue<String>()
    @Volatile private var startedAt = System.currentTimeMillis()

    /** [title]: what the viewer chose, for the start log kept in Settings (StartLog); null for a tool's own measure. */
    fun start(what: String, title: String? = null) {
        steps.clear()
        startedAt = System.currentTimeMillis()
        recording = title != null
        if (title != null) StartLog.begin(title, "+0 ms $what")
        note(what, log = false)
    }

    @Volatile private var recording = false

    fun note(step: String) = note(step, log = true)

    private fun note(step: String, log: Boolean) {
        val line = "+" + (System.currentTimeMillis() - startedAt) + " ms " + step
        steps += line
        if (log && recording) StartLog.add(line)
        com.sokoul.pc.Platform.startDiagnostic(line)
        while (steps.size > MAX) steps.poll()
    }

    fun steps(): List<String> = steps.toList()

    private const val MAX = 60
}
