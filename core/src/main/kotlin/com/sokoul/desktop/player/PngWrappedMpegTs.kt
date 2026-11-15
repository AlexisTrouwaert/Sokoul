// Adapted from Movix TV, CC BY-NC 4.0; see THIRD_PARTY.md.
package com.sokoul.desktop.player

import java.nio.charset.StandardCharsets


object PngWrappedMpegTs {
    const val MAX_ENVELOPE_BYTES = 4_096
    const val TS_PACKET_BYTES = 188
    const val REQUIRED_TS_PACKETS = 3
    const val MAX_PROBE_BYTES = MAX_ENVELOPE_BYTES + TS_PACKET_BYTES * REQUIRED_TS_PACKETS

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    
    fun startsLikePng(bytes: ByteArray, length: Int): Boolean =
        length >= signature.size && signature.indices.all { bytes[it] == signature[it] }

    
    fun payloadOffset(bytes: ByteArray, length: Int = bytes.size): Int? {
        val available = minOf(length, bytes.size)
        if (!startsLikePng(bytes, available)) return null
        var cursor = signature.size
        var sawIhdr = false
        while (cursor + 12 <= available && cursor + 12 <= MAX_ENVELOPE_BYTES) {
            val dataLength = readUInt32(bytes, cursor) ?: return null
            if (dataLength > MAX_ENVELOPE_BYTES.toLong()) return null
            val chunkEndLong = cursor.toLong() + 12L + dataLength
            if (chunkEndLong > available || chunkEndLong > MAX_ENVELOPE_BYTES) return null
            val chunkEnd = chunkEndLong.toInt()
            val type = String(bytes, cursor + 4, 4, StandardCharsets.US_ASCII)
            if (!sawIhdr) {
                if (type != "IHDR" || dataLength != 13L) return null
                sawIhdr = true
            }
            if (type == "IEND") {
                if (dataLength != 0L) return null
                if (available - chunkEnd < TS_PACKET_BYTES * REQUIRED_TS_PACKETS) return null
                repeat(REQUIRED_TS_PACKETS) { packet ->
                    if ((bytes[chunkEnd + packet * TS_PACKET_BYTES].toInt() and 0xff) != 0x47) return null
                }
                return chunkEnd
            }
            cursor = chunkEnd
        }
        return null
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (0 until 4).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 0xffL) }
    }
}
