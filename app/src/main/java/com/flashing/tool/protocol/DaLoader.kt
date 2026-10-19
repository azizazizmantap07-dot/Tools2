package com.flashing.tool.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses MediaTek Download Agent binaries (legacy + "MTK_DA_v6" style headers).
 *
 * Penumbra / SP Flash Tool / mtkclient do **not** upload the entire .bin to BROM.
 * They extract the matching chip entry and upload **DA stage-1** only via SEND_DA,
 * then jump to its load address. Uploading the full file (header + all stages)
 * is a common reason step 4/7 fails after a successful handshake.
 *
 * Example (DAMT6789 / Helio G99, hwCode 0x1208):
 *   region0: offset 0xBC, len ~0x63D18, addr 0x00200000, sig_len 0x100
 *   region2: addr 0x40000000 (stage-2, sent later by DA protocol)
 */
data class DaStage(
    val payload: ByteArray,
    val loadAddress: Long,
    val sigLen: Int,
    val fileOffset: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DaStage) return false
        return loadAddress == other.loadAddress &&
            sigLen == other.sigLen &&
            fileOffset == other.fileOffset &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = loadAddress.hashCode()
        r = 31 * r + sigLen
        r = 31 * r + fileOffset
        r = 31 * r + payload.contentHashCode()
        return r
    }
}

data class DaParseResult(
    val versionLabel: String,
    val stages: List<DaStage>,
    /** Preferred stage-1 for BROM SEND_DA (lowest DRAM/SRAM addr, usually 0x200000). */
    val bromStage: DaStage,
)

object DaLoader {

    private val MAGIC_HEADER = "MTK_DOWNLOAD_AGENT".toByteArray(Charsets.US_ASCII)

    /**
     * Parse [raw] DA file. If the header is not recognised, falls back to treating
     * the whole file as a single stage at [fallbackAddress] (legacy behaviour).
     */
    fun parse(raw: ByteArray, preferredHwCode: Int? = null, fallbackAddress: Long = 0x200000L): DaParseResult {
        if (raw.size >= MAGIC_HEADER.size && raw.copyOfRange(0, MAGIC_HEADER.size).contentEquals(MAGIC_HEADER)) {
            parseV6(raw, preferredHwCode)?.let { return it }
        }
        // Raw binary without header — upload as-is
        val stage = DaStage(raw, fallbackAddress, sigLen = 0, fileOffset = 0)
        return DaParseResult("raw", listOf(stage), stage)
    }

    private fun parseV6(raw: ByteArray, preferredHwCode: Int?): DaParseResult? {
        // Version string often sits at 0x20: "MTK_DA_v6_…"
        val ver = raw.copyOfRange(0x20, minOf(0x60, raw.size))
            .toString(Charsets.US_ASCII)
            .trim { it <= ' ' || it == '\u0000' }

        // Scan for entry magic 0xDA 0xDA followed by hw_code (LE u16)
        var entryOff = -1
        var matchedHw = 0
        var i = 0x40
        while (i + 20 < raw.size && i < 0x200) {
            if (raw[i] == 0xDA.toByte() && raw[i + 1] == 0xDA.toByte()) {
                val hw = u16LE(raw, i + 2)
                if (preferredHwCode == null || hw == preferredHwCode || entryOff < 0) {
                    entryOff = i
                    matchedHw = hw
                    if (preferredHwCode != null && hw == preferredHwCode) break
                }
            }
            i++
        }
        if (entryOff < 0) return null

        // Layout after magic+hw (observed on MTK_DA_v6 for MT6789):
        //   +0  DA DA
        //   +2  hw_code u16
        //   +4  hw_sub / hw_ver / sw_ver (6 bytes) + padding
        //   region_count u16 at entry+0x12 (0x7e when entry=0x6c)
        val regionCountOff = entryOff + 0x12
        if (regionCountOff + 2 > raw.size) return null
        val regionCount = u16LE(raw, regionCountOff).coerceIn(1, 8)
        var off = regionCountOff + 2
        val stages = mutableListOf<DaStage>()
        repeat(regionCount) {
            if (off + 20 > raw.size) return@repeat
            val buf = u32LE(raw, off)
            val len = u32LE(raw, off + 4)
            val addr = u32LE(raw, off + 8).toLong() and 0xFFFFFFFFL
            val startOff = u32LE(raw, off + 12)
            val sigLen = u32LE(raw, off + 16)
            off += 20
            if (buf < 0 || len <= 0 || buf + len > raw.size) return@repeat
            val payload = raw.copyOfRange(buf, buf + len)
            stages.add(DaStage(payload, addr, sigLen.coerceAtLeast(0), buf))
        }
        if (stages.isEmpty()) return null

        // BROM stage = lowest load address among stages that look like SRAM/DRAM low mem
        val bromStage = stages.minByOrNull { it.loadAddress } ?: stages.first()
        return DaParseResult(
            versionLabel = ver.ifBlank { "MTK_DA_v6" } + " hw=0x%04X".format(matchedHw),
            stages = stages,
            bromStage = bromStage,
        )
    }

    private fun u16LE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32LE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
}
