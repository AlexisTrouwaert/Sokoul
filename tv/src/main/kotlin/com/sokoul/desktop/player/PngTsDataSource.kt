// Adapted from Movix TV, CC BY-NC 4.0; see THIRD_PARTY.md.
package com.sokoul.desktop.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener


class PngTsReader(private val upstream: (ByteArray, Int, Int) -> Int) {
    private var prefix = ByteArray(0)
    private var prefixPos = 0
    private var prefixEnd = 0

    
    fun probe(): Int {
        val buf = ByteArray(PngWrappedMpegTs.MAX_PROBE_BYTES)
        var count = fill(buf, 0, SIGNATURE_BYTES)
        var offset = 0
        if (PngWrappedMpegTs.startsLikePng(buf, count)) {
            count = fill(buf, count, buf.size)
            offset = PngWrappedMpegTs.payloadOffset(buf, count) ?: 0
        }
        prefix = buf
        prefixPos = offset
        prefixEnd = count
        return offset
    }

    private fun fill(buf: ByteArray, from: Int, until: Int): Int {
        var count = from
        while (count < until) {
            val n = upstream(buf, count, until - count)
            if (n <= 0) break
            count += n
        }
        return count
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (prefixPos < prefixEnd) {
            val n = minOf(length, prefixEnd - prefixPos)
            System.arraycopy(prefix, prefixPos, buffer, offset, n)
            prefixPos += n
            return n
        }
        return upstream(buffer, offset, length)
    }

    private companion object {
        const val SIGNATURE_BYTES = 8
    }
}


class PngTsDataSource(private val upstream: DataSource) : DataSource {
    private var reader: PngTsReader? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        if (dataSpec.position != 0L) {
            reader = null
            return length
        }
        val r = PngTsReader(upstream::read)
        val skipped = r.probe()
        reader = r
        return if (length == C.LENGTH_UNSET.toLong() || skipped == 0) length else length - skipped
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        reader?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        reader = null
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = PngTsDataSource(upstream.createDataSource())
    }
}
