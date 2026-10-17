package com.flashing.tool.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host-side AVB vbmeta flag patching, equivalent to platform-tools:
 *   fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
 *
 * Flags field is a big-endian uint32 at offset 0x78 in the vbmeta image header
 * (libavb AvbVBMetaImageHeader). Bit0 = HASHTREE_DISABLED (verity),
 * bit1 = VERIFICATION_DISABLED.
 */
object VbmetaPatcher {
    private const val AVB_MAGIC_0 = 'A'.code
    private const val FLAGS_OFFSET = 0x78
    private const val FLAG_HASHTREE_DISABLED = 1
    private const val FLAG_VERIFICATION_DISABLED = 2

    fun isVbmetaImage(data: ByteArray): Boolean {
        if (data.size < FLAGS_OFFSET + 4) return false
        return data[0] == 'A'.code.toByte() &&
            data[1] == 'V'.code.toByte() &&
            data[2] == 'B'.code.toByte() &&
            data[3] == '0'.code.toByte()
    }

    /**
     * Returns a copy of [data] with the requested flags set. If the buffer is
     * not a vbmeta image, returns the original array unchanged.
     */
    fun applyFlags(
        data: ByteArray,
        disableVerity: Boolean,
        disableVerification: Boolean,
    ): ByteArray {
        if (!disableVerity && !disableVerification) return data
        if (!isVbmetaImage(data)) return data
        val out = data.copyOf()
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        var flags = bb.getInt(FLAGS_OFFSET)
        if (disableVerity) flags = flags or FLAG_HASHTREE_DISABLED
        if (disableVerification) flags = flags or FLAG_VERIFICATION_DISABLED
        bb.putInt(FLAGS_OFFSET, flags)
        return out
    }
}
