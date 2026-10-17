package com.flashing.tool.protocol

/**
 * MediaTek BROM protocol command bytes.
 *
 * These are the single-byte command codes the BootROM understands once a device
 * is connected in BROM mode (power off, hold Vol Up or Vol Down, plug USB). This
 * is a thin, low-level protocol: host sends one command byte, BROM replies, then
 * host/BROM exchange whatever parameters that command needs.
 *
 * This command set is documented across multiple independent public sources
 * (MediaTek's own leaked/published SoC startup documentation, and years of
 * community reverse-engineering write-ups on the MTK BROM protocol — see
 * tinyhack.com's BROM exploit dissection and similar). It is NOT copied from
 * any single tool's source code — it reflects the protocol itself, which is
 * shared by every tool that talks to MTK BootROM (SP Flash Tool, mtkclient,
 * Penumbra, Rafflink, etc. all target the same wire protocol because the
 * BootROM on the chip doesn't change per-tool).
 *
 * VERIFY ON YOUR DEVICE: exact supported commands vary by BROM version
 * (legacy vs "v2"/Dimensity-era secure BROM). Treat this as a starting point,
 * not gospel — log raw bytes during a real handshake and compare.
 */
object BromCommands {
    // --- Basic read/write to SRAM, used during early handshake and for staging payloads ---
    const val CMD_READ16: Byte = 0xD0.toByte()
    const val CMD_READ32: Byte = 0xD1.toByte()
    const val CMD_WRITE16: Byte = 0xD2.toByte()
    const val CMD_WRITE32: Byte = 0xD4.toByte()

    // --- Identification ---
    const val CMD_GET_HW_CODE: Byte = 0xFD.toByte()
    const val CMD_GET_HW_SW_VER: Byte = 0xFC.toByte()
    const val CMD_GET_VERSION: Byte = 0x73.toByte()

    // --- DA (Download Agent) handoff ---
    const val CMD_SEND_DA: Byte = 0xD7.toByte()
    const val CMD_JUMP_DA: Byte = 0xD5.toByte()
    const val CMD_SEND_DA64: Byte = 0xDE.toByte()
    const val CMD_JUMP_DA64: Byte = 0xDF.toByte()

    // --- UART baud rate negotiation (sometimes required before continuing) ---
    const val CMD_UART1_LOG_EN: Byte = 0xDB.toByte()

    /** BROM sends this single byte back to acknowledge/echo a received command. */
    const val ACK_MAGIC: Byte = 0x5A.toByte()

    /**
     * Canonical BROM / Preloader handshake sequence used by every major tool
     * (mtkclient, SP Flash Tool, etc.). Host sends each byte; device must reply
     * with its bitwise complement:
     *   0xA0 → 0x5F
     *   0x0A → 0xF5
     *   0x50 → 0xAF
     *   0x05 → 0xFA
     */
    val SYNC_SEQUENCE: ByteArray = byteArrayOf(
        0xA0.toByte(),
        0x0A.toByte(),
        0x50.toByte(),
        0x05.toByte(),
    )
}
