package com.sokoul.pc

import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sokoul.desktop.data.AppDirs
import com.sokoul.desktop.data.Catalog
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.DesktopDebrid
import com.sokoul.desktop.data.DesktopSync
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.desktop.data.Settings
import com.sokoul.desktop.player.DesktopPlayer
import com.sokoul.iptv.data.net.CoreLog
import com.sokoul.iptv.data.net.Redact
import com.sokoul.pc.data.CredentialValidator
import com.sokoul.pc.data.DbTrailerStore
import com.sokoul.pc.data.LegacyImport
import com.sokoul.pc.data.MyList
import com.sokoul.pc.data.PcLog
import com.sokoul.pc.data.TmdbCatalog
import com.sokoul.pc.data.TrailerResolver
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.desktop.data.AudioPreference
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.iptv.data.debrid.RealDebridApi
import com.sokoul.pc.data.providers.ProviderHttp
import com.sokoul.pc.data.providers.SubtitleResult
import com.sokoul.pc.data.providers.SubtitlesClient
import com.sokoul.pc.dev.FaultSources
import com.sokoul.pc.playback.session.PlayRequest
import com.sokoul.pc.playback.sources.SubtitleSource
import com.sokoul.pc.i18n.Strings
import com.sokoul.pc.playback.session.ConnectionGate
import com.sokoul.pc.playback.sources.DebridTransfers
import com.sokoul.pc.playback.sources.RealSources
import com.sokoul.pc.playback.sources.StreamProbe
import com.sokoul.pc.playback.session.VlcEngine
import com.sokoul.pc.startup.SetupState
import com.sokoul.pc.startup.SplashScreen
import com.sokoul.pc.startup.StartupRoute
import com.sokoul.pc.ui.FocusSource
import com.sokoul.pc.ui.ScaledRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val FULLSCREEN_SETTLE_MS = 300L

private class Started(val db: DesktopDb, val player: CompletableFuture<DesktopPlayer?>, val deps: AppDeps)

fun main() {
    // Live check of a language, whatever the system's: only with SOKOUL_DEBUG.
    if (!System.getenv("SOKOUL_DEBUG").isNullOrBlank()) System.getenv("SOKOUL_LANG")?.takeIf { it.isNotBlank() }?.let { java.util.Locale.setDefault(java.util.Locale.forLanguageTag(it)) }
    Thread.setDefaultUncaughtExceptionHandler { _, t -> logCrash(t) }
    CoreLog.warn = { msg, e -> System.err.println("${Redact.text(msg)} : ${e?.javaClass?.simpleName}") }
    val startedAt = System.currentTimeMillis()
    FocusSource.trackRealMouse = true
    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ FocusSource.mouseMoved() }, java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK)
    // The window opens at once on the logo: libVLC's plugin scan and the database can take many seconds.
    var ready by mutableStateOf<Started?>(null)
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val db = DesktopDb.open()
        PcLog.event("database open after ${System.currentTimeMillis() - startedAt}ms")
        importOnce(db, LegacyImport.defaultSource(), AppDirs.data)
        val future = CompletableFuture<DesktopPlayer?>()
        var loaded by mutableStateOf<DesktopPlayer?>(null)
        ready = Started(db, future, realDeps(db, future) { loaded })
        launch {
            val player = DesktopPlayer.create()
            player?.prefs = { DesktopPrefs(db).engine(Strings.isFrench()) }
            PcLog.event("player ready after ${System.currentTimeMillis() - startedAt}ms loaded=${player != null}")
            loaded = player
            future.complete(player)
        }
        if (StartupRoute.state(db) == SetupState.Done) {
            // Keeps the IPTV catalog searchable for playback; it only calls the provider's API, it opens no stream.
            val playlists = db.playlists()
            val active = db.setting(Settings.ACTIVE_PLAYLIST)?.toLongOrNull()
            (playlists.firstOrNull { it.id == active } ?: playlists.firstOrNull())?.let { runCatching { DesktopSync(db).syncIfStale(it) } }
        }
    }
    application {
        val windowState = rememberWindowState(size = DpSize(1600.dp, 900.dp))
        val windowScope = androidx.compose.runtime.rememberCoroutineScope()
        var awtWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var floatingRect by remember { mutableStateOf<com.sokoul.pc.ui.NativeWindow.Rect?>(null) }
        val toggleFullscreen: () -> Unit = {
            if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen) {
                windowState.placement = androidx.compose.ui.window.WindowPlacement.Floating
                // On a scaled display the window comes back too small: its real rectangle is set again, once it has left.
                windowScope.launch {
                    kotlinx.coroutines.delay(FULLSCREEN_SETTLE_MS)
                    val w = awtWindow
                    val saved = floatingRect
                    if (w != null && saved != null) {
                        com.sokoul.pc.data.PcLog.event("fullscreen left: ${com.sokoul.pc.ui.NativeWindow.rect(w)} wanted $saved")
                        com.sokoul.pc.ui.NativeWindow.restore(w, saved)
                        kotlinx.coroutines.delay(FULLSCREEN_SETTLE_MS)
                        com.sokoul.pc.data.PcLog.event("fullscreen restored: ${com.sokoul.pc.ui.NativeWindow.rect(w)}")
                    }
                }
            } else {
                floatingRect = awtWindow?.let(com.sokoul.pc.ui.NativeWindow::rect)
                windowState.placement = androidx.compose.ui.window.WindowPlacement.Fullscreen
            }
        }
        ready?.deps?.toggleFullscreen = toggleFullscreen
        ready?.deps?.isFullscreen = { windowState.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen }
        Window(
            onCloseRequest = {
                ready?.let {
                    it.deps.playback.close()
                    it.player.getNow(null)?.release()
                    it.db.close()
                }
                exitApplication()
            },
            state = windowState,
            onPreviewKeyEvent = { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.F11) {
                    toggleFullscreen()
                    true
                } else {
                    false
                }
            },
            title = "Sokoul",
            icon = painterResource("sokoul.png"),
        ) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                awtWindow = window
                com.sokoul.pc.dev.DebugDriver.registry = { ready?.deps?.bridge?.registry }
                com.sokoul.pc.dev.DebugDriver.start(window)
                // MCP access for AI assistants, only when SOKOUL_MCP is set (see McpServer).
                com.sokoul.pc.control.McpServer.fromEnvironment { ready?.deps?.bridge?.registry }
            }
            ScaledRoot {
                val started = ready
                var logoDone by remember { mutableStateOf(false) }
                if (started == null || !logoDone) {
                    SplashScreen(onFinished = { logoDone = true })
                } else {
                    SokoulPcApp(started.deps, skipSplash = true)
                }
            }
        }
    }
}
