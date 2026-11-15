package com.sokoul.desktop.data

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Read-only, tokenized loopback transport; media credentials never enter its URLs. */
class RecordingStreamServer : AutoCloseable {
    private data class Entry(val file: File, val offset: Long, val capturing: () -> Boolean)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val clients = Semaphore(4)
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val thread = Thread({
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            if (!clients.tryAcquire()) { client.close(); continue }
            Thread({ try { serve(client) } finally { clients.release() } }, "Sokoul-recording-reader").apply { isDaemon = true; start() }
        }
    }, "Sokoul-recording-loopback").apply { isDaemon = true; start() }

    fun url(file: File, offset: Long = 0, capturing: () -> Boolean): String {
        if (entries.size >= 32) entries.keys.firstOrNull()?.let(entries::remove)
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(file, offset.coerceAtLeast(0), capturing)
        return "http://127.0.0.1:${socket.localPort}/$token/capture.ts"
    }

    private fun serve(client: Socket) = client.use { connection ->
        runCatching {
            connection.soTimeout = 2500
            val input = connection.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val request = input.readLine()?.takeIf { it.length <= 2048 }?.split(' ') ?: return@runCatching
            if (request.size < 2 || request[0] !in setOf("GET", "HEAD")) return@runCatching
            val token = request[1].substringBefore('?').split('/').getOrNull(1) ?: return@runCatching
            val entry = entries[token]
            val output = connection.getOutputStream().buffered()
            if (entry == null || !entry.file.isFile) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)); output.flush(); return@runCatching
            }
            var requestBytes = 0
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: return@runCatching
                requestBytes += line.length
                if (requestBytes > 8192) return@runCatching
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
            }
            val growing = entry.capturing()
            val length = (entry.file.length() - entry.offset).coerceAtLeast(0)
            val requested = range?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it) }
            var start = 0L
            var end = length - 1
            if (!growing && range != null) {
                val first = requested?.groupValues?.get(1).orEmpty()
                val last = requested?.groupValues?.get(2).orEmpty()
                if (first.isEmpty()) {
                    val suffix = last.toLongOrNull() ?: 0
                    start = if (suffix > 0) (length - suffix).coerceAtLeast(0) else length
                } else {
                    start = first.toLongOrNull() ?: length
                    if (last.isNotEmpty()) end = last.toLongOrNull()?.coerceAtMost(end) ?: -1
                }
                if (requested == null || start >= length || start > end) {
                    output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                    return@runCatching
                }
            }
            val headers = if (growing) {
                "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nTransfer-Encoding: chunked\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
            } else {
                val partial = range != null
                "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: video/mp2t\r\nContent-Length: ${(end - start + 1).coerceAtLeast(0)}\r\nAccept-Ranges: bytes\r\n" +
                    (if (partial) "Content-Range: bytes $start-$end/$length\r\n" else "") + "Connection: close\r\nCache-Control: no-store\r\n\r\n"
            }
            output.write(headers.toByteArray(StandardCharsets.US_ASCII)); output.flush()
            if (request[0] == "HEAD") return@runCatching
            RandomAccessFile(entry.file, "r").use { video ->
                video.seek(entry.offset + start)
                val buffer = ByteArray(32 * 1024)
                var remaining = (end - start + 1).coerceAtLeast(0)
                while (!connection.isClosed) {
                    if (!growing && remaining == 0L) break
                    val size = video.read(buffer, 0, if (growing) buffer.size else minOf(buffer.size.toLong(), remaining).toInt())
                    if (size < 0) {
                        if (entry.capturing()) { Thread.sleep(150); continue }
                        // The capture may have flushed its final bytes after this read saw EOF.
                        if (growing && video.filePointer < video.length()) continue
                        break
                    }
                    if (growing) output.write("${size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write(buffer, 0, size)
                    if (!growing) remaining -= size
                    if (growing) output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }
            }
            if (growing) { output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)); output.flush() }
        }
    }

    override fun close() { entries.clear(); socket.close(); thread.interrupt() }
}
