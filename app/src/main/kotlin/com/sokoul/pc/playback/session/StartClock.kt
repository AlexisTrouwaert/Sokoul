package com.sokoul.pc.playback.session
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * When each step of the last start ended, from the press on Play: searches, identity, each try, first image. Read by the
 * tools to measure how long a start takes on the TV. Holds no address nor key, only names of steps.
 */
object StartClock {
    private val steps = ConcurrentLinkedQueue<String>()
    @Volatile private var startedAt = System.currentTimeMillis()

    fun start(what: String) {
        steps.clear()
        startedAt = System.currentTimeMillis()
        note(what)
    }

    fun note(step: String) {
        steps += "+" + (System.currentTimeMillis() - startedAt) + " ms " + step
        while (steps.size > MAX) steps.poll()
    }

    fun steps(): List<String> = steps.toList()

    private const val MAX = 60
}
