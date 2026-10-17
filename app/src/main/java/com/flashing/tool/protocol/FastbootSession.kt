package com.flashing.tool.protocol

import com.flashing.tool.usb.UsbTransport
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import com.flashing.tool.model.FlashGuards
import com.flashing.tool.model.FlashGuardException

/** Thrown when a logical partition flash requires userspace fastbootd. */
class NeedFastbootdException(message: String) : java.io.IOException(message)
/** Thrown when a physical partition flash should run from bootloader, not fastbootd. */
class NeedBootloaderException(message: String) : java.io.IOException(message)

/**
 * Host-side Fastboot protocol aligned with Android platform-tools 35.x behaviour:
 * sparse image expansion, multi-INFO getvar, flashing lock/unlock, set_active,
 * reboot targets, and optional vbmeta flag patching (--disable-verity /
 * --disable-verification) performed on the host before download.
 */
class FastbootSession(
    private val transport: UsbTransport,
    private val onLog: ((String) -> Unit)? = null,
) {
    data class Response(
        val status: String,
        val message: String,
        val allInfo: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = status == "OKAY"
    }

    data class FlashOptions(
        val disableVerity: Boolean = false,
        val disableVerification: Boolean = false,
        /**
         * A/B slot selector, matching platform-tools `--slot=`.
         * null = use partition name as typed (e.g. boot_a stays boot_a).
         * "a" / "b" = rewrite bare name to name_a / name_b.
         * "all" = flash both name_a and name_b.
         */
        val slot: String? = null,
        /** Allow flash to CRITICAL partitions (preloader/seccfg/otp/…). Default false. */
        val allowCritical: Boolean = false,
    )

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    @Throws(IOException::class)
    fun sendCommand(command: String): Response {
        val cmdBytes = command.toByteArray(Charsets.UTF_8)
        if (cmdBytes.size > 4096) throw IOException("Command too long (${cmdBytes.size})")
        log(">> $command")
        transport.write(cmdBytes)

        val infos = mutableListOf<String>()
        repeat(256) {
            val packet = transport.read(256, timeoutMs = 15_000)
            if (packet.isEmpty()) throw IOException("Empty response from device")
            val text = packet.decodeToString().trim { it <= ' ' || it == '\u0000' }
            val tag = if (text.length >= 4) text.substring(0, 4) else text
            val payload = if (text.length > 4) text.substring(4) else ""
            when (tag) {
                "INFO" -> {
                    log("<< INFO $payload")
                    infos.add(payload)
                }
                "OKAY" -> {
                    log("<< OKAY $payload")
                    return Response("OKAY", payload, infos)
                }
                "FAIL" -> {
                    log("<< FAIL $payload")
                    return Response("FAIL", payload, infos)
                }
                "DATA" -> {
                    log("<< DATA $payload")
                    return Response("DATA", payload, infos)
                }
                else -> {
                    log("<< RAW $text")
                    when {
                        text.startsWith("OKAY") ->
                            return Response("OKAY", text.removePrefix("OKAY"), infos)
                        text.startsWith("FAIL") ->
                            return Response("FAIL", text.removePrefix("FAIL"), infos)
                        text.startsWith("INFO") -> infos.add(text.removePrefix("INFO"))
                        text.startsWith("DATA") ->
                            return Response("DATA", text.removePrefix("DATA"), infos)
                        else -> throw IOException("Unknown fastboot response: $text")
                    }
                }
            }
        }
        throw IOException("Too many INFO packets without OKAY/FAIL/DATA")
    }

    @Throws(IOException::class)
    fun getVar(name: String): Response = sendCommand("getvar:$name")

    @Throws(IOException::class)
    fun getVarAll(): Response = sendCommand("getvar:all")

    @Throws(IOException::class)
    fun erase(partition: String): Response = sendCommand("erase:$partition")

    @Throws(IOException::class)
    fun reboot(target: String? = null): Response =
        if (target.isNullOrBlank()) sendCommand("reboot")
        else sendCommand("reboot-$target")

    @Throws(IOException::class)
    fun setActive(slot: String): Response = sendCommand("set_active:$slot")

    @Throws(IOException::class)
    fun flashingUnlock(): Response = sendCommand("flashing unlock")

    @Throws(IOException::class)
    fun flashingLock(): Response = sendCommand("flashing lock")

    @Throws(IOException::class)
    fun flashingUnlockCritical(): Response = sendCommand("flashing unlock_critical")

    @Throws(IOException::class)
    fun flashingLockCritical(): Response = sendCommand("flashing lock_critical")

    @Throws(IOException::class)
    fun flashingGetUnlockAbility(): Response = sendCommand("flashing get_unlock_ability")

    // ---- fastbootd / logical partitions (Android super) ----

    /** True when running userspace fastbootd (`getvar:is-userspace` → yes). */
    fun isUserspace(): Boolean {
        val r = getVar("is-userspace")
        val v = (r.message + r.allInfo.joinToString()).lowercase()
        return v.contains("yes") || v.trim() == "yes"
    }

    /** `getvar:is-logical:<partition>` — true for dynamic partitions inside super. */
    fun isLogical(partition: String): Boolean {
        return try {
            val r = sendCommand("getvar:is-logical:$partition")
            val v = (r.message + " " + r.allInfo.joinToString(" ")).lowercase()
            when {
                v.contains("yes") -> true
                v.contains("no") -> false
                else -> partition.lowercase() in LIKELY_LOGICAL
            }
        } catch (_: Exception) {
            partition.lowercase() in LIKELY_LOGICAL
        }
    }

    /**
     * Resize a logical partition before flashing a larger image (fastbootd).
     * Size is in bytes; device must be in fastbootd.
     */
    fun resizeLogicalPartition(partition: String, sizeBytes: Long): Response =
        sendCommand("resize-logical-partition:$partition:$sizeBytes")

    /** Boot into userspace fastbootd (needed to flash system/vendor/product). */
    fun rebootFastbootd(): Response = sendCommand("reboot-fastboot")

    fun rebootBootloader(): Response = sendCommand("reboot-bootloader")

    /** Cancel a pending snapshot-based OTA merge that blocks flashes. */
    fun snapshotUpdateCancel(): Response {
        return try {
            sendCommand("snapshot-update:cancel")
        } catch (e: Exception) {
            sendCommand("snapshot-update cancel")
        }
    }

    /** Finish merging a snapshot-based update (when device requires it). */
    fun snapshotUpdateMerge(): Response {
        return try {
            sendCommand("snapshot-update:merge")
        } catch (e: Exception) {
            sendCommand("snapshot-update merge")
        }
    }

    /**
     * Prepare environment for flashing [partition] of [imageSize] bytes:
     * - cancel blocking snapshot if reported
     * - if partition is logical and we are in fastbootd, resize to fit image
     * - if partition is logical but we are NOT in fastbootd, return a warning
     *   Response so the UI can offer reboot-fastboot
     */
    /**
     * Decide whether current mode (bootloader vs fastbootd) matches [partition]:
     * - logical → need userspace fastbootd (+ resize)
     * - physical → prefer classic bootloader; if currently in fastbootd, signal NEED_BOOTLOADER
     */
    fun prepareFlashMode(partition: String, imageSize: Long): Response {
        try {
            val snap = getVar("snapshot-update-status")
            val st = (snap.message + snap.allInfo.joinToString()).lowercase()
            if (st.contains("merging") || st.contains("snapshotted") || st.contains("pending")) {
                log("Snapshot tertunda terdeteksi ($st) — mencoba cancel…")
                snapshotUpdateCancel()
            }
        } catch (_: Exception) {
        }

        val logical = try {
            isLogical(partition)
        } catch (_: Exception) {
            partition.lowercase() in LIKELY_LOGICAL
        }

        val userspace = try {
            isUserspace()
        } catch (_: Exception) {
            false
        }

        if (logical) {
            if (!userspace) {
                return Response(
                    "NEED_FASTBOOTD",
                    "Partisi '$partition' logical (super) — perlu fastbootd."
                )
            }
            if (imageSize > 0) {
                log("Resize logical $partition → $imageSize bytes")
                val rz = resizeLogicalPartition(partition, imageSize)
                if (!rz.ok && rz.status == "FAIL") {
                    log("resize-logical-partition gagal: ${rz.message} (lanjut flash tetap dicoba)")
                }
            }
            return Response("OKAY", "logical-ready")
        }

        // Physical partition (boot, vbmeta, recovery, …)
        if (userspace) {
            return Response(
                "NEED_BOOTLOADER",
                "Partisi '$partition' bukan logical — lebih tepat di bootloader fastboot, bukan fastbootd."
            )
        }
        return Response("OKAY", "physical-ready")
    }

    /** Approximate `fastboot -w`: erase userdata, cache, metadata. */
    @Throws(IOException::class)
    fun wipe(): Response {
        val parts = listOf("userdata", "cache", "metadata")
        val messages = mutableListOf<String>()
        for (p in parts) {
            val r = try {
                erase(p)
            } catch (e: Exception) {
                log("erase:$p error: ${e.message}")
                continue
            }
            messages.add("$p -> ${r.status} ${r.message}".trim())
        }
        return Response("OKAY", messages.joinToString("; "))
    }

    /**
     * Query `getvar:current-slot` (returns "a" or "b", or null if unavailable).
     */
    fun currentSlot(): String? {
        return try {
            val r = getVar("current-slot")
            val v = r.message.trim().lowercase()
            when {
                v == "a" || v == "_a" -> "a"
                v == "b" || v == "_b" -> "b"
                v.endsWith("a") && v.length <= 2 -> "a"
                v.endsWith("b") && v.length <= 2 -> "b"
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Query `getvar:has-slot:<name>` — true when the base partition is A/B slotable.
     * Falls back to a built-in list of common slotable names when the var is missing.
     */
    fun hasSlot(partition: String): Boolean {
        val base = partition.removeSuffix("_a").removeSuffix("_b")
            .removeSuffix("_A").removeSuffix("_B")
        return try {
            val r = sendCommand("getvar:has-slot:$base")
            val v = r.message.trim().lowercase()
            v == "yes" || v == "true" || v.startsWith("yes")
        } catch (_: Exception) {
            base.lowercase() in SLOTABLE_BASE_NAMES
        }
    }

    /**
     * Resolve partition name(s) according to platform-tools `--slot=` semantics.
     *
     * - Explicit suffix (`boot_a`, `vendor_boot_b`) → kept as-is
     * - `--slot=a|b` → rewrite bare name to name_a / name_b
     * - `--slot=all` → both name_a and name_b
     * - No `--slot` and partition is slotable → use **current-slot** from device
     *   (same as platform-tools: `fastboot flash vendor_boot` → vendor_boot_a
     *   when current-slot is a)
     * - Non-slotable partitions (userdata, cache, …) → unchanged
     */
    fun resolveSlotPartitions(partition: String, slot: String?): List<String> {
        val alreadySlotted = partition.endsWith("_a", ignoreCase = true) ||
            partition.endsWith("_b", ignoreCase = true)

        val base = when {
            partition.endsWith("_a", ignoreCase = true) -> partition.dropLast(2)
            partition.endsWith("_b", ignoreCase = true) -> partition.dropLast(2)
            else -> partition
        }

        val s = slot?.lowercase()?.trim().orEmpty()

        // Explicit --slot always wins
        if (s.isNotEmpty()) {
            return when (s) {
                "a" -> listOf("${base}_a")
                "b" -> listOf("${base}_b")
                "all" -> listOf("${base}_a", "${base}_b")
                else -> listOf(partition)
            }
        }

        // Already has _a / _b → keep
        if (alreadySlotted) return listOf(partition)

        // No --slot: if slotable, resolve to current active slot (platform-tools behaviour)
        if (hasSlot(base)) {
            val cur = currentSlot()
            if (cur != null) {
                log("current-slot=$cur → $base → ${base}_$cur")
                return listOf("${base}_$cur")
            }
            // Device did not report current-slot; leave bare name (device may accept it)
            log("current-slot tidak tersedia — flash ke '$partition' apa adanya")
        }

        return listOf(partition)
    }

    /**
     * Flash [input] of known [size] to [partition], expanding sparse images and
     * optionally patching vbmeta flags (platform-tools --disable-verity /
     * --disable-verification). Honours [FlashOptions.slot] (`--slot=a|b|all`).
     *
     * For `--slot=all` the same image is flashed to both `_a` and `_b`.
     * [input] is fully buffered when more than one target is required.
     */
    @Throws(IOException::class)
    fun flash(
        partition: String,
        input: InputStream,
        size: Long,
        options: FlashOptions = FlashOptions(),
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Response {
        val targets = resolveSlotPartitions(partition, options.slot)
        if (targets.isEmpty()) throw IOException("No target partition resolved")

        // Safety: CRITICAL partitions blocked unless options.allowCritical
        val policy = FlashGuards.Policy(allowCritical = options.allowCritical)
        when (val d = FlashGuards.evaluateFastbootPartition(partition, size, policy)) {
            is FlashGuards.Decision.Block -> {
                log("GUARD: ${d.reason}")
                throw FlashGuardException(d.reason)
            }
            is FlashGuards.Decision.Allow -> d.notes.forEach { log("GUARD note: $it") }
        }
        for (tg in targets) {
            when (val d2 = FlashGuards.evaluateFastbootPartition(tg, size, policy)) {
                is FlashGuards.Decision.Block -> throw FlashGuardException(d2.reason)
                is FlashGuards.Decision.Allow -> { }
            }
        }

        if (targets.size == 1 && targets[0] != partition) {
            log("Target partition: ${targets[0]} (dari '$partition', current-slot / --slot)")
        } else if (targets.size > 1) {
            log("Target partitions: ${targets.joinToString(", ")} (--slot=all)")
        }

        // When flashing multiple slots we must re-read the image; buffer once.
        val buffered: ByteArray? = if (targets.size > 1) {
            log("Buffering image for ${targets.size} slots…")
            input.readBytes()
        } else null

        val results = mutableListOf<String>()
        for ((idx, target) in targets.withIndex()) {
            if (targets.size > 1) {
                log("---------- slot ${idx + 1}/${targets.size}: $target ----------")
            }
            val src: InputStream = if (buffered != null) {
                ByteArrayInputStream(buffered)
            } else {
                input
            }
            val srcSize = buffered?.size?.toLong() ?: size
            val r = flashSingle(target, src, srcSize, options, onProgress)
            results.add("$target → ${r.status}".trim())
            if (!r.ok) {
                return Response(
                    r.status,
                    "FAILED on $target: ${r.message} | ${results.joinToString("; ")}",
                    r.allInfo,
                )
            }
        }
        val summary = if (targets.size == 1) {
            "OKAY — flashed '${targets[0]}'"
        } else {
            "OKAY — flashed ${targets.joinToString(", ")}"
        }
        return Response("OKAY", summary)
    }

    @Throws(IOException::class)
    private fun flashSingle(
        partition: String,
        input: InputStream,
        size: Long,
        options: FlashOptions,
        onProgress: ((Long, Long) -> Unit)?,
    ): Response {
        // Peek for sparse magic or load into memory if vbmeta patch needed
        val push = PushbackInputStream(input, 28)
        val head = ByteArray(28)
        val n = push.read(head)
        if (n > 0) push.unread(head, 0, n)

        val isVbmetaPart = partition.startsWith("vbmeta", ignoreCase = true)
        val needVbmetaPatch = isVbmetaPart &&
            (options.disableVerity || options.disableVerification)

        val stream: InputStream
        val sendSize: Long

        if (n >= 4 && SparseImageInputStream.looksSparse(head)) {
            log("Sparse image detected — expanding (platform-tools compatible)")
            val expander = SparseImageExpandingStream(push)
            stream = expander
            sendSize = expander.expandedSize()
            log("Sparse expanded size: $sendSize bytes")
        } else if (needVbmetaPatch) {
            log("Patching vbmeta flags (verity=${options.disableVerity}, verification=${options.disableVerification})")
            val bytes = push.readBytes()
            val patched = VbmetaPatcher.applyFlags(
                bytes,
                disableVerity = options.disableVerity,
                disableVerification = options.disableVerification,
            )
            stream = ByteArrayInputStream(patched)
            sendSize = patched.size.toLong()
        } else {
            stream = push
            sendSize = size
        }

        if (sendSize <= 0) throw IOException("Nothing to flash (size=$sendSize)")
        if (sendSize > 0xFFFFFFFFL) throw IOException("Image too large for fastboot download")

        val prep = prepareFlashMode(partition, sendSize)
        when (prep.status) {
            "NEED_FASTBOOTD" -> throw NeedFastbootdException(prep.message)
            "NEED_BOOTLOADER" -> throw NeedBootloaderException(prep.message)
        }

        return stream.use { ins ->
            downloadAndFlash(partition, ins, sendSize, onProgress)
        }
    }

    @Throws(IOException::class)
    private fun downloadAndFlash(
        partition: String,
        input: InputStream,
        size: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ): Response {
        val sizeKb = (size + 1023) / 1024
        // Platform-tools style progress lines
        log("Sending '$partition' ($sizeKb KB)...")
        val t0 = System.nanoTime()

        val dl = sendCommand("download:%08x".format(size))
        if (dl.status != "DATA") {
            throw IOException("download rejected: ${dl.status} ${dl.message}")
        }

        var remaining = size
        val buf = ByteArray(DEFAULT_CHUNK)
        var done = 0L
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) throw IOException("Stream ended early at $done / $size")
            transport.write(if (read == buf.size) buf else buf.copyOf(read))
            remaining -= read
            done += read
            onProgress?.invoke(done, size)
        }

        val after = readStatusOnly(timeoutMs = 180_000)
        val sendSec = (System.nanoTime() - t0) / 1_000_000_000.0
        if (!after.ok) {
            log("FAILED (${"%.3f".format(sendSec)}s)")
            throw IOException("Data phase failed: ${after.status} ${after.message}")
        }
        log("OKAY (${"%.3f".format(sendSec)}s)")

        log("Writing '$partition'...")
        val t1 = System.nanoTime()
        val flashResp = sendCommand("flash:$partition")
        val writeSec = (System.nanoTime() - t1) / 1_000_000_000.0
        if (flashResp.ok) {
            log("OKAY (${"%.3f".format(writeSec)}s)")
            log("Finished. Total time: ${"%.3f".format(sendSec + writeSec)}s")
            log("Flashed → $partition")
        } else {
            log("FAILED (${"%.3f".format(writeSec)}s)")
            log("Flash '$partition' FAIL: ${flashResp.message}")
        }
        // Enrich message so UI shows the concrete target partition
        return flashResp.copy(
            message = buildString {
                append("partition=$partition")
                if (flashResp.message.isNotBlank()) append(" ${flashResp.message}")
            }.trim()
        )
    }

    private fun readStatusOnly(timeoutMs: Int = 60_000): Response {
        val infos = mutableListOf<String>()
        repeat(64) {
            val packet = transport.read(256, timeoutMs = timeoutMs)
            if (packet.isEmpty()) throw IOException("Empty status after data")
            val text = packet.decodeToString().trim { it <= ' ' || it == '\u0000' }
            when {
                text.startsWith("INFO") -> {
                    infos.add(text.removePrefix("INFO"))
                    log("<< INFO ${text.removePrefix("INFO")}")
                }
                text.startsWith("OKAY") -> {
                    log("<< OKAY ${text.removePrefix("OKAY")}")
                    return Response("OKAY", text.removePrefix("OKAY"), infos)
                }
                text.startsWith("FAIL") -> {
                    log("<< FAIL ${text.removePrefix("FAIL")}")
                    return Response("FAIL", text.removePrefix("FAIL"), infos)
                }
            }
        }
        throw IOException("No OKAY/FAIL after data phase")
    }

    /**
     * Parses a single user-typed line in platform-tools style.
     * Accepts optional leading "fastboot " or "adb ", common flags, and both
     * space-separated and colon forms (e.g. "getvar all" / "getvar:all").
     *
     * Flash / boot / stage that require a local image still need the UI file
     * picker (Android Storage Access Framework cannot take raw host paths).
     */
    @Throws(IOException::class)
    fun executeLine(line: String): Response {
        var rest = line.trim()
        // Strip optional CLI tool prefix (platform-tools style)
        when {
            rest.startsWith("fastboot ", ignoreCase = true) ->
                rest = rest.substring(9).trim()
            rest.startsWith("adb ", ignoreCase = true) ->
                rest = rest.substring(4).trim()
        }
        if (rest.isEmpty()) throw IOException("Empty command")

        // Collect simple flags (platform-tools)
        var disableVerity = false
        var disableVerification = false
        var slotFlag: String? = null
        val tokens = rest.split(Regex("\\s+")).toMutableList()
        val filtered = mutableListOf<String>()
        for (t in tokens) {
            when {
                t.equals("--disable-verity", ignoreCase = true) ||
                    t.equals("--disable_verity", ignoreCase = true) ->
                    disableVerity = true
                t.equals("--disable-verification", ignoreCase = true) ||
                    t.equals("--disable_verification", ignoreCase = true) ->
                    disableVerification = true
                t.startsWith("--slot=", ignoreCase = true) -> {
                    slotFlag = t.substringAfter("=").lowercase().trim()
                    if (slotFlag !in setOf("a", "b", "all")) {
                        throw IOException("--slot must be a, b, or all (got: $slotFlag)")
                    }
                }
                t.equals("--slot", ignoreCase = true) -> {
                    // next token is the value; handled below by peeking
                    filtered.add("__SLOT_NEXT__")
                }
                t == "-w" && filtered.isEmpty() -> {
                    // bare "-w" is handled as wipe below
                    filtered.add(t)
                }
                // Ignore local path / image filename tokens (SAF picks the file)
                t.lowercase().let { l ->
                    l.endsWith(".img") || l.endsWith(".bin") || l.endsWith(".raw") ||
                        l.endsWith(".sparse") || t.contains('/') || t.contains('\\')
                } -> { /* skip */ }
                else -> filtered.add(t)
            }
        }
        // Resolve "--slot a" (space form)
        val resolved = mutableListOf<String>()
        var i = 0
        while (i < filtered.size) {
            if (filtered[i] == "__SLOT_NEXT__") {
                val v = filtered.getOrNull(i + 1)?.lowercase()
                    ?: throw IOException("--slot requires a value (a|b|all)")
                if (v !in setOf("a", "b", "all")) {
                    throw IOException("--slot must be a, b, or all (got: $v)")
                }
                slotFlag = v
                i += 2
            } else {
                resolved.add(filtered[i])
                i++
            }
        }
        filtered.clear()
        filtered.addAll(resolved)
        if (filtered.isEmpty()) throw IOException("Empty command after flags")

        // Normalize verb that already contains colon (getvar:product, erase:userdata, …)
        val first = filtered[0]
        val colonIdx = first.indexOf(':')
        val verb: String
        val args: List<String>
        if (colonIdx > 0) {
            verb = first.substring(0, colonIdx).lowercase()
            val afterColon = first.substring(colonIdx + 1)
            args = if (afterColon.isNotEmpty()) {
                listOf(afterColon) + filtered.drop(1)
            } else {
                filtered.drop(1)
            }
        } else {
            verb = first.lowercase()
            args = filtered.drop(1)
        }

        return when (verb) {
            "devices" -> Response("OKAY", "this-device\tfastboot")
            "help", "--help", "-h" -> {
                val help = """
                    |Perintah yang didukung (gaya platform-tools):
                    |  devices
                    |  getvar <name|all>          getvar:all
                    |  erase <partition>          erase:userdata
                    |  flash <partition>          (pilih file via UI)
                    |  flash boot_a / vendor_boot_b            (slot eksplisit)
                    |  --slot=a|b|all flash boot               (rewrite / dual-slot)
                    |  flash --disable-verity --disable-verification vbmeta --slot=all
                    |  (flag boleh di mana saja; file path diabaikan → pilih via UI)
                    |  reboot [bootloader|fastboot|recovery]
                    |  reboot-bootloader | reboot-fastboot | reboot-recovery
                    |  continue
                    |  -w | wipe
                    |  set_active <a|b>
                    |  flashing unlock|lock|unlock_critical|lock_critical|get_unlock_ability
                    |  oem <…>
                    |  snapshot-update cancel|merge
                    |  resize-logical-partition <name> <size>
                    |  is-logical <partition>
                    |  (perintah lain dikirim mentah ke perangkat)
                """.trimMargin()
                Response("OKAY", help)
            }
            "getvar" -> {
                val name = args.firstOrNull() ?: "all"
                if (name.equals("all", true)) getVarAll() else getVar(name)
            }
            "erase" -> {
                val part = args.firstOrNull() ?: throw IOException("erase requires partition")
                val targets = resolveSlotPartitions(part, slotFlag)
                val messages = mutableListOf<String>()
                for (t in targets) {
                    val r = erase(t)
                    messages.add("$t -> ${r.status} ${r.message}".trim())
                    if (!r.ok) return Response(r.status, messages.joinToString("; "), r.allInfo)
                }
                Response("OKAY", messages.joinToString("; "))
            }
            "reboot" -> {
                val target = args.firstOrNull()
                when {
                    target == null -> reboot()
                    target.equals("bootloader", true) -> reboot("bootloader")
                    target.equals("fastboot", true) -> reboot("fastboot")
                    target.equals("recovery", true) -> reboot("recovery")
                    else -> reboot(target)
                }
            }
            "reboot-bootloader", "reboot_bootloader" -> reboot("bootloader")
            "reboot-fastboot", "reboot_fastboot" -> rebootFastbootd()
            "reboot-recovery", "reboot_recovery" -> reboot("recovery")
            "continue" -> sendCommand("continue")
            "set_active", "set-active", "--set-active" -> {
                val slot = args.firstOrNull()?.removePrefix("=")
                    ?: throw IOException("set_active requires slot (a|b)")
                setActive(slot)
            }
            "flashing" -> {
                val sub = args.firstOrNull()?.lowercase()
                    ?: throw IOException("flashing requires unlock|lock|…")
                when (sub) {
                    "unlock" -> flashingUnlock()
                    "lock" -> flashingLock()
                    "unlock_critical" -> flashingUnlockCritical()
                    "lock_critical" -> flashingLockCritical()
                    "get_unlock_ability" -> flashingGetUnlockAbility()
                    else -> sendCommand("flashing ${args.joinToString(" ")}")
                }
            }
            "oem" -> sendCommand("oem ${args.joinToString(" ")}")
            "snapshot-update", "snapshot_update" -> {
                val sub = args.firstOrNull()?.lowercase() ?: "cancel"
                when (sub) {
                    "cancel" -> snapshotUpdateCancel()
                    "merge" -> snapshotUpdateMerge()
                    else -> sendCommand("snapshot-update:$sub")
                }
            }
            "resize-logical-partition", "resize_logical_partition" -> {
                if (args.size < 2) throw IOException("resize-logical-partition <name> <size_bytes>")
                val name = args[0]
                val sz = args[1].toLongOrNull()
                    ?: throw IOException("ukuran tidak valid: ${args[1]}")
                resizeLogicalPartition(name, sz)
            }
            "is-logical", "is_logical" -> {
                val name = args.firstOrNull() ?: throw IOException("is-logical <partition>")
                val yes = isLogical(name)
                Response("OKAY", if (yes) "yes" else "no")
            }
            "-w", "wipe" -> wipe()
            "flash" -> {
                // Stash flags so the UI flash path can reuse them after file pick
                lastFlashOptions = FlashOptions(disableVerity, disableVerification, slotFlag)
                val hint = when {
                    slotFlag != null -> " (slot=$slotFlag)"
                    else -> ""
                }
                throw IOException(
                    "Untuk 'flash <partition>$hint' ketik flash <partition> " +
                        "lalu pilih file di UI (SAF tidak menerima path host mentah). " +
                        "Contoh: flash boot_a | --slot=all flash boot | flash vendor_boot_b"
                )
            }
            "boot", "stage", "download" -> {
                throw IOException(
                    "'$verb' membutuhkan file lokal. Gunakan tombol Flash File / " +
                        "fitur UI terkait (SAF tidak menerima path host mentah)."
                )
            }
            else -> {
                // Intelligent passthrough for platform-tools compatibility:
                // - verb:arg already normalized above
                // - single-arg protocol commands prefer colon form
                // - multi-arg or space-style (oem …) keep spaces
                when {
                    args.isEmpty() -> sendCommand(verb)
                    args.size == 1 && COLON_VERBS.contains(verb) ->
                        sendCommand("$verb:${args[0]}")
                    else -> sendCommand((listOf(verb) + args).joinToString(" "))
                }
            }
        }.also {
            lastFlashOptions = FlashOptions(disableVerity, disableVerification, slotFlag)
        }
    }

    /** Last flags seen in executeLine (for UI to reuse). */
    var lastFlashOptions: FlashOptions = FlashOptions()
        private set

    companion object {
        const val DEFAULT_CHUNK = 1 * 1024 * 1024

        /**
         * Protocol verbs that take a single argument in colon form
         * (`verb:arg`) on the wire. Space form typed by the user is
         * normalized to colon form for these.
         */
        private val COLON_VERBS = setOf(
            "getvar", "erase", "download", "flash", "boot", "stage",
            "get_staged", "upload", "resize-logical-partition",
            "is-logical", "snapshot-update", "set_active",
        )

        /**
         * Common A/B (slotable) base partition names. Used when
         * `getvar:has-slot:<name>` is not supported by the bootloader.
         * Matches typical Pixel / OEM A/B layouts.
         */
        private val SLOTABLE_BASE_NAMES = setOf(
            "boot", "vendor_boot", "init_boot", "dtbo", "recovery",
            "vbmeta", "vbmeta_system", "vbmeta_vendor", "vbmeta_odm",
            "pvmfw", "blkdiscard",
            // Some OEMs also slot these outside super
            "lk", "lk2", "logo", "tee", "md1img", "spmfw", "sspm",
            "gz", "scp", "mcupm", "pi_img", "dpm",
        )

        /**
         * Partitions that normally live inside `super` (dynamic partitions).
         * Used only as a fallback when `getvar:is-logical:<partition>` is
         * unsupported or fails. Slot-suffixed names (_a / _b) are included.
         */
        private val LOGICAL_BASE_NAMES = setOf(
            "system", "system_ext", "system_dlkm",
            "vendor", "vendor_dlkm",
            "product",
            "odm", "odm_dlkm",
            "mi_ext",
            "my_product", "my_engineering", "my_company", "my_carrier",
            "my_region", "my_heytap", "my_stock", "my_preload", "my_bigball", "my_manifest",
        )

        val LIKELY_LOGICAL: Set<String> =
            LOGICAL_BASE_NAMES + LOGICAL_BASE_NAMES.map { "${it}_a" } + LOGICAL_BASE_NAMES.map { "${it}_b" }
    }
}

/**
 * Streaming sparse expander that reports total expanded size after reading the
 * sparse header, then yields raw bytes chunk by chunk.
 */
private class SparseImageExpandingStream(
    private val input: InputStream,
) : InputStream() {
    private val expander: SparseImageInputStream = SparseImageInputStream(input)
    private var sizeKnown = false
    private var size = 0L

    fun expandedSize(): Long {
        if (!sizeKnown) {
            // Force header parse via available/read probe
            val probe = ByteArray(1)
            val markSupported = false
            // SparseImageInputStream parses on first read; use rawSize after peek
            // by reading 0 bytes worth through ensure — call read and push back not possible
            // Instead: SparseImageInputStream.ensureHeader is private; trigger via rawSize
            // which calls ensureHeader
            size = try {
                // Trigger header parse: read one byte then we lose it — so use rawSize
                // property which calls ensureHeader without consuming expanded data
                val s = expander.rawSize
                if (s < 0) throw IOException("Not a sparse image")
                s
            } catch (e: Exception) {
                throw IOException("Failed to parse sparse header: ${e.message}", e)
            }
            sizeKnown = true
        }
        return size
    }

    override fun read(): Int = expander.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = expander.read(b, off, len)
    override fun close() = expander.close()
}
