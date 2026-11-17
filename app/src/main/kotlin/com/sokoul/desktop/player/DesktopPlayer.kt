package com.sokoul.desktop.player

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer
import java.util.Timer
import kotlin.concurrent.schedule

/**
 * Image vidéo courante, dessinée par Compose : libVLC écrit chaque image (RV32 = BGRA) directement dans la mémoire
 * d'une Bitmap Skia ([frameTick] avance à chaque image). Double tampon : le décodeur écrit dans l'image arrière
 * sans verrou, l'échange avec l'image affichée est instantané ; le dessin ne bloque donc jamais le décodeur
 * plus que le temps d'un échange. Rien de natif à l'écran : barre et menus passent par-dessus.
 */
class VideoFrame {
    private class Buf(val bitmap: Bitmap, val pixels: ByteBuffer)

    private val lock = Any()
    private var front: Buf? = null
    @Volatile private var back: Buf? = null
    val frameTick = MutableStateFlow(0L)
    val size = MutableStateFlow(0 to 0)
    /** Rapport des pixels (SAR) : 16/15 pour une chaîne SD 720×576 en 16:9, 1 sinon. */
    @Volatile var pixelAspect = 1f
    /** Format d'image choisi dans le menu « … » (dessin seulement : le décodage ne change pas). */
    val aspect = MutableStateFlow(VideoAspect.FIT)

    private fun buffer(w: Int, h: Int): Buf {
        val b = Bitmap().apply { allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)) }
        val pm = b.peekPixels() ?: error("Pixels vidéo inaccessibles")
        return Buf(b, Pointer(pm.addr).getByteBuffer(0, pm.rowBytes.toLong() * h))
    }

    fun allocate(w: Int, h: Int) = synchronized(lock) {
        front?.bitmap?.close(); back?.bitmap?.close()
        front = buffer(w, h); back = buffer(w, h)
        size.value = w to h
    }

    fun write(src: ByteBuffer) {
        val b = back ?: return
        val s = src.duplicate().apply { rewind() }
        val d = b.pixels.duplicate().apply { rewind() }
        if (s.remaining() > d.remaining()) s.limit(s.position() + d.remaining())
        d.put(s)
        b.bitmap.notifyPixelsChanged()
        synchronized(lock) { if (back === b) { back = front; front = b } }
        frameTick.value++
    }

    /** Image affichée, lue sous verrou : jamais celle que libVLC est en train d'écrire. */
    fun <T> read(block: (Bitmap) -> T): T? = synchronized(lock) { front?.bitmap?.let(block) }

    fun clear() = synchronized(lock) {
        front?.bitmap?.close(); back?.bitmap?.close()
        front = null; back = null; size.value = 0 to 0; pixelAspect = 1f
    }
}

/**
 * Lecteur VLC (libVLC fourni avec l'app). **Une seule instance** : changer de flux arrête le précédent avant
 * d'ouvrir le suivant, l'abonnement IPTV n'autorisant qu'une connexion. Reconnexion automatique (3 essais), à la
 * même position pour un film.
 *
 * Fils : les événements vlcj arrivent sur leur propre fil, les commandes sur celui de l'interface. Le statut est
 * mis à jour de façon atomique (`update`) et chaque événement d'un média précédent est ignoré ([generation]).
 */
class DesktopPlayer private constructor(private val factory: MediaPlayerFactory) {
    val frame = VideoFrame()
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val _status = MutableStateFlow(PlayerStatus())
    val status: StateFlow<PlayerStatus> = _status
    private val _stats = MutableStateFlow<StreamSample?>(null)
    /** Apart from the status: reading it recomposes only what shows the measures. */
    val stats: StateFlow<StreamSample?> = _stats
    private var lastBytes = -1
    private var lastDisplayed = -1
    private var lastMeasureAt = 0L
    @Volatile private var current: StreamRequest? = null
    @Volatile private var retries = 0
    @Volatile private var released = false
    /** Avance à chaque ouverture ou arrêt ; [accepted] = génération du média qui envoie actuellement ses événements. */
    @Volatile private var generation = 0
    @Volatile private var accepted = -1
    /** Piste audio choisie dans le menu : la langue n'est plus imposée pour ce titre. */
    @Volatile private var audioChosenByUser = false
    /** Sous-titres par défaut déjà posés (ou choisis à la main) pour ce titre. */
    @Volatile private var subtitleChosen = false
    /** Réglages lus à chaque ouverture (langues, mémoire tampon, apparence des sous-titres). */
    @Volatile var prefs: () -> com.sokoul.desktop.data.PlayerPrefs = { com.sokoul.desktop.data.PlayerPrefs() }
    private val timer = Timer("sokoul-reconnect", true).apply {
        schedule(STATS_EVERY_MS, STATS_EVERY_MS) { player.submit { if (!released) measure() } }
    }

