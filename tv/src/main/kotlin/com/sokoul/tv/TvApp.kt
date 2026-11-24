package com.sokoul.tv

import android.app.Application
import android.content.Context
import java.io.File

class TvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        context = this
        System.setProperty("sokoul.data", filesDir.path)
        System.setProperty("java.io.tmpdir", cacheDir.path)
        // Native SQLite does not read Java's java.io.tmpdir. Its Unix fallback /tmp is not writable on Android.
        android.system.Os.setenv("SQLITE_TMPDIR", cacheDir.absolutePath, true)
        System.setProperty("user.home", filesDir.path)
        adoptPushedData()
        // SQLite's native library ships in the APK and is loaded from the app's own native folder: unpacking it into the
        // app's data and running it from there is forbidden to apps targeting API 29 and later.
        System.setProperty("org.sqlite.lib.path", applicationInfo.nativeLibraryDir)
        System.setProperty("org.sqlite.lib.name", "libsqlitejdbc.so")
        Class.forName("org.sqlite.JDBC")
        keepAlive()
        // Memory goes back when the system asks: the images of the pages left are decoded again from the disk cache.
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                when {
                    // The app is no longer on screen: its images are of no use, the other apps need the room.
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> com.sokoul.desktop.ui.ImageLoader.trim(0f)
                    // On screen, but the device is short: half of the cache goes.
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> com.sokoul.desktop.ui.ImageLoader.trim(0.5f)
                }
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() { com.sokoul.desktop.ui.ImageLoader.trim(0f) }
        })
    }

    /**
     * An error in one screen or one service must not close the app (on Windows it is only logged). Errors are written to the
     * log, and the main thread goes on handling the remote.
     */
    private fun keepAlive() {
        Thread.setDefaultUncaughtExceptionHandler { _, e -> android.util.Log.e("SokoulCrash", com.sokoul.pc.data.PcLog.let { e.stackTraceToString().take(1500) }) }
        android.os.Handler(mainLooper).post {
            while (true) {
                try {
                    android.os.Looper.loop()
                } catch (e: Throwable) {
                    android.util.Log.e("SokoulCrash", e.stackTraceToString().take(1500))
                }
            }
        }
    }

    /** The PC's data, pushed once with adb: keys, list, progress. Moved in before the database opens, then gone from shared storage. */
    private fun adoptPushedData() {
        val pushed = File(getExternalFilesDir(null) ?: return, "import.db")
        if (!pushed.isFile) return
        val target = File(filesDir, "sokoul.db")
        // A leftover import must never overwrite an existing account on an ordinary restart.
        if (target.exists()) return
        pushed.copyTo(target, overwrite = false)
        pushed.delete()
    }

    companion object {
        lateinit var context: Context
    }
}
