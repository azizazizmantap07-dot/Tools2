package com.flashing.tool.protocol

import com.flashing.tool.usb.UsbTransport
import java.io.IOException

data class ChipIdentity(
    val hwCode: Int,
    val hwSubCode: Int,
    val hwVersion: Int,
    val swVersion: Int,
)

/**
 * Talks the BROM (BootROM) protocol over an already-open [UsbTransport].
 *
 * Responsibility boundary: this class only handles the BROM stage — identifying
 * the chip and handing off execution to a Download Agent (DA) binary. Once DA is
 * running, communication switches to a different, higher-level protocol handled
 * by [DaSession]. Treat BROM and DA as two separate conversations over the same
 * USB pipe, because that's how the real hardware treats them.
 *
 * STATUS: this implementation reflects the publicly-documented shape of the BROM
 * handshake (send command byte, read echo, exchange identification, send DA
 * payload in chunks, jump to it). It has NOT been validated against a live
 * Helio G99 device yet — you are expected to run this against your test device,
 * log every raw exchange (see [onRawExchange]), and correct any byte-sequence
 * mismatches you find. Treat the TODO markers as the places most likely to need
 * adjustment for your specific chipset/BROM version.
 */
class BromSession(
    private val transport: UsbTransport,
    /** Optional hook for logging every raw byte exchange — invaluable for debugging. */
    private val onRawExchange: ((direction: String, bytes: ByteArray) -> Unit)? = null,
) {
    private fun writeLogged(bytes: ByteArray) {
        onRawExchange?.invoke("OUT", bytes)
        transport.write(bytes)
    }

    private fun readLogged(length: Int): ByteArray {
        val data = transport.readExact(length)
        onRawExchange?.invoke("IN", data)
        return data
    }

    /**
     * Sends a single command byte and expects the BROM to echo it back.
     * Skips null padding (0x00) left in the bulk pipe after prior transfers
     * (log showed D7 answered with 00 instead of D7).
     */
    @Throws(IOException::class)
    private fun sendCommand(cmd: Byte) {
        try {
            transport.drain(maxBytes = 256, timeoutMs = 5)
        } catch (_: Exception) {
        }
        writeLogged(byteArrayOf(cmd))
        val deadline = System.currentTimeMillis() + 250
        var last = ByteArray(0)
        while (System.currentTimeMillis() < deadline) {
            val echo = try {
                transport.read(1, timeoutMs = 40)
            } catch (_: Exception) {
                ByteArray(0)
            }
            if (echo.isEmpty()) continue
            onRawExchange?.invoke("IN", echo)
            last = echo
            when {
                echo[0] == cmd -> return
                echo[0] == 0.toByte() -> continue
                else -> throw IOException(
                    "BROM did not echo command 0x%02X (got %s)".format(
                        cmd, ByteUtil.toHex(echo)
                    )
                )
            }
        }
        throw IOException(
            "BROM did not echo command 0x%02X (got %s)".format(
                cmd, if (last.isEmpty()) "timeout" else ByteUtil.toHex(last)
            )
        )
    }

    /**
     * MediaTek BROM/Preloader handshake (aligned with mtkclient Port.py).
     *
     * Sequence: host sends 0xA0 0x0A 0x50 0x05 one byte at a time.
     * Device reply modes (both accepted — different chips differ):
     *  - Classic / UART-style: bitwise complement (0x5F, 0xF5, 0xAF, 0xFA)
     *  - Some USB BROM (e.g. MT6768): echo same byte back
     *
     * Per-byte timeout is kept very short (~25–40 ms) because the BootROM
     * window is often only ~0.3–1 s on real hardware.
     */
    @Throws(IOException::class)
    fun sync(maxAttempts: Int = 200) {
        var lastError: Exception? = null
        val seq = BromCommands.SYNC_SEQUENCE

        try {
            transport.drain(maxBytes = 4096, timeoutMs = 15)
        } catch (_: Exception) {
        }

        // Alternate echo modes so we don't permanently fail on "same-byte" chips
        val modes = arrayOf("complement", "same")

        repeat(maxAttempts) { attempt ->
            val mode = modes[attempt % modes.size]
            try {
                if (attempt > 0) {
                    try {
                        transport.drain(maxBytes = 512, timeoutMs = 8)
                    } catch (_: Exception) {
                    }
                }
                var matched = 0
                while (matched < seq.size) {
                    val expected = seq[matched]
                    writeLogged(byteArrayOf(expected))
                    // mtkclient serial path uses ~20 ms; USB bulk needs a bit more
                    val resp = try {
                        transport.read(1, timeoutMs = 35)
                    } catch (_: Exception) {
                        ByteArray(0)
                    }
                    onRawExchange?.invoke("IN", resp)

                    val ok = if (resp.size == 1) {
                        when (mode) {
                            "complement" ->
                                resp[0] == (expected.toInt().inv() and 0xFF).toByte()
                            else ->
                                resp[0] == expected
                        }
                    } else {
                        false
                    }
                    if (ok) {
                        matched++
                    } else {
                        val want = if (mode == "complement") {
                            (expected.toInt().inv() and 0xFF)
                        } else {
                            expected.toInt() and 0xFF
                        }
                        throw IOException(
                            "BROM handshake mismatch ($mode) at byte $matched: " +
                                "sent 0x%02X, expected echo 0x%02X, got %s".format(
                                    expected, want, ByteUtil.toHex(resp)
                                )
                        )
                    }
                }
                onRawExchange?.invoke(
                    "INFO",
                    "BROM handshake OK mode=$mode attempt=${attempt + 1}".toByteArray()
                )
                return
            } catch (e: Exception) {
                lastError = e
                // Zero sleep for first ~12 attempts — pure sniper inside the window
                if (attempt >= 12) {
                    try {
                        Thread.sleep(8)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
        throw IOException(
            "BROM sync failed after $maxAttempts attempts. " +
                "Perangkat kemungkinan keluar dari BROM (timeout). " +
                "Colok ulang sambil tahan Volume; pastikan DA sudah dimuat.",
            lastError
        )
    }

    /**
     * Reads chip identification: hardware code, sub-code, version, software version.
     * This is typically the first real command sent after sync, and is useful to
     * confirm you're actually talking to the chip you expect (e.g. Helio G99 should
     * report a specific hwCode — log this and record it for your device).
     */
    @Throws(IOException::class)
    fun getChipIdentity(): ChipIdentity {
        sendCommand(BromCommands.CMD_GET_HW_CODE)
        val hwCodeBytes = readLogged(4)
        val hwCode = ((ByteUtil.bytesToU16BE(hwCodeBytes, 0))).toInt()
        val hwSubCode = ((ByteUtil.bytesToU16BE(hwCodeBytes, 2))).toInt()

        sendCommand(BromCommands.CMD_GET_HW_SW_VER)
        val verBytes = readLogged(4)
        val hwVersion = ByteUtil.bytesToU16BE(verBytes, 0)
        val swVersion = ByteUtil.bytesToU16BE(verBytes, 2)

        return ChipIdentity(hwCode, hwSubCode, hwVersion, swVersion)
    }

    /**
     * Uploads a Download Agent (DA) binary to the given SRAM/DRAM address and
     * instructs the BROM to jump to it. After this returns, the device is running
     * the DA, and all further communication must go through [DaSession] — the
     * command set changes completely.
     *
     * @param daPayload the DA binary bytes (loaded from the file the user supplies —
     *   this class never embeds or generates a DA; that file must come from a
     *   legitimate source such as the vendor's SP Flash Tool package).
     * @param loadAddress target SRAM/DRAM address to load the DA at. This is
     *   chipset-specific and normally comes from the DA file's own header/scatter
     *   metadata, not a constant — wire this up to your DA parser once you have one.
     */
    @Throws(IOException::class)
    fun sendAndJumpToDa(
        daPayload: ByteArray,
        loadAddress: Long,
        chunkSize: Int = 1024,
        sigLen: Int = 0,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ) {
        // Several variants — BROM builds differ (echo D7 vs null padding, 32 vs 64-bit,
        // with/without signature trailer). Try until one is accepted.
        val sig = sigLen.coerceAtLeast(0)
        // Prefer the header-accurate variant first; avoid hammering BROM with
        // DE/64-bit after a clear param reject (device often stops responding).
        val variants = buildList {
            add(SendDaVariant(use64 = false, data = daPayload, sig = sig, label = "D7+sig=$sig"))
            if (sig > 0 && daPayload.size > sig) {
                add(
                    SendDaVariant(
                        use64 = false,
                        data = daPayload.copyOf(daPayload.size - sig),
                        sig = 0,
                        label = "D7+strip_sig",
                    )
                )
            }
            add(SendDaVariant(use64 = false, data = daPayload, sig = 0, label = "D7+sig=0"))
        }

        var lastErr: Exception? = null
        for ((vi, v) in variants.withIndex()) {
            try {
                onRawExchange?.invoke(
                    "INFO",
                    "SEND_DA variant ${vi + 1}/${variants.size}: ${v.label} len=${v.data.size} @ 0x${"%08X".format(loadAddress)}".toByteArray()
                )
                try {
                    transport.drain(maxBytes = 512, timeoutMs = 8)
                } catch (_: Exception) {
                }
                sendDaParameters(v, loadAddress)
                uploadDaPayload(v.data, chunkSize, onProgress)
                jumpToDa(loadAddress, v.use64)
                return
            } catch (e: Exception) {
                lastErr = e
                onRawExchange?.invoke(
                    "INFO",
                    "SEND_DA variant failed: ${v.label} → ${e.message}".toByteArray()
                )
            }
        }
        throw lastErr ?: IOException("SEND_DA: semua varian gagal")
    }

    private data class SendDaVariant(
        val use64: Boolean,
        val data: ByteArray,
        val sig: Int,
        val label: String,
    )

    /**
     * Some BROM builds (seen on MT6789 / itel via OTG) echo each 32-bit parameter
     * back before returning the final 16-bit status. Log showed after D7:
     *   OUT 00 20 00 00  (addr) → IN 00 20  — was misread as status 0x0020.
     * We write addr/size/sig, consume optional 4-byte echoes, then read status.
     */
    private fun sendDaParameters(v: SendDaVariant, loadAddress: Long) {
        if (v.use64) {
            sendCommand(BromCommands.CMD_SEND_DA64)
            val addr = loadAddress
            val addrBytes = byteArrayOf(
                ((addr ushr 56) and 0xFF).toByte(),
                ((addr ushr 48) and 0xFF).toByte(),
                ((addr ushr 40) and 0xFF).toByte(),
                ((addr ushr 32) and 0xFF).toByte(),
                ((addr ushr 24) and 0xFF).toByte(),
                ((addr ushr 16) and 0xFF).toByte(),
                ((addr ushr 8) and 0xFF).toByte(),
                (addr and 0xFF).toByte(),
            )
            writeLogged(addrBytes)
            consumeParamEcho(addrBytes, "addr64")
        } else {
            sendCommand(BromCommands.CMD_SEND_DA)
            val addrBytes = ByteUtil.u32ToBytesBE(loadAddress.toInt())
            writeLogged(addrBytes)
            consumeParamEcho(addrBytes, "addr")
        }
        val sizeBytes = ByteUtil.u32ToBytesBE(v.data.size)
        writeLogged(sizeBytes)
        consumeParamEcho(sizeBytes, "size")

        val sigBytes = ByteUtil.u32ToBytesBE(v.sig)
        writeLogged(sigBytes)
        consumeParamEcho(sigBytes, "sig")

        val statusBefore = readLogged(2)
        val statusWord = if (statusBefore.size >= 2) {
            ((statusBefore[0].toInt() and 0xFF) shl 8) or (statusBefore[1].toInt() and 0xFF)
        } else if (statusBefore.size == 1) {
            statusBefore[0].toInt() and 0xFF
        } else {
            -1
        }
        val statusOk = statusWord == 0 ||
            (statusBefore.size == 1 && statusBefore[0] == BromCommands.ACK_MAGIC)
        if (!statusOk) {
            throw IOException(
                "BROM rejected SEND_DA params (status=0x${"%04X".format(statusWord)}, " +
                    "raw=${ByteUtil.toHex(statusBefore)}, ${v.label})"
            )
        }
        onRawExchange?.invoke(
            "INFO",
            "SEND_DA status OK (0x${"%04X".format(statusWord)}) ${v.label}".toByteArray()
        )
    }

    /**
     * Optional parameter echo: only accept a *full* match of [expected].
     * Anything else is pushed back so the following status word is not lost
     * (status 0x0000 starts with the same bytes as some sig_len values).
     */
    private fun consumeParamEcho(expected: ByteArray, tag: String) {
        val got = try {
            transport.read(expected.size, timeoutMs = 60)
        } catch (_: Exception) {
            ByteArray(0)
        }
        if (got.isEmpty()) return
        onRawExchange?.invoke("IN", got)
        if (got.size == expected.size && got.contentEquals(expected)) {
            onRawExchange?.invoke("INFO", "param echo $tag OK".toByteArray())
            return
        }
        // Not a clean echo — return bytes to the read queue for status handling
        transport.pushBack(got)
        onRawExchange?.invoke(
            "INFO",
            "param $tag no echo (queued ${got.size}B for status)".toByteArray()
        )
    }

    private fun uploadDaPayload(
        data: ByteArray,
        chunkSize: Int,
        onProgress: ((done: Long, total: Long) -> Unit)?,
    ) {
        var offset = 0
        val total = data.size.toLong()
        onProgress?.invoke(0L, total)
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            writeLogged(data.copyOfRange(offset, end))
            offset = end
            onProgress?.invoke(offset.toLong(), total)
        }
        val checksum = readLogged(2)
        onRawExchange?.invoke("INFO", "DA upload checksum: ${ByteUtil.toHex(checksum)}".toByteArray())
    }

    private fun jumpToDa(loadAddress: Long, use64: Boolean) {
        if (use64) {
            sendCommand(BromCommands.CMD_JUMP_DA64)
            val addr = loadAddress
            val addrBytes = byteArrayOf(
                ((addr ushr 56) and 0xFF).toByte(),
                ((addr ushr 48) and 0xFF).toByte(),
                ((addr ushr 40) and 0xFF).toByte(),
                ((addr ushr 32) and 0xFF).toByte(),
                ((addr ushr 24) and 0xFF).toByte(),
                ((addr ushr 16) and 0xFF).toByte(),
                ((addr ushr 8) and 0xFF).toByte(),
                (addr and 0xFF).toByte(),
            )
            writeLogged(addrBytes)
            consumeParamEcho(addrBytes, "jump_addr64")
        } else {
            sendCommand(BromCommands.CMD_JUMP_DA)
            val addrBytes = ByteUtil.u32ToBytesBE(loadAddress.toInt())
            writeLogged(addrBytes)
            // Same as SEND_DA: BROM echoes the address (log had IN 00 20 after OUT 00 20 00 00)
            consumeParamEcho(addrBytes, "jump_addr")
        }
        val jumpStatus = readLogged(2)
        val jumpWord = if (jumpStatus.size >= 2) {
            ((jumpStatus[0].toInt() and 0xFF) shl 8) or (jumpStatus[1].toInt() and 0xFF)
        } else if (jumpStatus.size == 1) {
            jumpStatus[0].toInt() and 0xFF
        } else {
            -1
        }
        val jumpOk = jumpWord == 0 ||
            (jumpStatus.size == 1 && jumpStatus[0] == BromCommands.ACK_MAGIC)
        if (!jumpOk) {
            throw IOException(
                "BROM did not acknowledge JUMP_DA (status=0x${"%04X".format(jumpWord)}, " +
                    "raw=${ByteUtil.toHex(jumpStatus)})"
            )
        }
        onRawExchange?.invoke(
            "INFO",
            "JUMP_DA status OK (0x${"%04X".format(jumpWord)})".toByteArray()
        )
        // DA takes over USB; give it time before CMD:START
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
