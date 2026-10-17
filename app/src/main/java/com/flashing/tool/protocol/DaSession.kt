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
    private fun xreadFlow(): XFlow {
        repeat(32) {
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
                onRawExchange?.invoke("IN-LOG", msg)
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
        throw IOException("DA xread: tidak ada protocol-flow setelah 32 paket log")
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
                    // Require CMD:START after successful END
                    try {
                        val next = xreadFlowExtended(15_000).text
                        val c2 = xmlField(next, "command")
                        if (c2 == DaCommands.CMD_START || next.equals("OK", true) || next.startsWith("OK")) {
                            ack()
                            return
                        }
                        throw IOException("DA2 END OK tapi bukan START: ${next.take(120)}")
                    } catch (e: IOException) {
                        if (e.message?.startsWith("DA2") == true) throw e
                        throw IOException(
                            "DA2 END tanpa CMD:START (hash/DRAM gagal atau jump gagal): ${e.message}"
                        )
                    }
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
        repeat(48) {
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
                onRawExchange?.invoke("IN-LOG", msg)
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
    @Throws(IOException::class)
    fun getHwInfo(): Map<String, String> = sendAndExpectOk(DaCommands.CMD_GET_HW_INFO)

    // ---- partition operations (MVP scope) ----

    /**
     * Reads the device's current partition table.
     * Response shape confirmed from binary:
     *   <partition_table version="1.0"><pt><name>..</name><start>0x..</start><size>0x..</size></pt>...</partition_table>
     */
    @Throws(IOException::class)
    fun readPartitionTable(): List<PartitionEntry> {
        // Full command cycle — DA may return table inside CMD:END message or as OK@length data.
        val xml = DaXml.buildCommand(DaCommands.CMD_READ_PARTITION_TABLE).toString(Charsets.UTF_8)
        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            try { finishCommandCycle("READ-PARTITION-TABLE unsupported") } catch (_: Exception) {}
            throw IOException("CMD:READ-PARTITION-TABLE tidak didukung DA ini: $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("READ-PARTITION-TABLE ditolak: ${first.take(120)}")
        }
        // Collect XML packets until CMD:END
        val collected = StringBuilder()
        var guard = 0
        while (guard++ < 30) {
            val pkt = xreadFlow().text
            val cmd = xmlField(pkt, "command")
            collected.append(pkt).append('\n')
            when (cmd) {
                DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""
                    var g = 0
                    while (msg != "OK!EOT" && g++ < 100) {
                        msg = xreadFlow().text
                        collected.append(msg)
                        ack()
                    }
                }
                DaCommands.CMD_END -> {
                    ack()
                    try {
                        val (scmd, _) = readCommandResult()
                        if (scmd.isNotEmpty() && scmd != DaCommands.CMD_START) {
                            onRawExchange?.invoke("INFO", "after table END got $scmd".toByteArray())
                        }
                    } catch (_: Exception) {
                    }
                    break
                }
                DaCommands.CMD_START -> {
                    // already acked
                    break
                }
                else -> {
                    // table body may be bare XML without command wrapper
                    ack()
                }
            }
        }
        val all = collected.toString()
        onRawExchange?.invoke("IN-XML", all.toByteArray())
        val rows = DaXml.parsePartitionTable(all)
        if (rows.isEmpty()) {
            throw IOException(
                "Tabel partisi kosong / tidak ter-parse. " +
                    "Mungkin perlu DA stage-2 (BOOT-TO). cuplikan=${all.take(200)}"
            )
        }
        return rows.map { (name, startBytes, sizeBytes) ->
            PartitionEntry(name = name, startSector = startBytes / 512L, sizeBytes = sizeBytes)
        }
    }

    /**
     * Reads [entry]'s content via CMD:UPLOAD-FILE, streaming the binary
     * payload into [output] in chunks.
     *
     * TODO verify: whether binary bytes immediately follow the XML reply on
     * the same read, or require a separate explicit "ready" handshake first.
     * This implementation assumes the DA's XML ack is followed directly by
     * `packet_length`-sized raw chunks until declared total size is reached.
     */
    /**
     * Read partition via xflash CMD:UPLOAD-FILE (DA → host raw bulk).
     */
    @Throws(IOException::class)
    fun readPartition(entry: PartitionEntry, output: OutputStream, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
        if (entry.sizeBytes <= 0L) throw IOException("Ukuran partisi '${entry.name}' 0")
        val pkt = chunkSize.coerceIn(0x1000, 0x100000)
        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>${DaCommands.CMD_UPLOAD_FILE}</command><arg>""" +
                """<partition>${entry.name}</partition>""" +
                """<start_address>0x${"%x".format(entry.startSector * 512L)}</start_address>""" +
                """<length>0x${"%x".format(entry.sizeBytes)}</length>""" +
                """<packet_length>0x${"%x".format(pkt)}</packet_length>""" +
                """</arg></da>"""
        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            try { finishCommandCycle("read ${entry.name}") } catch (_: Exception) {}
            throw IOException("UPLOAD-FILE ditolak untuk '${entry.name}': $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("UPLOAD-FILE ditolak: ${first.take(160)}")
        }

        var packetLength = pkt
        // Optional intermediate CMD from DA
        try {
            val peek = xreadFlow()
            val cmd = xmlField(peek.text, "command")
            when (cmd) {
                DaCommands.CMD_UPLOAD_FILE, "CMD:UPLOAD-FILE" -> {
                    val pl = xmlField(peek.text, "packet_length")
                    if (pl.isNotEmpty()) {
                        packetLength = pl.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: pkt
                    }
                    ack()
                }
                DaCommands.CMD_END -> {
                    ack()
                    try { readCommandResult() } catch (_: Exception) {}
                    throw IOException("DA menutup UPLOAD-FILE tanpa data: ${peek.text.take(120)}")
                }
                else -> {
                    if (!peek.text.equals("OK", true) && !peek.text.startsWith("OK")) {
                        // might already be binary — push back
                        transport.pushBack(peek.raw)
                    }
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
        }

        var remaining = entry.sizeBytes
        var done = 0L
        onProgress?.invoke(0L, entry.sizeBytes)
        while (remaining > 0) {
            val toRead = minOf(packetLength.toLong(), remaining).toInt()
            val chunk = transport.readExact(toRead, timeoutMs = 30_000)
            output.write(chunk)
            remaining -= chunk.size
            done += chunk.size
            onProgress?.invoke(done, entry.sizeBytes)
        }

        // Drain END + START
        var guard = 0
        while (guard++ < 40) {
            val pkt2 = try { xreadFlow() } catch (_: Exception) { break }
            val cmd = xmlField(pkt2.text, "command")
            when {
                pkt2.text == "OK!EOT" || pkt2.text.startsWith("OK@") -> ack()
                cmd == DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""; var g = 0
                    while (msg != "OK!EOT" && g++ < 100) {
                        msg = try { xreadFlow().text } catch (_: Exception) { break }
                        ack()
                    }
                }
                cmd == DaCommands.CMD_END -> {
                    ack()
                    try { readCommandResult() } catch (_: Exception) {}
                    return
                }
                cmd == DaCommands.CMD_START -> return
                else -> if (pkt2.text.equals("OK", true) || pkt2.text.startsWith("OK")) ack()
            }
        }
    }

    /**
     * Write image to partition via xflash CMD:DOWNLOAD-FILE.
     * Binary body is raw bulk (same as BOOT-TO / mtkclient), not xflash packets.
     * Sparse images must already be expanded by the caller (flashAll does this).
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

        val pkt = chunkSize.coerceIn(0x1000, 0x100000)
        val xml =
            """<?xml version="1.0" encoding="utf-8"?><da><version>1.0</version>""" +
                """<command>${DaCommands.CMD_DOWNLOAD_FILE}</command><arg>""" +
                """<partition>${entry.name}</partition>""" +
                """<start_address>0x${"%x".format(entry.startSector * 512L)}</start_address>""" +
                """<length>0x${"%x".format(sizeBytes)}</length>""" +
                """<packet_length>0x${"%x".format(pkt)}</packet_length>""" +
                """<checksum>${DaCommands.CHECKSUM_NONE}</checksum>""" +
                """</arg></da>"""

        xsendText(xml)
        val first = xreadFlow().text
        if (first == "ERR!UNSUPPORTED" || first.startsWith("ERR!")) {
            try { finishCommandCycle("write ${entry.name}") } catch (_: Exception) {}
            throw IOException("DOWNLOAD-FILE ditolak untuk '${entry.name}': $first")
        }
        if (first != "OK" && !first.startsWith("OK")) {
            throw IOException("DOWNLOAD-FILE ditolak untuk '${entry.name}': ${first.take(160)}")
        }

        // DA may echo CMD:DOWNLOAD-FILE with packet_length, or jump straight to data phase
        var packetLength = pkt
        try {
            val peek = xreadFlow()
            val cmd = xmlField(peek.text, "command")
            when (cmd) {
                DaCommands.CMD_DOWNLOAD_FILE -> {
                    val pl = xmlField(peek.text, "packet_length")
                    if (pl.isNotEmpty()) {
                        packetLength = pl.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: pkt
                    }
                    ack()
                }
                DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""; var g = 0
                    while (msg != "OK!EOT" && g++ < 50) { msg = xreadFlow().text; ack() }
                }
                DaCommands.CMD_END -> {
                    // rejected without data phase
                    ack()
                    try { readCommandResult() } catch (_: Exception) {}
                    throw IOException("DA menutup DOWNLOAD-FILE tanpa menerima data: ${peek.text.take(160)}")
                }
                else -> {
                    // Unexpected — push back not available for full packet; if it was OK-like continue
                    if (peek.text.contains("OK", true)) {
                        // already consumed
                    } else {
                        // Treat as start of binary phase already (rare)
                        transport.pushBack(peek.raw)
                    }
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            // timeout on optional intermediate packet — begin binary upload
        }

        var remaining = sizeBytes
        var done = 0L
        val buffer = ByteArray(packetLength)
        onProgress?.invoke(0L, sizeBytes)
        while (remaining > 0) {
            val toRead = minOf(packetLength.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, toRead)
            if (n <= 0) {
                throw IOException("Stream image habis lebih awal untuk '${entry.name}' (sisa $remaining B)")
            }
            val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
            transport.write(chunk)
            remaining -= n
            done += n
            onProgress?.invoke(done, sizeBytes)
        }

        // Drain progress + END + START
        var guard = 0
        while (guard++ < 40) {
            val pkt2 = try { xreadFlow() } catch (e: Exception) {
                if (guard > 2) break else throw e
            }
            val cmd = xmlField(pkt2.text, "command")
            when {
                pkt2.text == "OK!EOT" || pkt2.text.startsWith("OK@") -> ack()
                cmd == DaCommands.CMD_PROGRESS_REPORT -> {
                    ack()
                    var msg = ""; var g = 0
                    while (msg != "OK!EOT" && g++ < 100) {
                        msg = try { xreadFlow().text } catch (_: Exception) { break }
                        ack()
                    }
                }
                cmd == DaCommands.CMD_END -> {
                    val result = xmlField(pkt2.text, "result")
                    val message = xmlField(pkt2.text, "message")
                    ack()
                    try {
                        val (scmd, _) = readCommandResult()
                        if (scmd.isNotEmpty() && scmd != DaCommands.CMD_START) {
                            onRawExchange?.invoke("INFO", "after write END got $scmd".toByteArray())
                        }
                    } catch (_: Exception) {}
                    if (result.startsWith("ERR", true) ||
                        (result.isNotEmpty() &&
                            !result.equals("OK", true) &&
                            !result.equals(DaCommands.RESULT_SUCCESS, true) &&
                            result.contains("FAIL", true))
                    ) {
                        throw IOException("Gagal tulis '${entry.name}': $result $message")
                    }
                    return
                }
                cmd == DaCommands.CMD_START -> return
                else -> {
                    if (pkt2.text.equals("OK", true) || pkt2.text.startsWith("OK")) ack()
                    else onRawExchange?.invoke("IN-XML", pkt2.raw)
                }
            }
        }
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
