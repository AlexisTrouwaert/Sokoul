package com.sokoul.desktop.data

import com.sokoul.desktop.player.StreamRequest
import com.sokoul.pc.Platform
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Durable plans and capture share the account's one provider connection. */
class DesktopRecorder(
    private val db: DesktopDb,
    private val sync: DesktopSync,
    private val scope: CoroutineScope,
    private val outputDir: File = File(AppDirs.data, "recordings"),
) {
    companion object {
        val connectionInUse = AtomicBoolean(false)
        private val localUrls = ConcurrentHashMap.newKeySet<String>()
        fun isLocalStream(url: String): Boolean = url in localUrls
        @Volatile var runtime: DesktopRecorder? = null
            private set
        private const val RULES = "pc.recording_rules.v1"
        private const val LINKS = "pc.recording_rule_links.v1"
        private const val STORAGE = "pc.recording_directory"
        private const val START_SPACE = 256L * 1024 * 1024
        private const val STOP_SPACE = 64L * 1024 * 1024
    }
    private val http = OkHttpClient.Builder().dns(com.sokoul.iptv.data.net.Ipv4FirstDns)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Mutex()
    private val started = AtomicBoolean(false)
    private val _active = MutableStateFlow<Recording?>(null)
    private val _progress = MutableStateFlow<RecordingProgress?>(null)
    val active: StateFlow<Recording?> = _active
    val progress: StateFlow<RecordingProgress?> = _progress
    val version = MutableStateFlow(0)
    private var job: Job? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var beforeCapture: suspend () -> Unit = {}
    @Volatile private var keepAlive: (Boolean) -> Unit = {}
    private val finalStatuses = ConcurrentHashMap<Long, RecordingStatus>()
    private val deleted = ConcurrentHashMap.newKeySet<Long>()
    private val ends = ConcurrentHashMap<Long, Long>()
    private val localServer by lazy { RecordingStreamServer() }

    init { runtime = this }

    fun configure(beforeCapture: suspend () -> Unit = {}, keepAlive: (Boolean) -> Unit = {}) {
        this.beforeCapture = beforeCapture
        this.keepAlive = keepAlive
    }
    val dir: File get() = (db.setting(STORAGE)?.let(::File) ?: outputDir).apply { mkdirs() }
    fun recordings(playlistId: Long): List<Recording> = db.recordings(playlistId)
    fun liveFile(): File? = active.value?.path?.let(::File)?.takeIf { it.exists() }

    /** Local tailing HTTP avoids EOF while the provider capture continues growing. */
    fun localStream(offsetMs: Long = 0): StreamRequest? {
        val current = active.value ?: return null
        val file = liveFile() ?: return null
        if (!file.name.endsWith(".ts", true)) return null
        val elapsed = (System.currentTimeMillis() - current.startAt).coerceAtLeast(1)
        val offset = (current.bytes * offsetMs.coerceIn(0, elapsed).toDouble() / elapsed).toLong()
        val url = localServer.url(file, offset - offset % 188) { active.value?.id == current.id }
        if (localUrls.size > 64) localUrls.clear()
        localUrls.add(url)
        return StreamRequest(url, null, null, current.title, live = true)
    }

    /** Initialise once per process, including plans belonging to inactive playlists. */
    fun start(playlist: () -> Playlist?) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                db.playlists().ifEmpty { listOfNotNull(playlist()) }.forEach { p ->
                    db.recordings(p.id).filter { PcRecordingRules.missed(it, now) }.forEach { r ->
                        val resumable = r.status == RecordingStatus.RECORDING && r.endAt > now && !r.channelId.startsWith("download:")
                        db.updateRecording(r.id, if (resumable) RecordingStatus.SCHEDULED else RecordingStatus.FAILED,
                            bytes = r.path?.let(::File)?.length() ?: r.bytes,
                            error = if (resumable) null else "Recording interrupted while the application was stopped")
                    }
                }
                changed()
            }
            while (isActive) {
                try { tickAll() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                delay(2_000)
            }
        }
    }

    @Synchronized
    fun schedule(p: Playlist, c: Channel, title: String, start: Long, end: Long): PcRecordingRules.Verdict {
        val target = runCatching { dir }.getOrNull() ?: return PcRecordingRules.Verdict.Refused("Recording storage is unavailable")
        if (!target.canWrite() || target.usableSpace < START_SPACE) return PcRecordingRules.Verdict.Refused("Recording storage needs at least 256 MB available")
        val verdict = PcRecordingRules.check(c.id, start, end, allRecordings())
        if (verdict is PcRecordingRules.Verdict.Ok) {
            db.insertRecording(Recording(0, p.id, c.id, c.name, c.logo, title, start, end, RecordingStatus.SCHEDULED))
            changed()
            scope.launch(Dispatchers.IO) { runCatching { tickAll() } }
        }
        return verdict
    }

    @Synchronized
    fun scheduleProgramme(p: Playlist, c: Channel, programme: Program, repeat: RecordingRepeat = RecordingRepeat.NONE): PcRecordingRules.Verdict {
        val now = System.currentTimeMillis()
        val slot = PcRecordingRules.slot(programme.start, programme.end, now)
        val verdict = schedule(p, c, programme.title, slot.first, slot.second)
        if (verdict is PcRecordingRules.Verdict.Ok && repeat != RecordingRepeat.NONE) {
            val anchor = programme.start - PcRecordingRules.MARGIN_BEFORE_MS
            val rule = RecordingRule(UUID.randomUUID().toString(), p.id, c.id, programme.title, anchor,
                programme.end + PcRecordingRules.MARGIN_AFTER_MS - anchor, repeat)
            saveRules(allRules() + rule)
            db.recordings(p.id).lastOrNull { it.channelId == c.id && it.startAt == slot.first && it.endAt == slot.second }?.let {
                saveLinks(links() + (it.id.toString() to rule.id))
            }
        }
        return verdict
    }

    fun rules(playlistId: Long): List<RecordingRule> = allRules().filter { it.playlistId == playlistId }
    @Synchronized fun cancelRule(ruleId: String) {
        val related = links().filterValues { it == ruleId }.keys.mapNotNull(String::toLongOrNull).toSet()
        allRecordings().filter { it.id in related && it.status == RecordingStatus.SCHEDULED }.forEach(::cancel)
        saveRules(allRules().filterNot { it.id == ruleId })
        saveLinks(links().filterValues { it != ruleId })
        changed()
    }

    fun stop(r: Recording) {
        if (active.value?.id == r.id) stopActive(r.id, RecordingStatus.COMPLETED) else cancel(r)
    }
    fun cancel(r: Recording) {
        if (active.value?.id == r.id) stopActive(r.id, RecordingStatus.CANCELLED)
        else { db.updateRecording(r.id, RecordingStatus.CANCELLED, error = "Cancelled"); changed() }
    }
    private fun stopActive(id: Long, status: RecordingStatus) {
        finalStatuses[id] = status
        activeCall?.cancel()
        job?.cancel()
    }
    @Synchronized fun extend(r: Recording, minutes: Int = 15): PcRecordingRules.Verdict {
        if (minutes <= 0 || r.status !in setOf(RecordingStatus.RECORDING, RecordingStatus.SCHEDULED)) return PcRecordingRules.Verdict.Refused("This recording cannot be extended")
        val stored = db.recordings(r.playlistId).firstOrNull { it.id == r.id } ?: return PcRecordingRules.Verdict.Refused("Recording not found")
        val end = maxOf(stored.endAt, ends[r.id] ?: stored.endAt) + minutes * 60_000L
        val verdict = PcRecordingRules.check(stored.channelId, stored.startAt, end, allRecordings().filterNot { it.id == r.id })
        if (verdict is PcRecordingRules.Verdict.Ok) {
            ends[r.id] = end
            db.updateRecordingEnd(r.id, end)
            _active.value?.takeIf { it.id == r.id }?.let { _active.value = it.copy(endAt = end) }
            changed()
        }
        return verdict
    }
    fun delete(r: Recording) {
        if (active.value?.id == r.id) { deleted.add(r.id); stopActive(r.id, RecordingStatus.CANCELLED); return }
        r.path?.let { File(it).delete() }
        db.deleteRecording(r.id)
        changed()
    }

    fun storages(): List<RecordingStorage> {
        val standard = Platform.recordingStorages()
        val selected = db.setting(STORAGE)?.let(::File)
        return if (selected != null && standard.none { it.directory.absolutePath == selected.absolutePath })
            standard + RecordingStorage(selected.absolutePath, selected.name.ifBlank { "Selected folder" }, selected)
        else standard
    }
    fun selectStorage(id: String): PcRecordingRules.Verdict {
        if (active.value != null) return PcRecordingRules.Verdict.Refused("Stop the recording before changing storage")
        val target = storages().firstOrNull { it.id == id }?.directory
            ?: if (!Platform.IS_TV) File(id) else return PcRecordingRules.Verdict.Refused("Storage is no longer connected")
        if (!target.isAbsolute || (!target.exists() && !target.mkdirs()) || !target.isDirectory || !target.canWrite()) return PcRecordingRules.Verdict.Refused("The selected storage is not writable")
        if (target.usableSpace < START_SPACE) return PcRecordingRules.Verdict.Refused("The selected storage needs at least 256 MB available")
        db.putSetting(STORAGE, target.absolutePath)
        changed()
        return PcRecordingRules.Verdict.Ok()
    }
    fun export(r: Recording, storageId: String): File? {
        if (active.value?.id == r.id) return null
        val source = r.path?.let(::File)?.takeIf { it.isFile && it.length() > 0 } ?: return null
        val destination = storages().firstOrNull { it.id == storageId }?.directory ?: return null
        if ((!destination.exists() && !destination.mkdirs()) || !destination.canWrite() || destination.usableSpace < source.length() + STOP_SPACE) return null
        val result = File(destination, "${r.id}-${source.name}")
        if (source.canonicalFile == result.canonicalFile) return source
        return runCatching { source.copyTo(result, overwrite = false) }.getOrNull()
    }

    /** Provider URLs remain in memory; direct-file download shares the capture gate. */
    @Synchronized fun saveStream(p: Playlist, request: StreamRequest, title: String, extension: String): PcRecordingRules.Verdict {
        val ext = extension.trimStart('.').lowercase()
        if (ext !in setOf("mp4", "mkv", "ts", "webm", "avi") || !request.url.startsWith("http", true) || request.url.substringBefore('?').endsWith(".m3u8", true)) return PcRecordingRules.Verdict.Refused("Only direct video files can be downloaded")
        if (dir.usableSpace < START_SPACE) return PcRecordingRules.Verdict.Refused("Recording storage needs at least 256 MB available")
        val now = System.currentTimeMillis()
        val verdict = PcRecordingRules.check("download", now, now + PcRecordingRules.MAX_DURATION_MS, allRecordings())
        if (verdict is PcRecordingRules.Verdict.Refused) return verdict
        if (!connectionInUse.compareAndSet(false, true)) return PcRecordingRules.Verdict.Refused("A provider recording already uses the connection")
        try {
            val row = Recording(0, p.id, "download:${UUID.randomUUID()}", title, null, title, now, now + PcRecordingRules.MAX_DURATION_MS, RecordingStatus.SCHEDULED)
            begin(row.copy(id = db.insertRecording(row)), request, ext, completeOnEof = true)
        } catch (failure: Exception) { connectionInUse.set(false); throw failure }
        return PcRecordingRules.Verdict.Ok()
    }

    private suspend fun tickAll() = gate.withLock {
        materializeRules()
        if (active.value != null) return@withLock
        val now = System.currentTimeMillis()
        val candidates = db.playlists().flatMap { p -> db.recordings(p.id).map { p to it } }
        val missed = candidates.filter { (_, r) -> r.status == RecordingStatus.SCHEDULED && r.endAt <= now }
        missed.forEach { (_, r) -> db.updateRecording(r.id, RecordingStatus.FAILED, error = "The scheduled programme was missed") }
        if (missed.isNotEmpty()) changed()
        val (playlist, row) = candidates.filter { (_, r) -> r.status == RecordingStatus.SCHEDULED && r.startAt <= now && r.endAt > now }.minByOrNull { it.second.startAt } ?: return@withLock
        val channel = db.channelsByIds(playlist.id, listOf(row.channelId)).firstOrNull()
        if (channel == null) { db.updateRecording(row.id, RecordingStatus.FAILED, error = "Channel not found"); changed(); return@withLock }
        if (!connectionInUse.compareAndSet(false, true)) return@withLock
        try { begin(row, LiveLogic.stream(playlist, channel, sync.userAgent()), "ts") }
        catch (failure: Exception) { connectionInUse.set(false); throw failure }
    }

    private fun begin(r: Recording, request: StreamRequest, extension: String, completeOnEof: Boolean = false) {
        val file = r.path?.let(::File) ?: File(dir, "${PcRecordingRules.fileBase(r.channelName, r.title, r.startAt)}-${r.id}.$extension")
        ends[r.id] = r.endAt
        db.updateRecording(r.id, RecordingStatus.RECORDING, path = file.absolutePath)
        _active.value = r.copy(status = RecordingStatus.RECORDING, path = file.absolutePath)
        changed()
        job = scope.launch(Dispatchers.IO) {
            var error: String? = null
            var fatal = false
            var deadline: Job? = null
            val owner = currentCoroutineContext()[Job]!!
            try {
                keepAlive(true)
                beforeCapture()
                deadline = launch(Dispatchers.IO) {
                    while (isActive) {
                        val left = (ends[r.id] ?: r.endAt) - System.currentTimeMillis()
                        if (left <= 0) { finalStatuses.putIfAbsent(r.id, RecordingStatus.COMPLETED); activeCall?.cancel(); owner.cancel(); return@launch }
                        delay(left.coerceAtMost(1000))
                    }
                }
                java.io.FileOutputStream(file, true).buffered().use { out ->
                    var published = 0L
                    var checkpoint = 0L
                    var bytes = file.length()
                    var validator: String? = null
                    while (isActive && !fatal && System.currentTimeMillis() < (ends[r.id] ?: r.endAt)) {
                        var completedFile = false
                        try {
                            val call = http.newCall(Request.Builder().url(request.url).apply {
                                request.userAgent?.let { header("User-Agent", it) }
                                request.referrer?.let { header("Referer", it) }
                                // Resuming a file must never append a fresh copy of its beginning.
                                if (completeOnEof) {
                                    header("Accept-Encoding", "identity")
                                    if (bytes > 0) {
                                        header("Range", "bytes=$bytes-")
                                        validator?.let { header("If-Range", it) }
                                    }
                                }
                            }.build())
                            activeCall = call
                            call.execute().use { response ->
                                val type = response.header("Content-Type").orEmpty().lowercase()
                                when {
                                    type.contains("mpegurl") || type.contains("text/html") -> { fatal = true; error = "This response is not a direct video stream" }
                                    completeOnEof && bytes > 0 &&
                                        (response.code != 206 || !response.header("Content-Range").orEmpty().startsWith("bytes $bytes-")) -> {
                                        fatal = true
                                        error = "The provider cannot safely resume this download"
                                    }
                                    response.code in 400..499 -> { fatal = true; error = "Provider refused the capture (HTTP ${response.code})" }
                                    !response.isSuccessful -> error = "Provider stream unavailable (HTTP ${response.code})"
                                    else -> {
                                        if (completeOnEof && validator == null) {
                                            validator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                                                ?: response.header("Last-Modified")
                                        }
                                        val input = response.body?.byteStream() ?: throw IOException("Empty body")
                                        val buffer = ByteArray(64 * 1024)
                                        while (isActive && System.currentTimeMillis() < (ends[r.id] ?: r.endAt)) {
                                            val size = input.read(buffer)
                                            if (size < 0) { completedFile = completeOnEof; break }
                                            if (file.usableSpace < STOP_SPACE) { fatal = true; error = "Recording stopped because storage is full"; break }
                                            out.write(buffer, 0, size)
                                            bytes += size
                                            val now = System.currentTimeMillis()
                                            if (now - published >= 1000) {
                                                out.flush()
                                                val end = ends[r.id] ?: r.endAt
                                                _active.value = _active.value?.copy(bytes = bytes, endAt = end)
                                                _progress.value = RecordingProgress(r.id, bytes, r.startAt, end, bytes * 1000 / (now - r.startAt).coerceAtLeast(1000))
                                                published = now
                                            }
                                            if (now - checkpoint >= 5000) { db.updateRecording(r.id, RecordingStatus.RECORDING, bytes = bytes); version.value++; checkpoint = now }
                                        }
                                    }
                                }
                            }
                            if (completedFile) break
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: IOException) { if (isActive) error = "Provider connection interrupted" }
                        if (isActive && !fatal) delay(3_000)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fatal = true; error = "Recording could not continue; check storage and connection" }
            finally {
                deadline?.cancel()
                val interrupted = !currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    val bytes = file.length()
                    val requested = finalStatuses.remove(r.id)
                    val status = when {
                        requested == RecordingStatus.CANCELLED -> RecordingStatus.CANCELLED
                        fatal || bytes == 0L -> RecordingStatus.FAILED
                        requested == RecordingStatus.COMPLETED || !interrupted -> RecordingStatus.COMPLETED
                        else -> RecordingStatus.FAILED
                    }
                    if (deleted.remove(r.id)) { file.delete(); db.deleteRecording(r.id) }
                    else db.updateRecording(r.id, status, bytes = bytes, error = if (status == RecordingStatus.COMPLETED) null else error ?: if (status == RecordingStatus.CANCELLED) "Cancelled" else "Capture interrupted")
                    ends.remove(r.id)
                    activeCall = null
                    _active.value = null
                    _progress.value = null
                    connectionInUse.set(false)
                    runCatching { keepAlive(false) }
                    changed()
                }
            }
        }
    }

    private fun allRecordings() = db.playlists().flatMap { db.recordings(it.id) }
    private fun allRules(): List<RecordingRule> = runCatching { json.decodeFromString<List<RecordingRule>>(db.setting(RULES).orEmpty()) }.getOrDefault(emptyList())
    private fun saveRules(rules: List<RecordingRule>) { db.putSetting(RULES, json.encodeToString(rules)) }
    private fun links(): Map<String, String> = runCatching { json.decodeFromString<Map<String, String>>(db.setting(LINKS).orEmpty()) }.getOrDefault(emptyMap())
    private fun saveLinks(links: Map<String, String>) { db.putSetting(LINKS, json.encodeToString(links)) }
    @Synchronized private fun materializeRules() {
        val now = System.currentTimeMillis()
        val horizon = now + 2 * 24 * 3600_000L
        var rulesChanged = false
        val updated = allRules().map { original ->
            var rule = original
            var attempts = 0
            while (attempts++ < 370) {
                val start = rule.nextStart()
                if (start > horizon) break
                val end = start + rule.durationMs
                val p = db.playlists().firstOrNull { it.id == rule.playlistId } ?: break
                val c = db.channelsByIds(p.id, listOf(rule.channelId)).firstOrNull() ?: break
                if (end > now) {
                    val existing = allRecordings()
                    val present = existing.any { it.channelId == rule.channelId && it.playlistId == rule.playlistId && it.startAt == start && it.endAt == end }
                    if (!present && PcRecordingRules.check(c.id, maxOf(start, now), end, existing, now) is PcRecordingRules.Verdict.Ok) {
                        val id = db.insertRecording(Recording(0, p.id, c.id, c.name, c.logo, rule.title, maxOf(start, now), end, RecordingStatus.SCHEDULED))
                        saveLinks(links() + (id.toString() to rule.id))
                    }
                }
                rule = rule.copy(lastScheduledStart = start)
                rulesChanged = true
            }
            rule
        }
        if (rulesChanged) { saveRules(updated); changed() }
    }
    private fun changed() {
        version.value++
        runCatching {
            val next = allRecordings().filter { it.status == RecordingStatus.SCHEDULED && it.startAt > System.currentTimeMillis() }.minOfOrNull { it.startAt }
            Platform.recordingScheduleWake(next)
        }
    }
}
