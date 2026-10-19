package com.flashing.tool.protocol

/**
 * MTK BROM/DA protocol framing is big-endian for multi-byte values on the wire,
 * which is the opposite of typical little-endian ARM memory layout — a classic
 * gotcha when porting from a PC tool. Double-check this against a packet capture
 * from a working session if commands aren't being acknowledged correctly.
 */
object ByteUtil {

    fun u32ToBytesBE(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    fun u16ToBytesBE(value: Int): ByteArray = byteArrayOf(
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    fun bytesToU32BE(bytes: ByteArray, offset: Int = 0): Long {
        require(bytes.size >= offset + 4) { "Need 4 bytes for u32" }
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    fun bytesToU16BE(bytes: ByteArray, offset: Int = 0): Int {
        require(bytes.size >= offset + 2) { "Need 2 bytes for u16" }
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
}