    private inline fun ifCurrent(block: () -> Unit) { if (accepted == generation) block() }

    init {
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    // définition décodée limitée à 2560 px de large (copie d'image par image raisonnable)
                    val scale = minOf(1.0, MAX_WIDTH.toDouble() / sourceWidth.coerceAtLeast(1))
                    val w = (sourceWidth * scale).toInt().coerceAtLeast(2) and 1.inv()
                    val h = (sourceHeight * scale).toInt().coerceAtLeast(2) and 1.inv()
                    frame.allocate(w, h)
                    return RV32BufferFormat(w, h)
                }
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
            },
            RenderCallback { _, nativeBuffers, _ -> nativeBuffers.firstOrNull()?.let(frame::write) },
            true,
        ))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            // les événements de l'ancien média, encore en file, passent avant celui-ci : ils sont ignorés
            override fun mediaChanged(mediaPlayer: MediaPlayer, media: uk.co.caprica.vlcj.media.MediaRef) { accepted = generation }
            override fun playing(mediaPlayer: MediaPlayer) = ifCurrent {
                retries = 0
                _status.update { it.copy(playing = true, paused = false, buffering = false, error = null) }
                // volume et muet choisis avant l'ouverture du flux : appliqués au flux qui démarre (aperçu : toujours muet)
                val st = _status.value
                mediaPlayer.submit { if (!released) { mediaPlayer.audio().setVolume(st.volume); mediaPlayer.audio().setMute(st.muted || previewMuted) } }
            }
            override fun paused(mediaPlayer: MediaPlayer) = ifCurrent { _status.update { it.copy(paused = true) } }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ifCurrent { _status.update { it.copy(buffering = newCache < 100f, bufferPercent = newCache.toInt().coerceIn(0, 100)) } }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = ifCurrent { keepMuteRight(mediaPlayer); _status.update { it.copy(timeMs = newTime) } }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = ifCurrent { _status.update { it.copy(lengthMs = newLength) } }
            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) = ifCurrent { refreshTracks() }
            // définition et codecs connus seulement une fois l'image sortie : relus hors du fil des événements
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) = ifCurrent { mediaPlayer.submit { if (!released) refreshTracks() } }
            override fun error(mediaPlayer: MediaPlayer) = ifCurrent { reconnect("Le flux a été interrompu") }
            override fun finished(mediaPlayer: MediaPlayer) = ifCurrent {
                if (current?.live == true) reconnect("Le flux s'est arrêté")
                else _status.update { it.copy(playing = false, paused = false, ended = true) }
            }
        })
    }

    /**
     * libVLC ignores a change of mute asked for before its audio output exists, and the output comes after the "playing"
     * event: a preview would play with its sound, and the stream opened right after it would stay silent. The mute is
     * checked again as the stream advances.
     */
    private fun keepMuteRight(mediaPlayer: MediaPlayer) {
        mediaPlayer.submit {
            if (!released) {
                val wanted = previewMuted || _status.value.muted
                if (mediaPlayer.audio().isMute != wanted) mediaPlayer.audio().setMute(wanted)
            }
        }
    }

    /** Aperçu du Direct : le même lecteur (une connexion), son coupé sans toucher au réglage « muet ». */
    @Volatile var previewMuted = false
        private set

    fun preview(req: StreamRequest) { previewMuted = true; start(req) }

    /** The stream goes on behind the guide: back to a muted preview, with no new connection. */
    fun enterPreview() {
        previewMuted = true
        player.submit { if (!released) player.audio().setMute(true) }
    }

    /** « Regarder » : l'aperçu devient la lecture, sans rouvrir le flux ; le son revient au réglage. */
    fun endPreview() {
        if (!previewMuted) return
        previewMuted = false
        player.submit { if (!released) player.audio().setMute(_status.value.muted) }
    }

    fun play(req: StreamRequest) { previewMuted = false; start(req) }

    private val localRelay = lazy { LocalMediaRelay() }

    @Synchronized
    private fun start(req: StreamRequest) {
        if (released) return
        // une seule connexion : le flux précédent est fermé avant d'ouvrir le suivant
        generation++
        lastBytes = -1
        lastDisplayed = -1
        _stats.value = null
        // essais : chaque ouverture de flux est notée (vérifier qu'« Regarder » ne rouvre rien)
        if (System.getenv("SOKOUL_ROUTE") != null) System.err.println("Flux ouvert : ${if (req.live) "direct" else "film"} ${req.title}${if (previewMuted) " (aperçu)" else ""}")
        player.controls().stop()
        if (localRelay.isInitialized()) localRelay.value.clear()
        val playable = if (req.headers.isNotEmpty()) localRelay.value.register(req) else req
        current = playable
        retries = 0
        audioChosenByUser = false
        subtitleChosen = false
        _status.update { PlayerStatus(title = req.title, buffering = true, volume = it.volume, muted = it.muted, session = req.session) }
        // chaque titre repart à vitesse normale
        player.controls().setRate(1f)
        open(playable)
    }

    private fun open(req: StreamRequest) {
        val p = prefs()
        val opts = buildList {
            add(":network-caching=${if (req.live) p.buffer.liveMs else p.buffer.vodMs}")
            add(":http-reconnect")
            // « version originale » : libVLC ne doit pas imposer sa langue par défaut dans un fichier MULTI
            if (p.audioLanguage.isBlank()) add(":audio-language=any")
            // apparence des sous-titres des Réglages (options du média : appliquées dès ce titre)
            addAll(p.subtitleOptions())
            // reprise : libVLC ouvre directement à la position (aussi après une reconnexion)
            if (!req.live && req.startMs > 0) add(":start-time=${req.startMs / 1000.0}")
            req.userAgent?.takeIf { it.isNotBlank() }?.let { add(":http-user-agent=$it") }
            req.referrer?.takeIf { it.isNotBlank() }?.let { add(":http-referrer=$it") }
            req.audioUrl?.let { add(":input-slave=$it") }
        }
        player.media().play(req.url, *opts.toTypedArray())
        req.externalSubtitles.forEach { sub ->
            val selected = p.subtitleLanguage.isNotBlank() && sub.language.equals(p.subtitleLanguage, true)
            player.media().addSlave(uk.co.caprica.vlcj.media.MediaSlaveType.SUBTITLE, sub.url, selected)
        }
    }

    private fun reconnect(reason: String) {
        val req = current ?: return
        if (retries >= MAX_RETRIES) {
            _status.update { it.copy(playing = false, buffering = false, error = "$reason. Vérifiez la connexion ou réessayez plus tard.") }
            return
        }
        retries++
        val resumeAt = _status.value.timeMs
        _status.update { it.copy(buffering = true, error = null) }
        val gen = generation
        timer.schedule(RETRY_DELAY_MS * retries) {
            // revérifié au moment d'ouvrir : un autre flux a pu être lancé, ou le lecteur libéré, entre-temps
            player.submit {
                if (!released && current === req && generation == gen) {
                    generation++
                    open(if (req.live) req else req.copy(startMs = resumeAt))
                }
            }
        }
    }

    private fun measure() {
        if (current == null) {
            _stats.value = null
            return
        }
        val info = player.media().info() ?: return
        val st = info.statistics() ?: return
        val now = System.currentTimeMillis()
        val seconds = (now - lastMeasureAt) / 1000f
        val bytes = st.inputBytesRead()
        val kbps = if (lastBytes in 0..bytes && seconds > 0) ((bytes - lastBytes) * 8 / 1000f / seconds).toInt() else null
        val measuredFps = if (lastDisplayed in 0..st.picturesDisplayed() && seconds > 0) (st.picturesDisplayed() - lastDisplayed) / seconds else null
        lastBytes = bytes
        lastDisplayed = st.picturesDisplayed()
        lastMeasureAt = now
        val vt = info.videoTracks()?.firstOrNull()
        val at = info.audioTracks()?.firstOrNull()
        val announcedFps = vt?.takeIf { it.frameRateBase() > 0 }?.let { it.frameRate().toFloat() / it.frameRateBase() }
        _stats.value = StreamSample(
            _status.value.session,
            vt?.width() ?: 0,
            vt?.height() ?: 0,
            announcedFps?.takeIf { it > 0 } ?: measuredFps?.takeIf { it > 0 },
            vt?.codecName()?.let(::codecLabel),
            at?.codecName()?.let(::codecLabel),
            at?.rate(),
            at?.channels(),
            kbps,
            st.picturesDisplayed(),
            st.picturesLost(),
            st.demuxCorrupted(),
            st.demuxDiscontinuity(),
        )
    }

    fun retry() { current?.let { play(it) } }

    /**
     * Stopping, opening and releasing never overlap: freeing libVLC while a stop still runs on the engine's thread is
     * an invalid memory access, the crash seen when the window closes during playback.
     */
    @Synchronized
    fun stop() {
        if (released) return
        previewMuted = false
        generation++
        current = null
        player.controls().stop()
        if (localRelay.isInitialized()) localRelay.value.clear()
        frame.clear()
        _status.update { PlayerStatus(volume = it.volume, muted = it.muted) }
    }

    /**
     * Lecture / pause. En direct : libVLC garde le flux en mémoire pendant la pause (différé) ; le retard accumulé est
     * suivi pour afficher « Différé · −2 min » et proposer « Retour au direct ».
     */
    fun togglePause() {
        if (current?.live == true) {
            val now = System.currentTimeMillis()
            if (!_status.value.paused) pausedAt = now
            else if (pausedAt > 0) { val d = now - pausedAt; pausedAt = 0; _status.update { it.copy(liveDelayMs = it.liveDelayMs + d) } }
        }
        player.controls().pause()
    }

    /** « Retour au direct » : le flux est rouvert, le retard retombe à zéro. */
    fun backToLive() { pausedAt = 0; current?.takeIf { it.live }?.let { play(it) } }

    @Volatile private var pausedAt = 0L

    fun seekBy(deltaMs: Long) {
        if (current?.live != false) return
        val len = _status.value.lengthMs
        val t = (player.status().time() + deltaMs).coerceIn(0, if (len > 0) len - 1000 else Long.MAX_VALUE)
        player.controls().setTime(t)
        _status.update { it.copy(timeMs = t) }
    }

    fun seekTo(fraction: Float) {
        if (current?.live != false) return
        val len = _status.value.lengthMs.takeIf { it > 0 } ?: return
        val t = (len * fraction.coerceIn(0f, 1f)).toLong()
        player.controls().setTime(t)
        _status.update { it.copy(timeMs = t) }
    }

    fun setAudio(id: Int) { audioChosenByUser = true; player.audio().setTrack(id); refreshTracks() }
    fun setSubtitles(id: Int) { subtitleChosen = true; player.subpictures().setTrack(id); refreshTracks() }

    /** Vitesse de lecture (menu « … ») : 0,75× à 2×. */
    fun setRate(rate: Float) { player.controls().setRate(rate); _status.update { it.copy(rate = rate) } }

    /** Décalage des sous-titres en millisecondes (positif = plus tard). */
    fun setSubtitleDelay(ms: Long) { player.subpictures().setDelay(ms * 1000); _status.update { it.copy(subtitleDelayMs = ms) } }

    fun volume(delta: Int) = setVolume(_status.value.volume + delta)

    fun setVolume(v: Int) {
        val vol = v.coerceIn(0, 150)
        player.audio().setVolume(vol)
        if (_status.value.muted) player.audio().setMute(false)
        _status.update { it.copy(volume = vol, muted = false) }
    }

    /** Safe at any moment: does nothing once the player is released. */
    @Synchronized
    fun setMutedSafely(on: Boolean) {
        if (released || _status.value.muted == on) return
        player.submit { if (!released) player.audio().setMute(on) }
        _status.update { it.copy(muted = on) }
    }

    fun toggleMute() {
        val m = !_status.value.muted
        player.audio().setMute(m)
        _status.update { it.copy(muted = m) }
    }

    private fun refreshTracks() {
        val audio = player.audio().trackDescriptions().filter { it.id() >= 0 }.map { it.id() to it.description() }
        // les pistes arrivent une à une : la langue est revue à chaque ajout, tant que l'utilisateur n'a rien choisi
        val p = prefs()
        // langue vide = version originale : la piste non française d'un MULTI, pas celle marquée par défaut (souvent la VF)
        val wanted = if (p.audioLanguage.isNotBlank()) AudioChoice.pick(audio, p.audioLanguage) else AudioChoice.pickOriginal(audio)
        if (!audioChosenByUser) wanted?.takeIf { it != player.audio().track() }?.let { id ->
            player.submit { if (!released) { player.audio().setTrack(id); refreshTracks() } }
        }
        // sous-titres par défaut des Réglages : posés une fois, dès qu'une piste de la langue apparaît
        if (!subtitleChosen) {
            val subTracks = player.subpictures().trackDescriptions().map { it.id() to it.description() }
            AudioChoice.pickSubtitle(subTracks, p.subtitleLanguage)?.let { id ->
                subtitleChosen = true
                player.submit { if (!released) { player.subpictures().setTrack(id); refreshTracks() } }
            }
        }
        val subs = player.subpictures().trackDescriptions().map { it.id() to if (it.id() < 0) "Désactivés" else it.description() }
        val dim = player.video().videoDimension()
        val info = player.media().info()
        val vt = info?.videoTracks()?.firstOrNull()
        // pixels non carrés (SD anamorphosée) : rapport appliqué au dessin
        vt?.let { t ->
            val num = t.sampleAspectRatio(); val den = t.sampleAspectRatioBase()
            frame.pixelAspect = if (num > 0 && den > 0) num.toFloat() / den else 1f
        }
        // « 1920×1080 · AVC · AAC » comme la ligne technique de la tablette
        val size = dim?.takeIf { d -> d.width > 0 && d.height > 0 }?.let { d -> "${d.width}×${d.height}" }
            ?: vt?.takeIf { it.width() > 0 && it.height() > 0 }?.let { "${it.width()}×${it.height()}" }
        val video = listOfNotNull(size, vt?.codecName()?.let(::codecLabel), info?.audioTracks()?.firstOrNull()?.codecName()?.let(::codecLabel))
            .joinToString(" · ").ifBlank { null }
        _status.update {
            it.copy(audio = audio, audioId = player.audio().track(), subtitles = subs, subtitleId = player.subpictures().track(), video = video)
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        stop()
        released = true
        if (localRelay.isInitialized()) localRelay.value.close()
        timer.cancel()
        player.release()
        factory.release()
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val STATS_EVERY_MS = 1_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val MAX_WIDTH = 2560

        /** Code quatre lettres de libVLC → nom lisible (h264 → AVC, mp4a → AAC). */
        fun codecLabel(fourcc: String): String? = when (val c = fourcc.trim().lowercase()) {
            "" -> null
            "h264", "avc1" -> "AVC"
            "hevc", "h265", "hev1", "hvc1" -> "HEVC"
            "mp4a" -> "AAC"
            "a52", "a52 " -> "AC3"
            "mpga", "mp3" -> "MP3"
            "mpgv", "mp2v" -> "MPEG-2"
            else -> c.uppercase()
        }

        /** Dossier de libVLC : ressources de l'app installée, ou `desktop/resources/windows/vlc` en développement. */
        fun vlcDir(): File? {
            val candidates = listOfNotNull(
                System.getenv("SOKOUL_VLC_DIR")?.takeIf { it.isNotBlank() }?.let(::File),
                System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") },
                File("app/app-resources/windows/vlc"),
                File("resources/windows/vlc"),
                File("desktop/resources/windows/vlc"),
            )
            return candidates.firstOrNull { File(it, "libvlc.dll").isFile }
        }

        /** Crée le lecteur, ou null si libVLC est introuvable (écran d'explication à la place). */
        fun create(): DesktopPlayer? {
            val dir = vlcDir() ?: return null
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), dir.absolutePath)
            NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), dir.absolutePath)
            return runCatching {
                DesktopPlayer(MediaPlayerFactory(null as NativeDiscovery?, "--no-video-title-show", "--quiet", "--no-snapshot-preview", "--no-osd",
                    // piste française d'abord (versions MULTI) ; jamais de sous-titres imposés : on les choisit dans la barre
                    if (java.util.Locale.getDefault().language == "fr") "--audio-language=fr,fre,fra,french" else "--audio-language=en,eng,english", "--sub-language=none"))
            }.getOrNull()
        }
    }
}
