package com.sokoul.pc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

/** What differs between the Windows app and the TV app. */
object Platform {
    fun localExtractionBridge(http: okhttp3.OkHttpClient): com.sokoul.extract.HttpBridge? = null

    const val IS_TV = false
    @Volatile private var playerBackHandler: (() -> Unit)? = null
    private var lastPlayerBackAt = 0L

    /** The PC's window stays as it is: only a phone turns sideways for a video. */
    @Suppress("UNUSED_PARAMETER") fun playerFullscreen(on: Boolean) = Unit

    /** Keeps the display and the computer awake while a video plays; [on] false lets them sleep again. */
    fun keepAwake(on: Boolean) = KeepAwake.set(on)

    fun setPlayerBackHandler(handler: (() -> Unit)?) { playerBackHandler = handler }
    fun hasPlayerBackHandler(): Boolean = playerBackHandler != null
    @Synchronized fun dispatchPlayerBack(): Boolean {
        val handler = playerBackHandler ?: return false
        val now = System.currentTimeMillis()
        if (now - lastPlayerBackAt < 250) return true
        lastPlayerBackAt = now
        handler()
        return true
    }

    fun recordingStorages(): List<com.sokoul.desktop.data.RecordingStorage> {
        val folder = File(com.sokoul.desktop.data.AppDirs.data, "recordings").apply { mkdirs() }
        return listOf(com.sokoul.desktop.data.RecordingStorage(folder.absolutePath, com.sokoul.pc.data.L("Default folder", "Dossier par défaut").text(), folder))
    }

    fun pickRecordingDirectory(): File? {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "Sokoul"
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    @Suppress("UNUSED_PARAMETER") fun recordingKeepAlive(needed: Boolean) = Unit
    @Suppress("UNUSED_PARAMETER") fun recordingScheduleWake(atMillis: Long?) = Unit
    fun recordingSchedulingExact() = false
    fun requestRecordingExactAlarms() = Unit

    fun guideDiagnostic(message: String) {
        System.err.println("SokoulEPG $message")
    }

    suspend fun resolveTrailer(keys: List<String>): com.sokoul.pc.data.TrailerStream? = null

    fun deviceCaps() = com.sokoul.pc.playback.sources.DeviceCaps.Pc
    const val IMAGE_CACHE_BYTES = 300L * 1024 * 1024

    /** [maxWidth] serves the TV; the PC decodes at full size, as before. */
    @Suppress("UNUSED_PARAMETER")
    fun decodeImage(bytes: ByteArray, maxWidth: Int = 0): ImageBitmap? = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun isBackButton(e: androidx.compose.ui.input.pointer.PointerEvent) = e.button == androidx.compose.ui.input.pointer.PointerButton.Back

    fun openUrl(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
    }

    fun pickComicFile(): File? {
        val dialog = FileDialog(null as Frame?, "Sokoul", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".zip") } }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }

    /** The PC types with its keyboard: no speech input. */
    val hasVoice: Boolean get() = false
    @Suppress("UNUSED_PARAMETER") fun listen(onText: (String) -> Unit): Boolean = false
}

/**
 * Windows: SetThreadExecutionState, which belongs to the thread that called it, hence one long-lived thread for every
 * call. macOS: caffeinate, which also ends with the app (-w). Elsewhere nothing changes.
 */
private object KeepAwake {
    private val os = System.getProperty("os.name").lowercase()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sokoul-keep-awake").apply { isDaemon = true } }
    private var held = false
    private var caffeinate: Process? = null

    fun set(on: Boolean) = worker.execute {
        if (on == held) return@execute
        runCatching {
            when {
                os.startsWith("windows") -> Kernel32.INSTANCE.SetThreadExecutionState(
                    if (on) ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED else ES_CONTINUOUS,
                )
                os.startsWith("mac") -> if (on) {
                    caffeinate = ProcessBuilder("/usr/bin/caffeinate", "-di", "-w", ProcessHandle.current().pid().toString())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                } else {
                    caffeinate?.destroy()
                    caffeinate = null
                }
            }
            held = on
        }.onFailure { com.sokoul.pc.data.PcLog.event("keep awake ${if (on) "on" else "off"} failed: ${it.javaClass.simpleName}") }
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    @Suppress("FunctionName")
    private interface Kernel32 : com.sun.jna.Library {
        fun SetThreadExecutionState(flags: Int): Int

        companion object {
            val INSTANCE: Kernel32 by lazy { com.sun.jna.Native.load("kernel32", Kernel32::class.java) }
        }
    }
}
