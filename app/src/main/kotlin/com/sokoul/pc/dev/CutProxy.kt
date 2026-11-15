package com.sokoul.pc.dev

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Live check of a stream that dies during a film, only with SOKOUL_FAULTS: forwards a real link to libVLC on this PC alone,
 * then cuts the connection after [budgetBytes] and refuses to serve again. It is how to see what the real player does.
 */
class CutProxy(private val target: String, private val budgetBytes: Long, private val refuseWith: Int = 503) {
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val http = OkHttpClient()
    @Volatile private var served = 0L

    val url: String get() = "http://127.0.0.1:${server.localPort}/stream.mkv"

    init {
        thread(isDaemon = true, name = "sokoul-cut-proxy") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { socket.use(::serve) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val out = socket.getOutputStream()
        if (served >= budgetBytes) {
            out.write("HTTP/1.1 $refuseWith Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val request = Request.Builder().url(target).apply { range?.let { header("Range", it) } }.build()
        http.newCall(request).execute().use { r ->
            val head = buildString {
                append("HTTP/1.1 ${r.code} OK\r\nConnection: close\r\n")
                listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges").forEach { h -> r.header(h)?.let { append("$h: $it\r\n") } }
                append("\r\n")
            }
            out.write(head.toByteArray())
            val input = r.body?.byteStream() ?: return
            val buffer = ByteArray(64 * 1024)
            while (served < budgetBytes) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                served += n
            }
        }
    }
}
