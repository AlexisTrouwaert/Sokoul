package com.sokoul.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sokoul.desktop.data.AppDirs
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.RecordingStatus
import java.io.File
import kotlinx.coroutines.*

/** Exact alarms are used only when Android grants them; the fallback remains explicitly inexact. */
class RecordingWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    DesktopDb(File(AppDirs.data, "sokoul.db")).use { db ->
                        val now = System.currentTimeMillis()
                        val next = db.playlists().flatMap { db.recordings(it.id) }
                            .filter { it.status in setOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING) && it.endAt > now }
                            .minOfOrNull { maxOf(it.startAt, now + 1000) }
                        schedule(context, next)
                    }
                } finally { pending.finish() }
            }
        } else {
            runCatching { context.startForegroundService(Intent(context, RecordingService::class.java)) }
        }
    }

    companion object {
        fun schedule(context: Context, atMillis: Long?) {
            val manager = context.getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(context, 703, Intent(context, RecordingWakeReceiver::class.java).setAction("com.sokoul.tv.RECORDING_WAKE"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.cancel(pending)
            if (atMillis == null) return
            val at = maxOf(atMillis, System.currentTimeMillis() + 500)
            if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }
}
