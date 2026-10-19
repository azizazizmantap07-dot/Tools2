package com.flashing.tool.protocol

import com.flashing.tool.model.PartitionEntry
import com.flashing.tool.usb.UsbTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Talks to a running MTK DA V6 over USB, using the XML command-envelope
 * protocol confirmed from the user's own DAMT6789.bin (MTK_DA_v6_2025-11-25,
 * Transsion vendor build). See DaCommands.kt for where each command string
 * and the envelope shape were sourced from — this is not a guess rewritten
 * from legacy-byte assumptions, it follows the literal strings found in that
 * binary.
 *
 * Wire framing used here (best current understanding — still mark with
 * TODO verify where noted, since we have command *names* and argument *field
 * names* confirmed from strings, but not a captured byte-for-byte session):
 *
 *   1. Host sends one length-prefixed XML command (see [sendXmlCommand]).
 *   2. For commands with a <packet_length> argument transferring a binary
 *      payload (CMD:DOWNLOAD-FILE / CMD:UPLOAD-FILE), the raw binary bytes
 *      follow immediately after the XML envelope, in chunks of up to
 *      packet_length bytes, until the declared total size is transferred.
 *   3. DA replies with its own XML envelope indicating success/failure,
 *      commonly via a trailing CMD:END with <result>SUCCESSED</result>.
 *
 * TODO verify (HIGH PRIORITY): whether the XML envelope itself is
 * length-prefixed on the wire (a 4-byte length header before the XML text)
 * or null-terminated / delimited some other way. V6-era MTK DA protocols
 * commonly length-prefix each XML blob with a 4-byte little-endian length
 * so the receiver knows how many bytes to read before parsing — that's the
 * assumption encoded in [sendXmlCommand]/[readXmlResponse] below. If the DA
 * doesn't respond to CMD:START as expected, this framing assumption is the
 * first thing to check via the raw hex log.
 */
