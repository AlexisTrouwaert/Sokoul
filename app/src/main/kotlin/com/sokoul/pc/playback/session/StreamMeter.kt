package com.sokoul.pc.playback.session
import com.sokoul.desktop.player.StreamSample

enum class StreamHealth { Measuring, Good, Weak, Bad }

/** What the player knows about the stream it reads, from the measures of the last seconds. */
data class StreamReading(
    val sample: StreamSample? = null,
    /** Kilobits per second, oldest first. */
    val history: List<Int> = emptyList(),
    val health: StreamHealth = StreamHealth.Measuring,
    val ticks: List<StreamMeter.Tick> = emptyList(),
) {
    /** The stream arrives in bursts: what is read in one second says little, a few seconds together do. */
    val averageKilobits: Int? get() = history.takeLast(AVERAGE_OVER).takeIf { it.isNotEmpty() }?.average()?.toInt()

    private companion object {
        const val AVERAGE_OVER = 8
    }
}

/** Turns the measures of the engine into a judgment. Pure and tested. */
object StreamMeter {
    data class Tick(val displayed: Int, val lost: Int, val buffering: Boolean)

    private const val HISTORY = 60
    private const val WINDOW = 10
    private const val MIN_TICKS = 3
    private const val FROZEN_TICKS = 5
    private const val WEAK_LOST = 0.05f
    private const val BAD_LOST = 0.20f
    private const val WEAK_BUFFERING = 3
    private const val BAD_BUFFERING = 6

    fun next(previous: StreamReading, sample: StreamSample, buffering: Boolean): StreamReading {
        val same = previous.sample?.session == sample.session
        val before = previous.sample?.takeIf { same }
        val tick = Tick(
            (sample.displayed - (before?.displayed ?: sample.displayed)).coerceAtLeast(0),
            (sample.lost - (before?.lost ?: sample.lost)).coerceAtLeast(0),
            buffering,
        )
        val ticks = ((if (same) previous.ticks else emptyList()) + tick).takeLast(WINDOW)
        val history = ((if (same) previous.history else emptyList()) + listOfNotNull(sample.kilobitsPerSecond)).takeLast(HISTORY)
        return StreamReading(sample, history, health(ticks), ticks)
    }

    private fun health(ticks: List<Tick>): StreamHealth {
        if (ticks.size < MIN_TICKS) return StreamHealth.Measuring
        val displayed = ticks.sumOf { it.displayed }
        val lost = ticks.sumOf { it.lost }
        val lostShare = if (displayed + lost == 0) 0f else lost.toFloat() / (displayed + lost)
        val buffering = ticks.count { it.buffering }
        val frozen = ticks.size >= FROZEN_TICKS && ticks.takeLast(FROZEN_TICKS).all { it.displayed == 0 }
        return when {
            frozen || lostShare >= BAD_LOST || buffering >= BAD_BUFFERING -> StreamHealth.Bad
            lostShare >= WEAK_LOST || buffering >= WEAK_BUFFERING -> StreamHealth.Weak
            else -> StreamHealth.Good
        }
    }
}
