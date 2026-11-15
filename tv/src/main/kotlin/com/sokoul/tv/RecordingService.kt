package com.sokoul.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.sokoul.desktop.data.AppDirs
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.DesktopRecorder
import com.sokoul.desktop.data.DesktopSync
import com.sokoul.desktop.data.Settings
import com.sokoul.pc.Platform
import com.sokoul.pc.data.L
import java.io.File
import kotlinx.coroutines.*

/** Keeps user-started captures alive when the activity is covered or the screen turns off. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observedCapture = false
    private val createdAt = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, L("Recordings", "Enregistrements").text(), NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification(L("Preparing recording", "Préparation de l’enregistrement").text()))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sokoul:recording").apply { acquire(12 * 3600_000L) }
        scope.launch {
            if (DesktopRecorder.runtime == null) {
                val db = DesktopDb(File(AppDirs.data, "sokoul.db"))
                val recorder = DesktopRecorder(db, DesktopSync(db), processScope)
                recorder.configure(keepAlive = Platform::recordingKeepAlive)
                recorder.start {
                    val selected = db.setting(Settings.ACTIVE_PLAYLIST)?.toLongOrNull()
                    db.playlists().firstOrNull { it.id == selected } ?: db.playlists().firstOrNull()
                }
            }
            while (isActive) {
                val active = DesktopRecorder.runtime?.active?.value
                if (active != null) {
                    observedCapture = true
                    notifications.notify(ID, notification("${active.title} · ${active.bytes / (1024 * 1024)} MB"))
                } else if (observedCapture || System.currentTimeMillis() - createdAt > 10_000) {
                    stopSelf()
                    break
                }
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            DesktopRecorder.runtime?.let { recorder -> recorder.active.value?.let(recorder::stop) }
            stopSelf()
        }
        return START_STICKY
    }

    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 701, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 702, Intent(this, RecordingService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Sokoul").setContentText(detail).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, L("Stop and save", "Arrêter et sauvegarder").text(), stop).build()).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) {
        DesktopRecorder.runtime?.let { recorder -> recorder.active.value?.let(recorder::stop) }
        stopSelf()
    }
    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        // A root-owned recorder continues independently of this notification service.
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "sokoul-recording"
        private const val ID = 701
        private const val STOP = "com.sokoul.tv.STOP_RECORDING"
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
