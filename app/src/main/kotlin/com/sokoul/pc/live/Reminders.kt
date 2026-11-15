package com.sokoul.pc.live

import androidx.compose.foundation.background
import com.sokoul.pc.ui.glassSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.pc.AppDeps
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.playback.session.LiveChannel
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.PillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sokoul.desktop.data.DesktopDb

data class Reminder(val channelId: String, val channel: String, val title: String, val start: Long)

/** Programmes to be told about. They survive a restart, and are forgotten once they have started for a while. */
class Reminders(private val db: DesktopDb) {
    var items by mutableStateOf(load())
        private set
    private val told = mutableSetOf<Reminder>()

    /** Upcoming items, deduplicated by channel and programme start so older stored data stays safe. */
    fun upcoming(now: Long = System.currentTimeMillis()): List<Reminder> = ReminderRules.upcoming(items, now)

    fun has(channelId: String, start: Long) = items.any { it.channelId == channelId && it.start == start }

    fun toggle(r: Reminder) {
        items = if (has(r.channelId, r.start)) items.filterNot { it.channelId == r.channelId && it.start == r.start } else items + r
        save()
    }

    /** The reminder to show now, once only. */
    fun due(now: Long): Reminder? {
        val fresh = items.filter { it.start > now - TOO_LATE_MS }
        if (fresh.size != items.size) {
            items = fresh
            save()
        }
        return ReminderRules.due(fresh, told, now)?.also { told += it }
    }

    fun dismiss(r: Reminder) {
        items = items - r
        save()
    }

    fun remove(r: Reminder) = dismiss(r)

    private fun load(): List<Reminder> = db.setting(SETTING)?.lines()?.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4) null else parts[3].toLongOrNull()?.let { Reminder(parts[0], parts[1], parts[2], it) }
    }.orEmpty()

    private fun save() = db.putSetting(SETTING, items.joinToString("\n") { "${it.channelId}\t${it.channel.clean()}\t${it.title.clean()}\t${it.start}" })

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ')

    private companion object {
        const val SETTING = "pc.live_reminders"
        const val TOO_LATE_MS = 5 * 60_000L
    }
}

object ReminderRules {
    const val BEFORE_MS = 60_000L

    fun due(items: List<Reminder>, told: Set<Reminder>, now: Long): Reminder? =
        items.filter { it !in told && it.start - now <= BEFORE_MS }.minByOrNull { it.start }

    fun upcoming(items: List<Reminder>, now: Long): List<Reminder> = items
        .asSequence()
        .filter { it.start > now }
        .groupBy { it.channelId to it.start }
        .values
        .map { duplicates -> duplicates.minBy { it.channel.length } }
        .sortedWith(compareBy<Reminder> { it.start }.thenBy { it.channel.lowercase() })
        .toList()
}

/** Tells about a programme that starts, on whatever page is open. */
@Composable
fun ReminderHost(deps: AppDeps, modifier: Modifier, onWatch: (LiveChannel) -> Unit) {
    var current by remember { mutableStateOf<Reminder?>(null) }
    // In the real app only: a check with no end keeps a test waiting for the interface to be idle.
    if (com.sokoul.pc.ui.FocusSource.trackRealMouse) LaunchedEffect(Unit) {
        while (true) {
            if (current == null) current = deps.reminders.due(System.currentTimeMillis())
            delay(CHECK_EVERY_MS)
        }
    }
    val r = current ?: return
    Row(
        modifier.padding(top = 120.dp).glassSurface(RoundedCornerShape(50), 40.dp).padding(horizontal = 28.dp, vertical = 12.dp).testTag("reminder-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Label(tr(Text.ReminderSoon, r.title, r.channel), Type.Meta, maxLines = 1)
        PillButton(tr(Text.Watch), {
            current = null
            deps.reminders.dismiss(r)
            kotlinx.coroutines.MainScope().launchWatch(deps, r, onWatch)
        }, Modifier.testTag("reminder-watch"))
        PillButton(tr(Text.Dismiss), {
            current = null
            deps.reminders.dismiss(r)
        }, Modifier.testTag("reminder-dismiss"))
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchWatch(deps: AppDeps, r: Reminder, onWatch: (LiveChannel) -> Unit) {
    launch {
        withContext(Dispatchers.IO) { deps.tv.channelById(r.channelId)?.let(deps.tv::toLive) }?.let(onWatch)
    }
}

private const val CHECK_EVERY_MS = 10_000L