class DaSession(
    private val transport: UsbTransport,
    private val onRawExchange: ((direction: String, bytes: ByteArray) -> Unit)? = null,
    private val onProgress: ((bytesDone: Long, bytesTotal: Long) -> Unit)? = null,
) {
    /** Partitions seen in DA boot logs: [name] At[0x…] Size[0x…] */
    private val bootPartitionHints = mutableListOf<PartitionEntry>()
    private val partLogRegex = Regex(
        """\[([^\]]+)\]\s+At\[(0x[0-9a-fA-F]+)\]\s+Size\[(0x[0-9a-fA-F]+)\]"""
    )

    // ---- low-level framing (mtkclient DAXML) ----
    // Packet: LE magic 0xFEEEEEEF | LE datatype | LE length | payload
    // datatype 1 = protocol flow, 2 = log message (extra 4-byte priority in header)

    private fun le32(v: Long): ByteArray {
        val x = v and 0xFFFFFFFFL
        return byteArrayOf(
            (x and 0xFF).toByte(),
            ((x ushr 8) and 0xFF).toByte(),
            ((x ushr 16) and 0xFF).toByte(),
            ((x ushr 24) and 0xFF).toByte(),
        )
    }

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    /** XML/text packet. Length includes the trailing NUL, matching mtkclient xsend(str). */
    private fun xsendText(text: String) {
        val body = text.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val hdr = le32(MAGIC) + le32(DT_PROTOCOL_FLOW.toLong()) + le32(body.size.toLong())
        onRawExchange?.invoke("OUT-XML", text.toByteArray(Charsets.UTF_8))
        transport.write(hdr)
        transport.write(body)
    }

    /** mtkclient ack() is xsend("OK\\0") → payload OK + two NULs, length 4. */
    private fun ack() {
        val body = byteArrayOf('O'.code.toByte(), 'K'.code.toByte(), 0, 0)
        val hdr = le32(MAGIC) + le32(DT_PROTOCOL_FLOW.toLong()) + le32(body.size.toLong())
        onRawExchange?.invoke("OUT", "OK".toByteArray())
        transport.write(hdr)
        transport.write(body)
    }

    /** mtkclient ack_value(): xsend("OK@0x..\\0") → text + two NULs. */
    private fun ackValue(length: Int) {
        val text = "OK@0x${"%x".format(length)}"
        val body = text.toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0)
        val hdr = le32(MAGIC) + le32(DT_PROTOCOL_FLOW.toLong()) + le32(body.size.toLong())
        onRawExchange?.invoke("OUT", text.toByteArray())
        transport.write(hdr)
        transport.write(body)
    }

    /** mtkclient xsend(bytes): MAGIC + DT_PROTOCOL_FLOW + exact length, no extra NUL. */
    private fun xsendBytes(data: ByteArray) {
        val hdr = le32(MAGIC) + le32(DT_PROTOCOL_FLOW.toLong()) + le32(data.size.toLong())
        transport.write(hdr)
        if (data.isNotEmpty()) transport.write(data, timeoutMs = 30_000)
    }

    private data class XFlow(val text: String, val raw: ByteArray)

    /**
     * Read one protocol-flow payload, skipping DA log messages (datatype 2).
     */

    private fun noteDaLog(msg: ByteArray) {
        if (msg.isEmpty()) return
        val s = msg.toString(Charsets.UTF_8)
        for (m in partLogRegex.findAll(s)) {
            val name = m.groupValues[1].trim()
            if (name.isEmpty() || name.equals("ROM_INFO", true)) continue
            val start = m.groupValues[2].removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: continue
            val size = m.groupValues[3].removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: continue
            if (size <= 0L) continue
            if (bootPartitionHints.any { it.name.equals(name, true) }) continue
            bootPartitionHints.add(
                PartitionEntry(
                    name = name,
                    startSector = start / 512L,
                    sizeBytes = size,
                )
            )
            onRawExchange?.invoke(
                "INFO",
                ("part-hint $name @0x${"%x".format(start)} size=0x${"%x".format(size)}").toByteArray(),
            )
        }
    }

    private fun xreadFlow(): XFlow {
        // DA2 can emit hundreds of tiny UFS/eMMC log packets before CMD:START
        repeat(2048) {
            val hdr = transport.readExact(12, timeoutMs = 20_000)
            onRawExchange?.invoke("IN-HDR", hdr)
            val magic = u32le(hdr, 0)
            if (magic != MAGIC) {
                throw IOException(
                    "DA xread magic salah: 0x${"%08X".format(magic)} (harap 0xFEEEEEEF) raw=${ByteUtil.toHex(hdr)}"
                )
            }
            val datatype = u32le(hdr, 4).toInt()
            var length = u32le(hdr, 8).toInt()
            if (length < 0 || length > 1024 * 1024) {
                throw IOException("DA xread length tidak masuk akal: $length datatype=$datatype")
            }
            if (datatype == DT_MESSAGE) {
                val prio = transport.readExact(4)
                length -= 4
                if (length < 0) length = 0
                val msg = if (length > 0) transport.readExact(length) else ByteArray(0)
                noteDaLog(msg)
                if (msg.size > 2) {
                    onRawExchange?.invoke("IN-LOG", msg)
                }
                return@repeat
            }
            if (datatype != DT_PROTOCOL_FLOW) {
                if (length > 0) transport.readExact(length)
                return@repeat
            }
            val data = if (length > 0) transport.readExact(length) else ByteArray(0)
            val text = data.toString(Charsets.UTF_8).trimEnd(0.toChar(), ' ', '\r', '\n')
            onRawExchange?.invoke("IN-XML", data)
            return XFlow(text, data)
        }
        throw IOException("DA xread: tidak ada protocol-flow setelah 2048 paket log")
    }

    private fun xmlField(xml: String, name: String): String {
        val open = "<$name>"
        val close = "</$name>"
        val s = xml.indexOf(open)
        if (s < 0) return ""
        val e = xml.indexOf(close, s + open.length)
        if (e < 0) return ""
        return xml.substring(s + open.length, e).trim()
    }

    /**
     * One DA XML transaction (mtkclient send_command):
     * host XML → "OK" → CMD:END → ack → CMD:START (acked inside reader).
     * Progress reports are acked until OK!EOT.
     */
    /**
     * Drain CMD:END (+ optional progress) and following CMD:START so the pipe
     * stays in sync after OK / ERR!UNSUPPORTED.
     */
    private fun finishCommandCycle(label: String = "") {
        val (cmd, result) = readCommandResult()
        when (cmd) {
            DaCommands.CMD_END -> {
                ack()
                val (scmd, _) = readCommandResult()
                if (scmd.isNotEmpty() && scmd != DaCommands.CMD_START) {
                    throw IOException("$label: setelah CMD:END diharapkan CMD:START, dapat $scmd ($result)")
                }
            }
            DaCommands.CMD_START -> { /* already acked in readCommandResult */ }
            DaCommands.CMD_DOWNLOAD_FILE -> {
                // Caller must handle; leave as error if unexpected here
                throw IOException("$label: DA minta DOWNLOAD-FILE tak terduga: $result")
            }
            else -> {
                if (cmd.isNotEmpty()) {
                    throw IOException("$label: respons tak dikenal cmd=$cmd result=${result.take(120)}")
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun sendCommand(xml: String): String {
        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            // mtkclient: still consume END + START so framing stays valid
            try {
                finishCommandCycle("after $first")
            } catch (_: Exception) {
            }
            throw IOException("DA menolak perintah: $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("DA menolak perintah (bukan OK): ${first.take(180)}")
        }
        // May get DOWNLOAD-FILE for boot-to / file ops
        val flowPeek = xreadFlow()
        val peekCmd = xmlField(flowPeek.text, "command")
        if (peekCmd == DaCommands.CMD_DOWNLOAD_FILE) {
            // Push responsibility to caller via special return; re-sync not needed yet
            // Stash by processing download here only when called from bootDa2
            return "DOWNLOAD-FILE:" + flowPeek.text
        }
        // Normal path: CMD:END then CMD:START
        // We already consumed one packet as flowPeek — treat it as command result
        return finishFromPacket(flowPeek.text)
    }

    private fun finishFromPacket(xml: String): String {
        var cmd = xmlField(xml, "command")
        var result = ""
        if (cmd == DaCommands.CMD_PROGRESS_REPORT) {
            ack()
            var msg = ""
            var guard = 0
            while (msg != "OK!EOT" && guard++ < 200) {
                msg = xreadFlow().text
                onRawExchange?.invoke("IN-PROGRESS", msg.toByteArray())
                ack()
            }
            val next = xreadFlow().text
            cmd = xmlField(next, "command")
            if (cmd == DaCommands.CMD_END) {
                result = xmlField(next, "result").ifEmpty { xmlField(next, "message") }
                ack()
                val (scmd, _) = readCommandResult()
                if (scmd.isNotEmpty() && scmd != DaCommands.CMD_START) {
                    throw IOException("Setelah progress/END diharapkan START, dapat $scmd")
                }
                return result.ifEmpty { "OK" }
            }
        }
        if (cmd == DaCommands.CMD_END) {
            result = xmlField(xml, "result").ifEmpty { xmlField(xml, "message") }
            ack()
            val (scmd, _) = readCommandResult()
            if (scmd.isNotEmpty() && scmd != DaCommands.CMD_START) {
                throw IOException("Setelah CMD:END diharapkan CMD:START, dapat $scmd")
            }
            return result.ifEmpty { "OK" }
        }
        if (cmd == DaCommands.CMD_START) {
            ack()
            return "START"
        }
        // Fallback: full cycle
        ack()
        try { finishCommandCycle("fallback") } catch (_: Exception) {}
        return result.ifEmpty { cmd.ifEmpty { "OK" } }
    }

    /**
     * Upload DA stage-2 via CMD:BOOT-TO using mtkclient xml_lib.upload():
     * ACK DOWNLOAD-FILE, OK@length, then each chunk is OK@0 + xflash-framed bytes + OK.
     * END + START are required; a silent USB drop is a failure (not a successful jump).
     */
    @Throws(IOException::class)
    fun bootDa2(loadAddress: Long, payload: ByteArray) {
        if (payload.isEmpty()) throw IOException("DA2 payload kosong")
        val data = payload
        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>CMD:BOOT-TO</command><arg>""" +
                """<at_address>0x${"%x".format(loadAddress)}</at_address>""" +
                """<jmp_address>0x${"%x".format(loadAddress)}</jmp_address>""" +
                """<source_file>MEM://0x0:0x${"%x".format(data.size)}</source_file>""" +
                """</arg></da>"""
        onRawExchange?.invoke(
            "INFO",
            "BOOT-TO DA2 ${data.size} B @ 0x${"%08X".format(loadAddress)}".toByteArray(),
        )
        xsendText(xml)
        val first = xreadFlow().text
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("BOOT-TO ditolak: ${first.take(120)}")
        }

        var pkt = ""
        var cmd = ""
        var guard = 0
        while (guard++ < 40) {
            pkt = xreadFlow().text
            cmd = xmlField(pkt, "command")
            when {
                cmd == DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""
                    var g = 0
                    while (msg != "OK!EOT" && g++ < 80) {
                        msg = try { xreadFlow().text } catch (_: Exception) { break }
                        ack()
                    }
                }
                cmd == DaCommands.CMD_DOWNLOAD_FILE -> break
                cmd == DaCommands.CMD_END -> break
                pkt.equals("OK", true) || pkt.startsWith("OK") -> { /* continue */ }
                else -> {
                    if (cmd.isNotEmpty()) break
                }
            }
        }

        if (cmd == DaCommands.CMD_DOWNLOAD_FILE) {
            var packetLength = xmlField(pkt, "packet_length")
                .removePrefix("0x").removePrefix("0X")
                .toIntOrNull(16) ?: 0
            if (packetLength <= 0) packetLength = 0x1000
            packetLength = packetLength.coerceIn(0x200, 0x40000)
            ack()
            // mtkclient xml_lib.upload(): OK@length, then per chunk OK@0 + framed xsend + OK
            onRawExchange?.invoke(
                "INFO",
                "DA2 framed upload ${data.size} B, packet_length=0x${"%X".format(packetLength)}".toByteArray(),
            )
            ackValue(data.size)
            val lenAck = xreadFlow().text
            if (!lenAck.contains("OK")) {
                throw IOException("DA2 menolak panjang ${data.size}: ${lenAck.take(120)}")
            }
            var offset = 0
            val total = data.size
            onProgress?.invoke(0L, total.toLong())
            while (offset < total) {
                ackValue(0)
                val pre = xreadFlow().text
                if (!pre.contains("OK")) {
                    throw IOException("DA2 ACK0 gagal @ $offset: ${pre.take(120)}")
                }
                val n = minOf(packetLength, total - offset)
                xsendBytes(data.copyOfRange(offset, offset + n))
                val post = xreadFlow().text
                if (!post.contains("OK")) {
                    throw IOException("DA2 chunk ditolak @ $offset: ${post.take(120)}")
                }
                offset += n
                onProgress?.invoke(offset.toLong(), total.toLong())
                if (offset == total || offset % (64 * 1024) < packetLength) {
                    onRawExchange?.invoke(
                        "INFO",
                        "DA2 upload $offset / $total".toByteArray(),
                    )
                }
            }
            onRawExchange?.invoke(
                "INFO",
                "DA2 binary selesai ($total B) — tunggu END/START…".toByteArray(),
            )
            drainBootToResult()
            onRawExchange?.invoke("INFO", "DA2 END/START OK".toByteArray())
            return
        }

        if (cmd == DaCommands.CMD_END) {
            ack()
            try {
                val (s, _) = readCommandResult()
                if (s.isNotEmpty() && s != DaCommands.CMD_START) {
                    throw IOException("BOOT-TO END tanpa download, lalu $s")
                }
            } catch (e: IOException) {
                onRawExchange?.invoke("INFO", "BOOT-TO END drain: ${e.message}".toByteArray())
            }
            return
        }
        throw IOException("BOOT-TO: respons tak dikenal cmd=$cmd xml=${pkt.take(160)}")
    }

    private fun drainBootToResult() {
        var guard = 0
        var sawEnd = false
        var endResult = ""
        var endMessage = ""
        while (guard++ < 24) {
            val flow = xreadFlowExtended(8_000)
            val text = flow.text
            val cmd = xmlField(text, "command")
            when {
                text == "OK!EOT" || text.startsWith("OK@") -> ack()
                cmd == DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""
                    var g = 0
                    while (msg != "OK!EOT" && g++ < 200) {
                        msg = try { xreadFlowExtended(15_000).text } catch (_: Exception) { break }
                        onRawExchange?.invoke("IN-PROGRESS", msg.toByteArray())
                        ack()
                    }
                }
                cmd == DaCommands.CMD_END -> {
                    endResult = xmlField(text, "result")
                    endMessage = xmlField(text, "message")
                    if (endMessage.isEmpty()) endMessage = xmlField(text, "info")
                    onRawExchange?.invoke(
                        "INFO",
                        ("DA2 CMD:END result=${endResult.ifEmpty { "-" }} msg=${endMessage.take(160)}").toByteArray(),
                    )
                    val bad = endMessage.contains("mismatch", ignoreCase = true) ||
                        endMessage.contains("hash", ignoreCase = true) ||
                        (endResult.isNotEmpty() &&
                            !endResult.equals("OK", true) &&
                            !endResult.equals(DaCommands.RESULT_SUCCESS, true))
                    ack()
                    if (bad) {
                        throw IOException(
                            "DA2 ditolak: ${endMessage.ifEmpty { endResult }.ifEmpty { "CMD:END gagal" }.take(200)}"
                        )
                    }
                    sawEnd = true
                    // After hash OK, DA2 boots and may spam UFS logs for several seconds
                    // before CMD:START. Keep reading until START or overall timeout.
                    val deadline = System.currentTimeMillis() + 90_000L
                    var lastErr: IOException? = null
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val next = xreadFlowExtended(10_000).text
                            val c2 = xmlField(next, "command")
                            if (c2 == DaCommands.CMD_START ||
                                next.equals("OK", true) ||
                                next.startsWith("OK")
                            ) {
                                ack()
                                onRawExchange?.invoke("INFO", "DA2 CMD:START setelah boot".toByteArray())
                                return
                            }
                            if (c2 == DaCommands.CMD_PROGRESS_REPORT) {
                                ack()
                                continue
                            }
                            if (c2.isNotEmpty()) {
                                onRawExchange?.invoke(
                                    "INFO",
                                    "DA2 post-END cmd=$c2 ${next.take(80)}".toByteArray(),
                                )
                                ack()
                            }
                        } catch (e: IOException) {
                            lastErr = e
                            // brief pause if bus momentarily quiet during jump
                            try { Thread.sleep(50) } catch (_: InterruptedException) {}
                        }
                    }
                    throw IOException(
                        "DA2 END OK tapi CMD:START tidak datang dalam 90s " +
                            "(mungkin masih init storage). last=${lastErr?.message}"
                    )
                }
                cmd == DaCommands.CMD_START -> {
                    ack()
                    return
                }
                text.equals("OK", true) || text.startsWith("OK") -> ack()
                else -> {
                    if (cmd.isNotEmpty()) {
                        onRawExchange?.invoke("INFO", "DA2 drain cmd=$cmd".toByteArray())
                        ack()
                    }
                }
            }
        }
        if (sawEnd) {
            throw IOException("DA2: CMD:END tanpa CMD:START setelah upload")
        }
        throw IOException("DA2: tidak ada CMD:END/CMD:START setelah upload")
    }

    private fun xreadFlowExtended(timeoutMs: Int): XFlow {
        // After DA2 jump, UFS init logs flood one byte at a time — need large skip budget
        repeat(4096) {
            val hdr = transport.readExact(12, timeoutMs = timeoutMs)
            onRawExchange?.invoke("IN-HDR", hdr)
            val magic = u32le(hdr, 0)
            if (magic != MAGIC) {
                throw IOException(
                    "DA xread magic salah: 0x${"%08X".format(magic)} raw=${ByteUtil.toHex(hdr)}"
                )
            }
            val datatype = u32le(hdr, 4).toInt()
            var length = u32le(hdr, 8).toInt()
            if (length < 0 || length > 1024 * 1024) {
                throw IOException("DA xread length tidak masuk akal: $length")
            }
            if (datatype == DT_MESSAGE) {
                transport.readExact(4, timeoutMs = timeoutMs) // priority
                length -= 4
                if (length < 0) length = 0
                val msg = if (length > 0) transport.readExact(length, timeoutMs = timeoutMs) else ByteArray(0)
                // Skip flooding UI with single-byte UFS descriptor dumps
                noteDaLog(msg)
                if (msg.size > 2) {
                    onRawExchange?.invoke("IN-LOG", msg)
                }
                return@repeat
            }
            if (datatype != DT_PROTOCOL_FLOW) {
                if (length > 0) transport.readExact(length, timeoutMs = timeoutMs)
                return@repeat
            }
            val data = if (length > 0) transport.readExact(length, timeoutMs = timeoutMs) else ByteArray(0)
            val text = data.toString(Charsets.UTF_8).trimEnd(0.toChar(), ' ', '\r', '\n')
            onRawExchange?.invoke("IN-XML", data)
            return XFlow(text, data)
        }
        throw IOException("DA xreadExtended: tidak ada protocol-flow")
    }

    /** Returns command name and result/message. CMD:START is acked here. */
    private fun readCommandResult(): Pair<String, String> {
        var flow = xreadFlow().text
        if (flow.startsWith("OK@") || flow == "OK!EOT") {
            return "" to flow
        }
        var cmd = xmlField(flow, "command")
        if (cmd == DaCommands.CMD_PROGRESS_REPORT) {
            ack()
            var msg = ""
            var guard = 0
            while (msg != "OK!EOT" && guard++ < 200) {
                msg = xreadFlow().text
                onRawExchange?.invoke("IN-PROGRESS", msg.toByteArray())
                ack()
            }
            flow = xreadFlow().text
            cmd = xmlField(flow, "command")
        }
        if (cmd == DaCommands.CMD_START) {
            ack()
            return cmd to "START"
        }
        if (cmd == DaCommands.CMD_END) {
            val result = xmlField(flow, "result")
            val message = xmlField(flow, "message")
            val value = if (message.isNotEmpty() && result != "OK" && result != DaCommands.RESULT_SUCCESS) {
                message
            } else result
            return cmd to value
        }
        return cmd to flow
    }

    private fun sendXmlCommand(command: String, args: Map<String, String> = emptyMap()) {
        val xml = DaXml.buildCommand(command, args).toString(Charsets.UTF_8)
        xsendText(xml)
    }

    private fun readXmlResponse(maxLength: Int = 64 * 1024): String {
        val flow = xreadFlow()
        if (flow.raw.size > maxLength) {
            throw IOException("XML response terlalu besar: ${flow.raw.size}")
        }
        return flow.text
    }


    /**
     * Sends [command], reads the DA's XML reply, and throws unless the reply
     * indicates success. Returns the parsed flat tag-map for callers that need
     * more than a bare success/fail (e.g. CMD:GET-DA-INFO's version fields).
     */
    @Throws(IOException::class)
    private fun sendAndExpectOk(command: String, args: Map<String, String> = emptyMap()): Map<String, String> {
        val xml = DaXml.buildCommand(command, args).toString(Charsets.UTF_8)
        val result = sendCommand(xml)
        return mapOf("result" to result, "arg/result" to result)
    }

    /**
     * After JUMP_DA the DA speaks first: a 0xFEEEEEEF packet whose payload is
     * XML CMD:START. Host must ACK, then send runtime / hw-init. Do not send
     * CMD:START ourselves.
     */
    @Throws(IOException::class)
    fun start() {
        val (cmd, result) = readCommandResult()
        if (cmd != DaCommands.CMD_START && result != "START") {
            throw IOException("DA tidak mengirim CMD:START (cmd=$cmd result=${result.take(120)})")
        }
        val runtime = """<?xml version="1.0" encoding="utf-8"?><da><version>1.1</version><command>CMD:SET-RUNTIME-PARAMETER</command><arg><checksum_level>NONE</checksum_level><battery_exist>AUTO-DETECT</battery_exist><da_log_level>INFO</da_log_level><log_channel>USB</log_channel><system_os>LINUX</system_os></arg><adv><initialize_dram>YES</initialize_dram></adv></da>"""
        sendCommand(runtime)
        val cap = "CMD:DOWNLOAD-FILE^1@CMD:FILE-SYS-OPERATION^1@CMD:PROGRESS-REPORT^1@CMD:UPLOAD-FILE^1@"
        sendCommand(
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version><command>CMD:HOST-SUPPORTED-COMMANDS</command><arg><host_capability>$cap</host_capability></arg></da>"""
        )
        sendCommand(
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version><command>CMD:NOTIFY-INIT-HW</command></da>"""
        )
    }

    /**
     * Reports basic host identity to the DA — some DA builds require this
     * before accepting partition operations. Values here are placeholders;
     * adjust fields to whatever da/arg/host_capability etc. actually expects
     * once you've captured a real exchange (see PROTOCOL_NOTES.md).
     */
    @Throws(IOException::class)
    fun setHostInfo() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        sendCommand(
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version><command>CMD:SET-HOST-INFO</command><arg><info>$stamp</info></arg></da>"""
        )
    }

    /**
     * Reads DA version/build info — cheap sanity check that you're really
     * talking to DAMT6789.bin and not some other DA, and a good first call
     * to validate framing against a known-static expected response shape.
     */
    @Throws(IOException::class)
    fun getDaInfo(): Map<String, String> = sendAndExpectOk(DaCommands.CMD_GET_DA_INFO)

    /**
     * Reads hardware info: RAM size, storage type/size, product id, etc.
     * Also where EFUSE-derived security state tends to surface in logs —
     * see PROTOCOL_NOTES.md for how to find the sla_en/daa_en indicators.
     */
    /**
     * Penumbra: CMD:GET-HW-INFO + target_file MEM://… → UPLOAD-FILE.
     * DA ini sering menolak tanpa target_file. Juga isi SLA via GET-SYS-PROPERTY.
     */
    @Throws(IOException::class)
    fun getHwInfo(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        // Chip already known from BROM
        try {
            val data = uploadCommandResult(
                command = DaCommands.CMD_GET_HW_INFO,
                argsXml = """<target_file>MEM://0x0:0x200000</target_file>""",
            )
            val text = data.toString(Charsets.UTF_8)
            out["raw"] = text.take(2000)
            // parse common tags if present
            for (tag in listOf(
                "ram_size", "storage", "emmc_size", "ufs_size", "product_id",
                "hw_code", "hw_version", "sw_version", "battery_voltage",
            )) {
                val v = xmlField(text, tag)
                if (v.isNotEmpty()) out[tag] = v
            }
            // flat parse for nested xml
            try {
                out.putAll(DaXml.parseFlat(text).filterKeys { it !in out })
            } catch (_: Exception) {}
        } catch (e: Exception) {
            out["GET-HW-INFO"] = "gagal: ${e.message?.take(120)}"
            try { finishCommandCycle("hwinfo") } catch (_: Exception) {}
        }

        // SLA status — Penumbra uses GET-SYS-PROPERTY key DA.SLA
        try {
            val sla = uploadCommandResult(
                command = DaCommands.CMD_GET_SYS_PROPERTY,
                argsXml = """<key>DA.SLA</key><target_file>MEM://0x0:0x200000</target_file>""",
            ).toString(Charsets.UTF_8)
            val enabled = sla.contains("ENABLED", ignoreCase = true)
            val disabled = sla.contains("DISABLED", ignoreCase = true)
            out["SLA"] = when {
                enabled -> "ENABLED"
                disabled -> "DISABLED"
                else -> sla.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
            }
            out["sla_enabled"] = enabled.toString()
            com.flashing.tool.protocol.FlashSessionHolder.lastSlaEnabled = enabled
        } catch (e: Exception) {
            out["SLA"] = "tidak terbaca (${e.message?.take(60) ?: "?"})"
            try { finishCommandCycle("sla") } catch (_: Exception) {}
        }

        // Never fail the whole call if we at least have SLA or chip from BROM
        if (out.isEmpty()) {
            throw IOException("HW info kosong")
        }
        // Drop noisy GET-HW-INFO failure key for cleaner UI
        if (out["GET-HW-INFO"]?.startsWith("gagal") == true) {
            out.remove("GET-HW-INFO")
            out["GET-HW-INFO"] = "tidak didukung DA ini (normal)"
        }
        return out
    }

    /** Send XML command that responds with UPLOAD-FILE payload (Penumbra pattern). */
    private fun uploadCommandResult(command: String, argsXml: String): ByteArray {
        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>$command</command><arg>$argsXml</arg></da>"""
        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            throw IOException("$command: $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("$command ditolak: ${first.take(100)}")
        }
        var guard = 0
        var cmd = ""
        while (guard++ < 40) {
            val pkt = xreadFlow().text
            cmd = xmlField(pkt, "command")
            when {
                cmd == DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""
                    var g = 0
                    while (msg != "OK!EOT" && g++ < 100) {
                        msg = try { xreadFlow().text } catch (_: Exception) { break }
                        ack()
                    }
                }
                cmd == "CMD:UPLOAD-FILE" || cmd.endsWith("UPLOAD-FILE") -> {
                    ack()
                    break
                }
                cmd == DaCommands.CMD_END -> {
                    // some props return inline in END
                    val msg = xmlField(pkt, "message")
                    ack()
                    try { readCommandResult() } catch (_: Exception) {}
                    if (msg.isNotEmpty()) return msg.toByteArray()
                    throw IOException("$command: END tanpa UPLOAD")
                }
            }
        }
        if (cmd != "CMD:UPLOAD-FILE" && !cmd.endsWith("UPLOAD-FILE")) {
            throw IOException("$command: expect UPLOAD-FILE, got $cmd")
        }
        val data = downloadUploadedFile()
        try {
            val (ecmd, _) = readCommandResult()
            if (ecmd == DaCommands.CMD_END) {
                ack()
                try { readCommandResult() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return data
    }


    // ---- partition operations (MVP scope) ----

    /**
     * Reads the device's current partition table.
     * Response shape confirmed from binary:
     *   <partition_table version="1.0"><pt><name>..</name><start>0x..</start><size>0x..</size></pt>...</partition_table>
     */
    @Throws(IOException::class)
    fun readPartitionTable(): List<PartitionEntry> {
        // Penumbra: baca "PGPT"/"SGPT" via CMD:READ-PARTITION, parse GPT binary.
        // CMD:READ-PARTITION-TABLE sering ERR!UNSUPPORTED pada DA XML modern.
        onRawExchange?.invoke(
            "INFO",
            "Tabel partisi: metode Penumbra (PGPT/SGPT → GPT)".toByteArray(),
        )

        for (name in listOf("PGPT", "SGPT")) {
            try {
                val data = readNamedPartitionBytes(name, 32L * 1024L)
                val gpt = parseGptBinary(data)
                if (gpt.isNotEmpty()) {
                    onRawExchange?.invoke(
                        "INFO",
                        ("GPT dari $name: ${gpt.size} partisi").toByteArray(),
                    )
                    return gpt
                }
            } catch (e: Exception) {
                onRawExchange?.invoke(
                    "INFO",
                    ("Baca $name gagal: ${e.message?.take(120)}").toByteArray(),
                )
                try { finishCommandCycle("read $name") } catch (_: Exception) {}
            }
        }

        if (bootPartitionHints.isNotEmpty()) {
            onRawExchange?.invoke(
                "INFO",
                ("Tabel partisi dari log boot DA: ${bootPartitionHints.size} entry").toByteArray(),
            )
            return bootPartitionHints.toList()
        }

        for (part in listOf("UFS-USER", "EMMC-USER", "USER")) {
            try {
                val gpt = readFlashGpt(part)
                if (gpt.isNotEmpty()) {
                    onRawExchange?.invoke(
                        "INFO",
                        ("Tabel partisi dari GPT $part: ${gpt.size} entry").toByteArray(),
                    )
                    return gpt
                }
            } catch (e: Exception) {
                onRawExchange?.invoke(
                    "INFO",
                    ("READ-FLASH $part gagal: ${e.message?.take(100)}").toByteArray(),
                )
                try { finishCommandCycle("gpt $part") } catch (_: Exception) {}
            }
        }

        throw IOException(
            "Tidak bisa baca tabel partisi (PGPT/SGPT/log/READ-FLASH semua gagal)."
        )
    }

    /** Penumbra-style CMD:READ-PARTITION for named partition (PGPT, etc.). */
    private fun readNamedPartitionBytes(partName: String, maxBytes: Long): ByteArray {
        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>${DaCommands.CMD_READ_PARTITION}</command><arg>""" +
                """<partition>$partName</partition>""" +
                """<target_file>$partName.bin</target_file>""" +
                """</arg></da>"""
        onRawExchange?.invoke("INFO", "CMD:READ-PARTITION $partName".toByteArray())
        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            throw IOException("READ-PARTITION $partName: $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("READ-PARTITION $partName ditolak: ${first.take(100)}")
        }

        var guard = 0
        var cmd = ""
        while (guard++ < 40) {
            val pkt = xreadFlow().text
            cmd = xmlField(pkt, "command")
            when {
                cmd == DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""
                    var g = 0
                    while (msg != "OK!EOT" && g++ < 100) {
                        msg = try { xreadFlow().text } catch (_: Exception) { break }
                        ack()
                    }
                }
                cmd == "CMD:UPLOAD-FILE" || cmd.endsWith("UPLOAD-FILE") -> {
                    ack()
                    break
                }
                cmd == DaCommands.CMD_END -> {
                    ack()
                    throw IOException("READ-PARTITION $partName: END tanpa data")
                }
            }
        }
        if (cmd != "CMD:UPLOAD-FILE" && !cmd.endsWith("UPLOAD-FILE")) {
            throw IOException("READ-PARTITION $partName: expect UPLOAD-FILE, got $cmd")
        }

        val data = downloadUploadedFile()
        try {
            val (ecmd, _) = readCommandResult()
            if (ecmd == DaCommands.CMD_END) {
                ack()
                try { readCommandResult() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (data.isEmpty()) throw IOException("READ-PARTITION $partName: data kosong")
        return if (data.size > maxBytes) data.copyOf(maxBytes.toInt()) else data
    }

    private fun readFlashGpt(userPartition: String): List<PartitionEntry> {
        val length = 0x10000
        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>${DaCommands.CMD_READ_FLASH}</command><arg>""" +
                """<partition>$userPartition</partition>""" +
                """<offset>0x0</offset>""" +
                """<length>0x${"%x".format(length)}</length>""" +
                """<target_file>ROM_0</target_file>""" +
                """</arg></da>"""
        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            throw IOException("READ-FLASH unsupported: $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("READ-FLASH ditolak: ${first.take(100)}")
        }
        var guard = 0
        var cmd = ""
        while (guard++ < 40) {
            val pkt = xreadFlow().text
            cmd = xmlField(pkt, "command")
            when {
                cmd == DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""
                    var g = 0
                    while (msg != "OK!EOT" && g++ < 100) {
                        msg = try { xreadFlow().text } catch (_: Exception) { break }
                        ack()
                    }
                }
                cmd == "CMD:UPLOAD-FILE" || cmd.endsWith("UPLOAD-FILE") -> {
                    ack()
                    break
                }
                cmd == DaCommands.CMD_END -> {
                    ack()
                    throw IOException("READ-FLASH END tanpa data")
                }
            }
        }
        val data = downloadUploadedFile()
        try {
            val (ecmd, _) = readCommandResult()
            if (ecmd == DaCommands.CMD_END) {
                ack()
                try { readCommandResult() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return parseGptBinary(data)
    }

    private fun parseGptBinary(data: ByteArray): List<PartitionEntry> {
        if (data.size < 512) return emptyList()
        for (ss in longArrayOf(512L, 4096L)) {
            val hdrOff = ss.toInt()
            if (data.size < hdrOff + 92) continue
            val sig = data.copyOfRange(hdrOff, hdrOff + 8).toString(Charsets.US_ASCII)
            if (sig != "EFI PART") continue
            fun u32(o: Int): Long {
                var v = 0L
                for (i in 0 until 4) v = v or ((data[hdrOff + o + i].toLong() and 0xff) shl (8 * i))
                return v
            }
            fun u64(o: Int): Long {
                var v = 0L
                for (i in 0 until 8) v = v or ((data[hdrOff + o + i].toLong() and 0xff) shl (8 * i))
                return v
            }
            val numParts = u32(80).toInt()
            val partEntrySize = u32(84).toInt()
            val partLba = u64(72)
            val partOff = (partLba * ss).toInt()
            if (partEntrySize < 128 || numParts <= 0 || numParts > 256) continue
            if (partOff + numParts * partEntrySize > data.size) continue
            val out = mutableListOf<PartitionEntry>()
            for (i in 0 until numParts) {
                val o = partOff + i * partEntrySize
                val typeZero = (0 until 16).all { data[o + it] == 0.toByte() }
                if (typeZero) continue
                var firstLba = 0L
                for (b in 0 until 8) firstLba = firstLba or ((data[o + 32 + b].toLong() and 0xff) shl (8 * b))
                var lastLba = 0L
                for (b in 0 until 8) lastLba = lastLba or ((data[o + 40 + b].toLong() and 0xff) shl (8 * b))
                val nameBytes = data.copyOfRange(o + 56, o + 56 + 72)
                val name = String(nameBytes, Charsets.UTF_16LE).trimEnd('\u0000').trim()
                if (name.isEmpty()) continue
                val sizeSectors = lastLba - firstLba + 1
                out.add(
                    PartitionEntry(
                        name = name,
                        startSector = firstLba * (ss / 512L),
                        sizeBytes = sizeSectors * ss,
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }


    /** mtkclient download(): OK@len → OK → framed data chunks → ACK */
    private fun downloadUploadedFile(): ByteArray {
        val sizeResp = xreadFlow().text
        if (!sizeResp.startsWith("OK@")) {
            throw IOException("UPLOAD size expected OK@…, got ${sizeResp.take(80)}")
        }
        val hex = sizeResp.removePrefix("OK@").trim().removePrefix("0x").removePrefix("0X")
        val total = hex.toLongOrNull(16)
            ?: throw IOException("UPLOAD size parse gagal: $sizeResp")
        if (total <= 0L) {
            throw IOException("UPLOAD size 0")
        }
        // Soft cap 2 GiB (avoid OOM on bad length); large partitions stream via readPartition
        if (total > 2L * 1024 * 1024 * 1024) {
            throw IOException("UPLOAD size terlalu besar: $total")
        }
        ack()
        val ok2 = xreadFlow().text
        if (!ok2.contains("OK")) {
            throw IOException("UPLOAD siap ditolak: ${ok2.take(80)}")
        }
        ack()
        val out = java.io.ByteArrayOutputStream(total.toInt().coerceAtLeast(256))
        var remaining = total
        while (remaining > 0) {
            // Each chunk is a DT_PROTOCOL_FLOW packet with binary payload
            val chunk = xreadBinaryChunk()
            if (chunk.isEmpty()) break
            out.write(chunk)
            remaining -= chunk.size
            onProgress?.invoke(total - remaining, total)
        }
        ack()
        return out.toByteArray()
    }

    private fun xreadBinaryChunk(): ByteArray {
        repeat(256) {
            val hdr = transport.readExact(12, timeoutMs = 30_000)
            onRawExchange?.invoke("IN-HDR", hdr)
            val magic = u32le(hdr, 0)
            if (magic != MAGIC) {
                throw IOException("xreadBinary magic 0x${"%08X".format(magic)}")
            }
            val datatype = u32le(hdr, 4).toInt()
            var length = u32le(hdr, 8).toInt()
            if (length < 0 || length > 1024 * 1024) {
                throw IOException("xreadBinary length $length")
            }
            if (datatype == DT_MESSAGE) {
                transport.readExact(4, timeoutMs = 30_000)
                length -= 4
                if (length < 0) length = 0
                val msg = if (length > 0) transport.readExact(length, timeoutMs = 30_000) else ByteArray(0)
                if (msg.size > 2) onRawExchange?.invoke("IN-LOG", msg)
                return@repeat
            }
            if (datatype != DT_PROTOCOL_FLOW) {
                if (length > 0) transport.readExact(length, timeoutMs = 30_000)
                return@repeat
            }
            return if (length > 0) transport.readExact(length, timeoutMs = 30_000) else ByteArray(0)
        }
        throw IOException("xreadBinary: tidak ada protocol-flow")
    }

    private fun parsePartitionTableLoose(data: String): List<PartitionEntry> {
        val entries = mutableListOf<PartitionEntry>()
        for (item in data.split("<pt>")) {
            val name = xmlField(item, "name")
            if (name.isEmpty()) continue
            val startS = xmlField(item, "start")
            val sizeS = xmlField(item, "size")
            if (sizeS.isEmpty()) continue
            val start = startS.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: continue
            val size = sizeS.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: continue
            entries.add(PartitionEntry(name = name, startSector = start / 512L, sizeBytes = size))
        }
        return entries
    }
    /**
     * Backup/read partition.
     * 1) CMD:READ-PARTITION (Penumbra name-based) — several DAs return ERR!UNSUPPORTED
     * 2) Fallback: CMD:READ-FLASH at physical offset (startSector*512, length=sizeBytes)
     *    trying UFS-USER / EMMC-USER / USER regions.
     */
    @Throws(IOException::class)
    fun readPartition(entry: PartitionEntry, output: OutputStream, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
        if (entry.sizeBytes <= 0L) throw IOException("Ukuran partisi '${entry.name}' 0")
        val expected = entry.sizeBytes
        onRawExchange?.invoke(
            "INFO",
            ("READ ${entry.name} size=0x${"%x".format(expected)}").toByteArray(),
        )

        // --- Attempt 1: name-based READ-PARTITION ---
        try {
            readPartitionByName(entry, output, chunkSize)
            return
        } catch (e: IOException) {
            val msg = e.message ?: ""
            val unsupported = msg.contains("UNSUPPORTED", ignoreCase = true) ||
                msg.contains("Unsupported", ignoreCase = true)
            if (!unsupported) throw e
            onRawExchange?.invoke(
                "INFO",
                ("READ-PARTITION ${entry.name} tidak didukung DA — fallback READ-FLASH").toByteArray(),
            )
            try { finishCommandCycle("read-name ${entry.name}") } catch (_: Exception) {}
        }

        // --- Attempt 2: physical READ-FLASH ---
        val offset = entry.startSector * 512L
        val length = entry.sizeBytes
        var last: Exception? = null
        for (region in listOf("UFS-USER", "EMMC-USER", "USER")) {
            try {
                onRawExchange?.invoke(
                    "INFO",
                    ("READ-FLASH $region offset=0x${"%x".format(offset)} len=0x${"%x".format(length)}").toByteArray(),
                )
                readFlashRange(region, offset, length, output, chunkSize)
                onRawExchange?.invoke(
                    "INFO",
                    ("READ-FLASH ${entry.name} via $region selesai").toByteArray(),
                )
                return
            } catch (e: Exception) {
                last = e
                onRawExchange?.invoke(
                    "INFO",
                    ("READ-FLASH $region gagal: ${e.message?.take(120)}").toByteArray(),
                )
                try { finishCommandCycle("read-flash $region") } catch (_: Exception) {}
            }
        }
        throw IOException(
            "Backup '${entry.name}' gagal (READ-PARTITION + READ-FLASH): ${last?.message}",
            last,
        )
    }

    /** Name-based CMD:READ-PARTITION → UPLOAD-FILE stream. */
    @Throws(IOException::class)
    private fun readPartitionByName(
        entry: PartitionEntry,
        output: OutputStream,
        chunkSize: Int,
    ) {
        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>${DaCommands.CMD_READ_PARTITION}</command><arg>""" +
                """<partition>${entry.name}</partition>""" +
                """<target_file>${entry.name}.bin</target_file>""" +
                """</arg></da>"""
        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            try { finishCommandCycle("read ${entry.name}") } catch (_: Exception) {}
            throw IOException("READ-PARTITION '${entry.name}' ditolak: $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("READ-PARTITION '${entry.name}' ditolak: ${first.take(120)}")
        }
        awaitUploadAndStream(entry.name, output, chunkSize)
    }

    /**
     * Physical range read: CMD:READ-FLASH partition/offset/length → UPLOAD-FILE.
     */
    @Throws(IOException::class)
    private fun readFlashRange(
        userPartition: String,
        offset: Long,
        length: Long,
        output: OutputStream,
        chunkSize: Int,
    ) {
        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>${DaCommands.CMD_READ_FLASH}</command><arg>""" +
                """<partition>$userPartition</partition>""" +
                """<offset>0x${"%x".format(offset)}</offset>""" +
                """<length>0x${"%x".format(length)}</length>""" +
                """<target_file>ROM_0</target_file>""" +
                """</arg></da>"""
        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            try { finishCommandCycle("read-flash") } catch (_: Exception) {}
            throw IOException("READ-FLASH unsupported: $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("READ-FLASH ditolak: ${first.take(100)}")
        }
        awaitUploadAndStream("flash@$userPartition", output, chunkSize)
    }

    /** Shared UPLOAD-FILE framed download into [output]. */
    @Throws(IOException::class)
    private fun awaitUploadAndStream(label: String, output: OutputStream, chunkSize: Int) {
        var guard = 0
        var cmd = ""
        var packetLength = chunkSize.coerceIn(0x1000, 0x100000)
        while (guard++ < 80) {
            val pkt = xreadFlow().text
            cmd = xmlField(pkt, "command")
            when {
                cmd == DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""
                    var g = 0
                    while (msg != "OK!EOT" && g++ < 200) {
                        msg = try { xreadFlow().text } catch (_: Exception) { break }
                        ack()
                    }
                }
                cmd == "CMD:UPLOAD-FILE" || cmd.endsWith("UPLOAD-FILE") -> {
                    val pl = xmlField(pkt, "packet_length")
                    if (pl.isNotEmpty()) {
                        packetLength = pl.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                            ?: packetLength
                    }
                    ack()
                    break
                }
                cmd == DaCommands.CMD_END -> {
                    ack()
                    try { readCommandResult() } catch (_: Exception) {}
                    throw IOException("$label: END tanpa data UPLOAD")
                }
            }
        }
        if (cmd != "CMD:UPLOAD-FILE" && !cmd.endsWith("UPLOAD-FILE")) {
            throw IOException("$label: expect UPLOAD-FILE, got $cmd")
        }

        val sizeResp = xreadFlow().text
        if (!sizeResp.startsWith("OK@")) {
            throw IOException("UPLOAD size expected OK@…, got ${sizeResp.take(80)}")
        }
        val hex = sizeResp.removePrefix("OK@").trim().removePrefix("0x").removePrefix("0X")
        val total = hex.toLongOrNull(16)
            ?: throw IOException("UPLOAD size parse gagal: $sizeResp")
        if (total <= 0L) throw IOException("UPLOAD size 0 untuk $label")
        ack()
        val ok2 = xreadFlow().text
        if (!ok2.contains("OK")) {
            throw IOException("UPLOAD siap ditolak: ${ok2.take(80)}")
        }
        ack()

        var remaining = total
        var done = 0L
        onProgress?.invoke(0L, total)
        while (remaining > 0) {
            val chunk = xreadBinaryChunk()
            if (chunk.isEmpty()) break
            output.write(chunk)
            remaining -= chunk.size
            done += chunk.size
            onProgress?.invoke(done, total)
        }
        ack()

        try {
            val (ecmd, _) = readCommandResult()
            if (ecmd == DaCommands.CMD_END) {
                ack()
                try { readCommandResult() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (done == 0L) {
            throw IOException("Backup $label: 0 byte diterima")
        }
        onRawExchange?.invoke(
            "INFO",
            ("$label selesai: $done / $total B").toByteArray(),
        )
    }


    /**
     * Penumbra-style write: CMD:WRITE-PARTITION → loop
     * (PROGRESS-REPORT / FILE-SYS-OPERATION / DOWNLOAD-FILE) → END result=OK.
     * DOWNLOAD-FILE uses framed OK@size + per-chunk OK@0 + xsend (sama DA2).
     */
    @Throws(IOException::class)
    fun writePartition(entry: PartitionEntry, input: InputStream, sizeBytes: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
        if (sizeBytes > entry.sizeBytes) {
            throw IOException(
                "Image size ($sizeBytes bytes) > partisi '${entry.name}' (${entry.sizeBytes} bytes) — dibatalkan"
            )
        }
        if (sizeBytes <= 0L) {
            throw IOException("Ukuran image 0 untuk '${entry.name}'")
        }

        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>${DaCommands.CMD_WRITE_PARTITION}</command><arg>""" +
                """<partition>${entry.name}</partition>""" +
                """<source_file>${entry.name}.bin</source_file>""" +
                """</arg></da>"""
        onRawExchange?.invoke(
            "INFO",
            ("WRITE-PARTITION ${entry.name} size=0x${"%x".format(sizeBytes)}").toByteArray(),
        )
        xsendText(xml)

        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            try { finishCommandCycle("write ${entry.name}") } catch (_: Exception) {}
            throw IOException("WRITE-PARTITION '${entry.name}' ditolak: $first")
        }
        // Some DAs reply OK before issuing DOWNLOAD-FILE; others jump straight to cmds
        if (first != "OK" && !first.startsWith("OK") && xmlField(first, "command").isEmpty()) {
            throw IOException("WRITE-PARTITION '${entry.name}' ditolak: ${first.take(120)}")
        }

        var downloaded = false
        var guard = 0
        var pending: String? = if (first == "OK" || first.startsWith("OK") || xmlField(first, "command").isEmpty()) null else first
        while (guard++ < 80) {
            val pkt = pending ?: xreadFlow().text
            pending = null
            val cmd = xmlField(pkt, "command")
            when {
                cmd == DaCommands.CMD_PROGRESS_REPORT || cmd.endsWith("PROGRESS-REPORT") -> {
                    ack()
                    var msg = ""
                    var g = 0
                    while (msg != "OK!EOT" && g++ < 300) {
                        msg = try { xreadFlow().text } catch (_: Exception) { break }
                        if (msg == "OK!EOT" || msg.startsWith("OK")) {
                            ack()
                            if (msg == "OK!EOT") break
                        } else {
                            ack()
                        }
                    }
                }
                cmd == DaCommands.CMD_FILE_SYS_OPERATION || cmd.endsWith("FILE-SYS-OPERATION") -> {
                    // Penumbra: ACK then OK@{op result}
                    val key = xmlField(pkt, "key").ifEmpty { xmlField(pkt, "arg/key") }
                    ack()
                    val reply = when {
                        key.contains("SIZE", ignoreCase = true) ||
                            key.contains("FILE-SIZE", ignoreCase = true) ->
                            "0x${"%X".format(sizeBytes)}"
                        key.contains("EXISTS", ignoreCase = true) -> "NOT-EXISTS"
                        key.contains("MKDIR", ignoreCase = true) -> "MKDIR"
                        else -> "0x${"%X".format(sizeBytes)}"
                    }
                    // send as protocol-flow text with trailing NULs
                    val body = "OK@$reply".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0)
                    val hdr = le32(MAGIC) + le32(DT_PROTOCOL_FLOW.toLong()) + le32(body.size.toLong())
                    transport.write(hdr)
                    transport.write(body)
                    onRawExchange?.invoke("OUT", "OK@$reply".toByteArray())
                }
                cmd == DaCommands.CMD_DOWNLOAD_FILE || cmd.endsWith("DOWNLOAD-FILE") -> {
                    processHostDownloadFile(pkt, input, sizeBytes, chunkSize)
                    downloaded = true
                }
                cmd == DaCommands.CMD_END -> {
                    val result = xmlField(pkt, "result")
                    val message = xmlField(pkt, "message")
                    ack()
                    val ok = result.equals("OK", true) ||
                        result.equals(DaCommands.RESULT_SUCCESS, true) ||
                        result.isEmpty()
                    if (!ok) {
                        throw IOException(
                            "WRITE '${entry.name}' END gagal: result=$result msg=${message.take(160)}"
                        )
                    }
                    try {
                        val (scmd, _) = readCommandResult()
                        if (scmd == DaCommands.CMD_START) {
                            // session ready for next op
                        }
                    } catch (_: Exception) {}
                    if (!downloaded) {
                        throw IOException("WRITE '${entry.name}': END tanpa DOWNLOAD-FILE")
                    }
                    onRawExchange?.invoke(
                        "INFO",
                        ("WRITE-PARTITION ${entry.name} OK ($sizeBytes B)").toByteArray(),
                    )
                    return
                }
                pkt.equals("OK", true) || pkt.startsWith("OK") -> {
                    // initial OK after WRITE-PARTITION
                }
                cmd.isNotEmpty() -> {
                    onRawExchange?.invoke("INFO", "WRITE loop cmd=$cmd".toByteArray())
                    ack()
                }
            }
        }
        throw IOException("WRITE '${entry.name}': timeout menunggu CMD:END")
    }

    /**
     * Penumbra process_download_data:
     * ACK → OK@size → (read OK) → per chunk: OK@0 → OK → xsend(chunk) → OK
     */
    private fun processHostDownloadFile(
        downloadXml: String,
        input: InputStream,
        sizeBytes: Long,
        chunkSize: Int,
    ) {
        var packetLength = chunkSize.coerceIn(0x1000, 0x100000)
        val pl = xmlField(downloadXml, "packet_length")
        if (pl.isNotEmpty()) {
            packetLength = pl.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: packetLength
        }
        ack() // received DOWNLOAD-FILE
        // Announce total size (long-safe hex)
        run {
            val text = "OK@0x${"%x".format(sizeBytes)}"
            val body = text.toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0)
            val hdr = le32(MAGIC) + le32(DT_PROTOCOL_FLOW.toLong()) + le32(body.size.toLong())
            transport.write(hdr)
            transport.write(body)
            onRawExchange?.invoke("OUT", text.toByteArray())
        }
        // DA ACK
        val a1 = xreadFlow().text
        if (!a1.contains("OK")) {
            throw IOException("DOWNLOAD size ACK gagal: ${a1.take(80)}")
        }

        var sent = 0L
        val buf = ByteArray(packetLength)
        onProgress?.invoke(0L, sizeBytes)
        while (sent < sizeBytes) {
            val toRead = minOf(packetLength.toLong(), sizeBytes - sent).toInt()
            var off = 0
            while (off < toRead) {
                val n = input.read(buf, off, toRead - off)
                if (n < 0) throw IOException("EOF image di offset $sent")
                off += n
            }
            // OK@0 handshake
            ackValue(0)
            val a0 = xreadFlow().text
            if (!a0.contains("OK")) {
                throw IOException("DOWNLOAD chunk ACK gagal @ $sent: ${a0.take(60)}")
            }
            xsendBytes(buf.copyOf(toRead))
            val a2 = xreadFlow().text
            if (!a2.contains("OK")) {
                throw IOException("DOWNLOAD chunk data ACK gagal @ $sent: ${a2.take(60)}")
            }
            sent += toRead
            onProgress?.invoke(sent, sizeBytes)
        }
        onRawExchange?.invoke("INFO", ("DOWNLOAD-FILE selesai $sent B").toByteArray())
    }



    /**
     * Cleanly ends the DA session. Call this before disconnecting.
     */
    @Throws(IOException::class)
    fun end() {
        sendXmlCommand(DaCommands.CMD_END, mapOf("message" to "session complete"))
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val MAGIC = 0xFEEE_EEEFL
        const val DT_PROTOCOL_FLOW = 1
        const val DT_MESSAGE = 2
    }
}
