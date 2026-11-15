package com.sokoul.pc

import com.sokoul.desktop.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRecordingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `programme slot records more than an hour through EPG end plus margin`() {
        val now = 1_800_000_000_000L
        val programmeEnd = now + 3 * 60 * 60_000L
        val (start, end) = PcRecordingRules.slot(now - 30 * 60_000L, programmeEnd, now)
        assertEquals(now, start)
        assertEquals(programmeEnd + 5 * 60_000L, end)
        assertTrue(PcRecordingRules.check("1", start, end, emptyList(), now) is PcRecordingRules.Verdict.Ok)
    }

    @Test fun `stop saves received transport stream and rejects a second recording`() = runBlocking {
        exercise("video/mp2t", "G".repeat(188000), false)
    }

    @Test fun `HLS manifest is refused instead of being saved as video`() = runBlocking {
        exercise("application/vnd.apple.mpegurl", "#EXTM3U\n", true)
    }

    @Test fun `interrupted download resumes from its received bytes`() = runBlocking {
        exerciseDownloadResume(honoursRange = true)
    }

    @Test fun `download refuses a full response to a resume request`() = runBlocking {
        exerciseDownloadResume(honoursRange = false)
    }

    @Test fun `saved recording serves bounded suffix and unsatisfiable ranges`() {
        val file = temp.newFile("saved.ts").apply { writeText("0123456789") }
        RecordingStreamServer().use { server ->
            val url = server.url(file, capturing = { false })
            val client = okhttp3.OkHttpClient()
            fun read(range: String, code: Int, expected: String, contentRange: String) {
                client.newCall(okhttp3.Request.Builder().url(url).header("Range", range).build()).execute().use { response ->
                    assertEquals(code, response.code)
                    assertEquals(contentRange, response.header("Content-Range"))
                    assertEquals(expected, response.body!!.string())
                }
            }
            read("bytes=0-2", 206, "012", "bytes 0-2/10")
            read("bytes=3-", 206, "3456789", "bytes 3-9/10")
            read("bytes=-3", 206, "789", "bytes 7-9/10")
            read("bytes=10-", 416, "", "bytes */10")
            read("bytes=7-2", 416, "", "bytes */10")
            read("bytes=0-2,5-7", 416, "", "bytes */10")
        }
    }

    @Test fun `active capture continues beyond its first EOF without another provider request`() {
        val file = temp.newFile("growing.ts").apply { writeText("01234") }
        val capturing = java.util.concurrent.atomic.AtomicBoolean(true)
        RecordingStreamServer().use { server ->
            val url = server.url(file, capturing = capturing::get)
            okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                assertEquals("chunked", response.header("Transfer-Encoding"))
                val input = response.body!!.byteStream()
                val firstBytes = ByteArray(5)
                var received = 0
                while (received < firstBytes.size) {
                    val count = input.read(firstBytes, received, firstBytes.size - received)
                    assertTrue("Capture ended before its initial bytes", count > 0)
                    received += count
                }
                assertEquals("01234", firstBytes.toString(Charsets.UTF_8))
                file.appendText("56789")
                capturing.set(false)
                assertEquals("56789", input.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    @Test fun `local recording playback retains source protection and fails closed for kids without provenance`() {
        DesktopDb(temp.newFile("recording-policy.db")).use { db ->
            val pid = db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, "https://provider.test/list", null, null, null, null))
            val channel = Channel(pid, "one", 1, "Family channel", null, "family", null, "one", "https://provider.test/live", null, null, 0)
            db.replaceLive(pid, listOf(Category(pid, "family", "Family", 0)), listOf(channel))
            val organization = com.sokoul.pc.live.TvOrganization(db, { db.playlists().single() })
            val item = Recording(1, pid, channel.id, channel.name, null, "Programme", 1, 2, RecordingStatus.COMPLETED)
            fun allowed(recording: Recording = item, kids: Boolean = false) = com.sokoul.pc.live.canPlayRecording(db, recording, kids)
            assertTrue(allowed(kids = true))
            assertTrue(organization.setPin("4826"))
            assertTrue(organization.setGroupLocked("family", true))
            organization.lockSession()
            assertFalse(allowed())
            assertEquals(com.sokoul.pc.live.TvOrganization.UnlockResult.Unlocked, organization.unlockPin("4826"))
            assertTrue(allowed())
            assertFalse(allowed(kids = true))
            assertTrue(organization.setGroupHidden("family", true))
            assertFalse(allowed())
            assertFalse(allowed(item.copy(channelId = "missing"), kids = true))
            assertFalse(allowed(item.copy(channelId = "download:opaque"), kids = true))
            assertFalse(allowed(item.copy(title = "Adult programme"), kids = true))
        }
    }

    private suspend fun exerciseDownloadResume(honoursRange: Boolean) {
        val content = "0123456789abcdef".repeat(32768)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")
                    if (range == null) return MockResponse().setHeader("Content-Type", "video/mp4")
                        .setHeader("ETag", "\"test-video\"").setBody(content)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    assertEquals("identity", request.getHeader("Accept-Encoding"))
                    assertEquals("\"test-video\"", request.getHeader("If-Range"))
                    val offset = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    assertTrue(offset in 1 until content.length)
                    return if (honoursRange) MockResponse().setResponseCode(206)
                        .setHeader("Content-Type", "video/mp4")
                        .setHeader("Content-Range", "bytes $offset-${content.lastIndex}/${content.length}")
                        .setBody(content.substring(offset))
                    else MockResponse().setHeader("Content-Type", "video/mp4").setBody(content)
                }
            }
            DesktopDb(temp.newFile("download.db")).use { db ->
                db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, server.url("/list").toString(), null, null, null, null))
                val playlist = db.playlists().single()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val recorder = DesktopRecorder(db, DesktopSync(db), scope, temp.newFolder("downloads"))
                    val request = com.sokoul.desktop.player.StreamRequest(server.url("/video.mp4").toString(), null, null, "Test", live = false)
                    assertTrue(recorder.saveStream(playlist, request, "Test", "mp4") is PcRecordingRules.Verdict.Ok)
                    withTimeout(15000) { while (recorder.active.value != null) delay(20) }
                    val saved = db.recordings(playlist.id).single()
                    val file = java.io.File(saved.path!!)
                    assertEquals(2, server.requestCount)
                    if (honoursRange) {
                        assertEquals(RecordingStatus.COMPLETED, saved.status)
                        assertEquals(content, file.readText())
                    } else {
                        assertEquals(RecordingStatus.FAILED, saved.status)
                        assertTrue(file.length() in 1 until content.length.toLong())
                        assertTrue(content.startsWith(file.readText()))
                    }
                } finally { scope.cancel() }
            }
        }
    }

    private suspend fun exercise(type: String, body: String, rejected: Boolean) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", type).setBody(body))
            DesktopDb(temp.newFile("record.db")).use { db ->
                db.upsertPlaylist(Playlist(0, "Test", PlaylistType.M3U, server.url("/list").toString(), null, null, null, null))
                val playlist = db.playlists().single()
                val channel = Channel(playlist.id, "1", 1, "Test", null, "test", null, "test", server.url("/video.ts").toString(), null, null, 0)
                db.replaceLive(playlist.id, listOf(Category(playlist.id, "test", "Test", 0)), listOf(channel))
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val recorder = DesktopRecorder(db, DesktopSync(db), scope, temp.newFolder("videos"))
                    val now = System.currentTimeMillis()
                    assertTrue(recorder.schedule(playlist, channel, "Test", now, now + 60000) is PcRecordingRules.Verdict.Ok)
                    assertTrue(recorder.schedule(playlist, channel, "Overlap", now, now + 60000) is PcRecordingRules.Verdict.Refused)
                    withTimeout(8000) {
                        while (db.recordings(playlist.id).single().status == RecordingStatus.SCHEDULED) delay(20)
                        if (!rejected) {
                            while (recorder.dir.listFiles().orEmpty().none { it.length() > 0 }) delay(20)
                            recorder.cancel(db.recordings(playlist.id).single())
                        }
                        while (recorder.active.value != null) delay(20)
                    }
                    val saved = db.recordings(playlist.id).single()
                    if (rejected) {
                        assertEquals(RecordingStatus.FAILED, saved.status)
                        assertEquals(0L, saved.bytes)
                    } else {
                        assertEquals(RecordingStatus.CANCELLED, saved.status)
                        assertTrue(saved.bytes > 0)
                        val file = java.io.File(saved.path!!)
                        assertEquals(saved.bytes, file.length())
                        assertTrue(body.startsWith(file.readText()))
                    }
                } finally { scope.cancel() }
            }
        }
    }
}
