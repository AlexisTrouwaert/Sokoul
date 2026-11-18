package com.sokoul.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sokoul.desktop.data.AppDirs
import com.sokoul.desktop.data.DesktopDb
import com.sokoul.desktop.data.DesktopPrefs
import com.sokoul.desktop.player.DesktopPlayer
import com.sokoul.pc.AppDeps
import com.sokoul.pc.SokoulPcApp
import com.sokoul.pc.i18n.Strings
import com.sokoul.pc.realDeps
import com.sokoul.pc.startup.SplashScreen
import com.sokoul.pc.ui.FocusSource
import com.sokoul.pc.ui.ScaledRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    private var deps by mutableStateOf<AppDeps?>(null)
    private var player by mutableStateOf<DesktopPlayer?>(null)
    private var db: DesktopDb? = null
    /** A title asked for by a press on the Watch Next row, opened once the interface is ready. */
    private var link by mutableStateOf<com.sokoul.pc.playback.session.PlayRequest?>(null)

    private var voiceReply: ((String) -> Unit)? = null
    /** The system's speech recogniser (the remote's microphone): it records itself, so no audio permission is needed here. */
    private val voice = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) voiceReply?.invoke(text)
        voiceReply = null
    }

    private fun listen(onText: (String) -> Unit): Boolean {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (intent.resolveActivity(packageManager) == null) return false
        voiceReply = onText
        return runCatching { voice.launch(intent) }.isSuccess
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // After setContent: Compose's view exists, and the TV's volume-key service no longer costs it every frame.
        A11yGate.install(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (com.sokoul.pc.Platform.dispatchPlayerBack()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        // The pages paint their own background: the window's was one more full-screen fill under every frame.
        window.setBackgroundDrawable(null)
        com.sokoul.pc.Platform.voiceHook = ::listen
        link = WatchNext.requestOf(intent?.data)
        applyFormFactor(resources.configuration)
        // A phone's taps report no hover worth following: only the TV's air mouse gives focus by pointing.
        FocusSource.trackRealMouse = !com.sokoul.pc.ui.Form.isPhone
        com.sokoul.pc.Platform.playerFullscreenHook = { on -> runOnUiThread { playerFullscreen(on) } }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val base = DesktopDb.open()
            db = base
            val future = CompletableFuture<DesktopPlayer?>()
            val made = realDeps(base, future) { player }
            launch(Dispatchers.Main) { deps = made }
            val p = DesktopPlayer.create()
            p?.prefs = { DesktopPrefs(base).engine() }
            launch(Dispatchers.Main) { player = p }
            future.complete(p)
            launch(Dispatchers.Main) { TvDebug.start(made) }
            // As on Windows (Main.kt, left out of this build): channels and guide refresh when older than half a day.
            // Without it the TV kept the guide it was set up with, empty a day later ("the guide does not know this channel").
            if (com.sokoul.pc.startup.StartupRoute.state(base) == com.sokoul.pc.startup.SetupState.Done) {
                val playlists = base.playlists()
                val active = base.setting(com.sokoul.desktop.data.Settings.ACTIVE_PLAYLIST)?.toLongOrNull()
                (playlists.firstOrNull { it.id == active } ?: playlists.firstOrNull())?.let { playlist ->
                    val sync = com.sokoul.desktop.data.DesktopSync(base)
                    runCatching {
                        sync.syncIfStale(playlist)
                        if (sync.isGuideStale(playlist)) sync.syncGuide(playlist)
                    }
                }
            }
        }
        setContent {
            // A TV recentres whatever has focus by default, which pushes a page's hero off the screen. Here, as on Windows, a page moves only as far as it must.
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides object : androidx.compose.foundation.gestures.BringIntoViewSpec {}) {
                ScaledRoot {
                    val started = deps
                    var logoDone by remember { mutableStateOf(false) }
                    if (started == null || !logoDone) {
                        SplashScreen(onFinished = { logoDone = true })
                    } else {
                        SokoulPcApp(started, skipSplash = true)
                    }
                }
            }
            // The title of a Watch Next entry opens as soon as the interface can: not before the app has loaded.
            val asked = link
            val ready = deps
            if (asked != null && ready != null) {
                LaunchedEffect(asked) {
                    var waited = 0
                    while (ready.bridge.navigation == null && waited < LINK_WAIT_MS) {
                        kotlinx.coroutines.delay(200)
                        waited += 200
                    }
                    ready.bridge.navigation?.play(asked)
                    link = null
                }
            }
            val p = player
            if (p != null) {
                LaunchedEffect(p) {
                    p.status.collect { s ->
                        if (s.playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFormFactor(newConfig)
    }

    /** A TV, or a phone held sideways or upright: the shared screens size and arrange themselves from it. */
    private fun applyFormFactor(config: android.content.res.Configuration) {
        val modes = getSystemService(android.app.UiModeManager::class.java)
        val television = modes?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val handheld = !television && config.smallestScreenWidthDp < PHONE_MAX_SMALLEST_DP
        com.sokoul.pc.ui.Form.current = when {
            !handheld -> com.sokoul.pc.ui.FormFactor.Tv
            config.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT -> com.sokoul.pc.ui.FormFactor.PhonePortrait
            else -> com.sokoul.pc.ui.FormFactor.PhoneLandscape
        }
    }

    /** On a phone, a video takes the whole screen sideways; leaving it gives the sensor and the system bars back. */
    private fun playerFullscreen(on: Boolean) {
        // Once sideways, the form factor reads "phone held sideways": the phone is still a phone.
        if (com.sokoul.pc.ui.Form.current == com.sokoul.pc.ui.FormFactor.Tv) return
        requestedOrientation = if (on) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The remote speaks the keyboard the screens already know: Back is Escape, OK is Enter. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && com.sokoul.pc.Platform.hasPlayerBackHandler()) {
            if (event.action == KeyEvent.ACTION_UP) com.sokoul.pc.Platform.dispatchPlayerBack()
            return true
        }
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> KeyEvent.KEYCODE_ESCAPE
            KeyEvent.KEYCODE_GUIDE -> KeyEvent.KEYCODE_F2
            KeyEvent.KEYCODE_INFO -> KeyEvent.KEYCODE_F1
            KeyEvent.KEYCODE_CHANNEL_UP -> KeyEvent.KEYCODE_PAGE_UP
            KeyEvent.KEYCODE_CHANNEL_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
            KeyEvent.KEYCODE_LAST_CHANNEL -> KeyEvent.KEYCODE_DEL
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_ENTER
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> KeyEvent.KEYCODE_SPACE
            else -> event.keyCode
        }
        val e = if (mapped == event.keyCode) event else KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
        return super.dispatchKeyEvent(e)
    }

    /** The app is already open (single task): a press on the Watch Next row reaches it here. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WatchNext.requestOf(intent.data)?.let { link = it }
    }

    override fun onStop() {
        super.onStop()
        player?.let { p -> if (p.status.value.playing) p.togglePause() }
        // Leaving the app is when the home screen's Continue Watching row must be up to date.
        val base = db
        if (base != null) {
            val context = applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { WatchNext.sync(context, base.resumeList()) }
        }
    }

    private companion object {
        const val LINK_WAIT_MS = 20_000
        /** Below this smallest width (dp), a screen that is not a television is a phone. */
        const val PHONE_MAX_SMALLEST_DP = 600
    }

    override fun onDestroy() {
        if (isFinishing) player?.release()
        super.onDestroy()
    }
}
