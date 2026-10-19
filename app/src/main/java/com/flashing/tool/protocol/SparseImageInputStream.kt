package com.flashing.tool.protocol

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Expands Android sparse images (magic 0xed26ff3a) into a sequential raw stream
 * suitable for fastboot download/flash — matching what platform-tools fastboot
 * does before sending data to the device.
 *
 * Chunk types: RAW (write bytes), FILL (repeat 4-byte pattern), DONT_CARE (zeros
 * or skip — we emit zeros so total length matches blk_sz * total_blks).
 */
class SparseImageInputStream(
    private val source: InputStream,
) : InputStream() {

    private var expandedTotal: Long = -1
    private var expandedPosition: Long = 0
    private var pending: ByteArray = ByteArray(0)
    private var pendingPos = 0
    private var headerParsed = false
    private var blkSize = 0
    private var remainingChunks = 0

    val isSparse: Boolean
        get() {
            ensureHeader()
            return expandedTotal >= 0
        }

    val rawSize: Long
        get() {
            ensureHeader()
            return if (expandedTotal >= 0) expandedTotal else -1
        }

    private fun ensureHeader() {
        if (headerParsed) return
        headerParsed = true
        source.mark(64)
        val hdr = ByteArray(28)
        val n = readFully(source, hdr, 0, 28)
        if (n < 28) {
            // Not sparse — treat as opaque; caller should use original stream
            expandedTotal = -1
            // Can't un-read without mark support; this class is only used when
            // magic already detected by peek.
            return
        }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        if (magic != SPARSE_MAGIC) {
            expandedTotal = -1
            return
        }
        val major = bb.short.toInt() and 0xFFFF
        val minor = bb.short.toInt() and 0xFFFF
        val fileHdrSz = bb.short.toInt() and 0xFFFF
        val chunkHdrSz = bb.short.toInt() and 0xFFFF
        blkSize = bb.int
        val totalBlks = bb.int
        val totalChunks = bb.int
        bb.int // checksum checksum, ignore
        if (fileHdrSz > 28) {
            skipFully(source, (fileHdrSz - 28).toLong())
        }
        remainingChunks = totalChunks
        expandedTotal = blkSize.toLong() * totalBlks.toLong()
        expandedPosition = 0
        // stash chunk_hdr expected size for validation
        this.chunkHdrSize = chunkHdrSz
        this.majorVersion = major
    }

    private var chunkHdrSize = 12
    private var majorVersion = 1

    override fun read(): Int {
        val b = ByteArray(1)
        val r = read(b, 0, 1)
        return if (r <= 0) -1 else (b[0].toInt() and 0xFF)
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        ensureHeader()
        if (expandedTotal < 0) return -1
        if (expandedPosition >= expandedTotal) return -1
        var written = 0
        while (written < len && expandedPosition < expandedTotal) {
            if (pendingPos >= pending.size) {
                if (!loadNextChunk()) break
            }
            val n = minOf(len - written, pending.size - pendingPos)
            System.arraycopy(pending, pendingPos, buf, off + written, n)
            pendingPos += n
            written += n
            expandedPosition += n
        }
        return if (written == 0) -1 else written
    }

    private fun loadNextChunk(): Boolean {
        if (remainingChunks <= 0) {
            pending = ByteArray(0)
            pendingPos = 0
            return false
        }
        val ch = ByteArray(chunkHdrSize.coerceAtLeast(12))
        if (readFully(source, ch, 0, ch.size) < ch.size) {
            throw IOException("Truncated sparse chunk header")
        }
        val bb = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
        val type = bb.short.toInt() and 0xFFFF
        bb.short // reserved
        val chunkSzBlocks = bb.int
        val totalSzBytes = bb.int
        val dataSize = totalSzBytes - chunkHdrSize
        val outSize = chunkSzBlocks.toLong() * blkSize
        remainingChunks--

        when (type) {
            CHUNK_RAW -> {
                if (dataSize.toLong() != outSize) {
                    throw IOException("RAW chunk size mismatch data=$dataSize out=$outSize")
                }
                pending = ByteArray(outSize.toInt())
                if (readFully(source, pending, 0, pending.size) < pending.size) {
                    throw IOException("Truncated RAW chunk data")
                }
                pendingPos = 0
            }
            CHUNK_FILL -> {
                val fill = ByteArray(4)
                if (readFully(source, fill, 0, 4) < 4) {
                    throw IOException("Truncated FILL pattern")
                }
                // skip extra if any
                if (dataSize > 4) skipFully(source, (dataSize - 4).toLong())
                pending = ByteArray(outSize.toInt())
                var i = 0
                while (i + 4 <= pending.size) {
                    System.arraycopy(fill, 0, pending, i, 4)
                    i += 4
                }
                pendingPos = 0
            }
            CHUNK_DONT_CARE -> {
                if (dataSize > 0) skipFully(source, dataSize.toLong())
                pending = ByteArray(outSize.toInt()) // zeros
                pendingPos = 0
            }
            CHUNK_CRC32 -> {
                if (dataSize > 0) skipFully(source, dataSize.toLong())
                pending = ByteArray(0)
                pendingPos = 0
                return loadNextChunk()
            }
            else -> throw IOException("Unknown sparse chunk type 0x${type.toString(16)}")
        }
        return pending.isNotEmpty() || expandedPosition < expandedTotal
    }

    override fun close() {
        source.close()
    }

    companion object {
        const val SPARSE_MAGIC = 0xed26ff3a.toInt()
        private const val CHUNK_RAW = 0xcac1
        private const val CHUNK_FILL = 0xcac2
        private const val CHUNK_DONT_CARE = 0xcac3
        private const val CHUNK_CRC32 = 0xcac4

        fun looksSparse(header: ByteArray): Boolean {
            if (header.size < 4) return false
            val m = (header[0].toInt() and 0xFF) or
                ((header[1].toInt() and 0xFF) shl 8) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 24)
            return m == SPARSE_MAGIC
        }

        private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
            var o = off
            var left = len
            while (left > 0) {
                val n = input.read(buf, o, left)
                if (n < 0) return o - off
                o += n
                left -= n
            }
            return len
        }

        private fun skipFully(input: InputStream, amount: Long) {
            var left = amount
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) {
                    if (input.read() < 0) throw IOException("Unexpected EOF while skipping")
                    left--
                } else {
                    left -= s
                }
            }
        }
    }
}
