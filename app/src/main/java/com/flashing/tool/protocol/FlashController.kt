package com.flashing.tool.protocol

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.flashing.tool.model.PartitionEntry
import com.flashing.tool.model.FlashGuards
import com.flashing.tool.model.FlashGuardException
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import com.flashing.tool.usb.UsbTransport
import java.io.InputStream
import java.io.OutputStream

/**
 * Structured log events emitted by FlashController / protocol layers.
 * The UI maps each subtype to a colour and severity so the user can
 * instantly see success vs failure vs raw protocol noise.
 */
sealed class FlashLogEvent {
    /** Neutral informational progress message. */
    data class Info(val message: String) : FlashLogEvent()
    /** Operation completed successfully. */
    data class Success(val message: String) : FlashLogEvent()
    /** Non-fatal warning that still allows the flow to continue. */
    data class Warning(val message: String) : FlashLogEvent()
    /** Raw protocol bytes (hex dump) for deep debugging. */
    data class Raw(val direction: String, val hex: String) : FlashLogEvent()
    /** Fatal or operation-stopping error. [cause] may carry a stack trace. */
    data class Error(val message: String, val cause: Throwable? = null) : FlashLogEvent()
    data class Progress(val bytesDone: Long, val bytesTotal: Long) : FlashLogEvent()
    /** Emitted during flashAll() between partitions — overall job progress. */
    data class PartitionStep(val index: Int, val total: Int, val partitionName: String) : FlashLogEvent()
    /**
     * High-level connect phase for the status bar / progress indicator.
     * [step] is 1-based; [total] is typically 7 for the BROM→DA bootstrap.
     */
    data class Phase(val step: Int, val total: Int, val label: String) : FlashLogEvent()
}

/**
 * One entry in a full-flash job: which partition to write, and where its
 * image file lives. [resolveInput] is called lazily right before that
 * partition's turn, so the UI layer controls how files are opened (e.g. via
 * SAF DocumentFile / ContentResolver) without FlashController needing to know
 * about Uri/Context.
 */
class FlashJobItem(
    val entry: PartitionEntry,
    val fileSizeBytes: Long,
    val resolveInput: () -> InputStream,
)

class FlashAbortedException(message: String) : Exception(message)

/**
 * Orchestrates the full flow: open USB transport -> BROM sync -> send DA ->
 * hand off to DaSession -> expose partition operations to the UI.
 *
 * This is the single entry point the UI should talk to; it owns the lifecycle
 * of the lower-level sessions so the UI doesn't have to juggle BROM vs DA state
 * itself.
 */
class FlashController(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onEvent: (FlashLogEvent) -> Unit,
) : AutoCloseable {

    private var transport: UsbTransport? = null
    private var daSession: DaSession? = null

    private fun logRaw(direction: String, bytes: ByteArray) {
        onEvent(FlashLogEvent.Raw(direction, ByteUtil.toHex(bytes)))
    }

    /**
     * Full connect sequence: open transport, sync BROM, identify chip, send DA,
     * jump into it, and sync the DA session. After this returns successfully,
     * [readPartitionTable] / [readPartition] / [writePartition] are usable.
     */
    @Throws(Exception::class)
    fun connectAndBoostrapDa(daPayload: ByteArray, daLoadAddress: Long) {
        onEvent(FlashLogEvent.Info("=== MULAI KONEKSI ==="))
        onEvent(FlashLogEvent.Info("USB device: ${device.deviceName}"))
        onEvent(FlashLogEvent.Info("VID=0x%04X PID=0x%04X".format(device.vendorId, device.productId)))
        onEvent(FlashLogEvent.Info("DA payload size: ${daPayload.size} bytes"))
        onEvent(FlashLogEvent.Info("DA load address: 0x%08X".format(daLoadAddress)))

        onEvent(FlashLogEvent.Phase(1, 7, "Membuka transport USB…"))
        onEvent(FlashLogEvent.Info("[1/7] Membuka transport USB..."))
        val t = try {
            UsbTransport(usbManager, device).also { it.open() }
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Error("Gagal membuka USB transport: ${e.message}", e))
            throw e
        }
        transport = t
        onEvent(FlashLogEvent.Success("Transport USB terbuka"))

        onEvent(FlashLogEvent.Phase(2, 7, "Handshake BROM…"))
        onEvent(FlashLogEvent.Info("[2/7] Sinkronisasi BROM (handshake 0xA0 0x0A 0x50 0x05)..."))
        val brom = BromSession(t, onRawExchange = ::logRaw)
        try {
            brom.sync()
            onEvent(FlashLogEvent.Success("BROM handshake berhasil"))
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Error("BROM handshake gagal: ${e.message}", e))
            throw e
        }

        onEvent(FlashLogEvent.Phase(3, 7, "Membaca identitas chip…"))
        onEvent(FlashLogEvent.Info("[3/7] Membaca identitas chip..."))
        val identity = try {
            brom.getChipIdentity()
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Error("Gagal membaca identitas chip: ${e.message}", e))
            throw e
        }
        onEvent(
            FlashLogEvent.Success(
                "Chip: hwCode=0x%04X hwSubCode=0x%04X hwVer=0x%04X swVer=0x%04X".format(
                    identity.hwCode, identity.hwSubCode, identity.hwVersion, identity.swVersion
                )
            )
        )
        FlashSessionHolder.lastChipSummary =
            "hwCode=0x%04X  hwSub=0x%04X  hwVer=0x%04X  swVer=0x%04X".format(
                identity.hwCode, identity.hwSubCode, identity.hwVersion, identity.swVersion
            )

        // Parse DA file: BROM needs stage-1 only (not the full .bin header+all stages).
        // Matches Penumbra/mtkclient behaviour for MTK_DA_v6 (e.g. DAMT6789).
        val parsed = DaLoader.parse(
            daPayload,
            preferredHwCode = identity.hwCode,
            fallbackAddress = daLoadAddress,
        )
        onEvent(
            FlashLogEvent.Info(
                "DA parse: ${parsed.versionLabel}, ${parsed.stages.size} stage(s); " +
                    "BROM stage len=${parsed.bromStage.payload.size} @ 0x%08X sigLen=%d"
                        .format(parsed.bromStage.loadAddress, parsed.bromStage.sigLen)
            )
        )
        parsed.stages.forEachIndexed { i, st ->
            onEvent(
                FlashLogEvent.Info(
                    "  stage[$i]: ${st.payload.size} B @ 0x%08X (fileOff=0x%X sig=%d)"
                        .format(st.loadAddress, st.fileOffset, st.sigLen)
                )
            )
        }

        val stageAttempts = buildList {
            add(parsed.bromStage)
            parsed.stages.forEach { if (it !== parsed.bromStage) add(it) }
        }.distinctBy { "${it.loadAddress}:${it.payload.size}:${it.sigLen}" }

        onEvent(FlashLogEvent.Phase(4, 7, "Mengirim Download Agent…"))
        onEvent(
            FlashLogEvent.Info(
                "[4/7] Mengirim DA stage ke BROM — ${stageAttempts.size} percobaan…"
            )
        )
        var daLoaded = false
        var lastDaError: Exception? = null
        for ((idx, stage) in stageAttempts.withIndex()) {
            onEvent(
                FlashLogEvent.Info(
                    "  → percobaan ${idx + 1}/${stageAttempts.size}: " +
                        "${stage.payload.size} B @ 0x%08X sigLen=%d"
                            .format(stage.loadAddress, stage.sigLen)
                )
            )
            onEvent(
                FlashLogEvent.Phase(
                    4, 7,
                    "Mengirim DA @ 0x%08X (${idx + 1}/${stageAttempts.size})…"
                        .format(stage.loadAddress)
                )
            )
            try {
                brom.sendAndJumpToDa(
                    daPayload = stage.payload,
                    loadAddress = stage.loadAddress,
                    sigLen = stage.sigLen,
                ) { done, total ->
                    onEvent(FlashLogEvent.Progress(done, total))
                }
                onEvent(
                    FlashLogEvent.Success(
                        "DA terkirim dan JUMP_DA OK @ 0x%08X".format(stage.loadAddress)
                    )
                )
                daLoaded = true
                break
            } catch (e: Exception) {
                lastDaError = e
                onEvent(
                    FlashLogEvent.Warning(
                        "  stage @ 0x%08X gagal: ${e.message}".format(stage.loadAddress)
                    )
                )
                if (idx < stageAttempts.lastIndex) {
                    try {
                        onEvent(FlashLogEvent.Info("  re-sync BROM sebelum stage berikutnya…"))
                        brom.sync(maxAttempts = 60)
                        onEvent(FlashLogEvent.Success("  re-sync OK"))
                    } catch (syncEx: Exception) {
                        onEvent(
                            FlashLogEvent.Warning(
                                "  re-sync gagal (${syncEx.message}). " +
                                    "Colok ulang OTG (tahan Volume) lalu Hubungkan lagi."
                            )
                        )
                        break
                    }
                }
            }
        }
        if (!daLoaded) {
            onEvent(
                FlashLogEvent.Error(
                    "Semua stage DA gagal. Terakhir: ${lastDaError?.message}",
                    lastDaError
                )
            )
            throw lastDaError
                ?: java.io.IOException("Gagal mengirim DA pada semua stage/load address")
        }

        onEvent(FlashLogEvent.Phase(5, 7, "Memulai sesi DA…"))
        onEvent(FlashLogEvent.Info("[5/7] Menunggu CMD:START dari DA (paket 0xFEEEEEEF)…"))
        val da = DaSession(
            t,
            onRawExchange = ::logRaw,
            onProgress = { done, total -> onEvent(FlashLogEvent.Progress(done, total)) },
        )
        try {
            da.start()
            onEvent(FlashLogEvent.Success("CMD:START diterima DA"))
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Error("CMD:START gagal: ${e.message}", e))
            onEvent(FlashLogEvent.Warning("Sesi XML DA gagal: ${e.message}"))
            throw e
        }

        onEvent(FlashLogEvent.Phase(6, 7, "SET-HOST-INFO…"))
        onEvent(FlashLogEvent.Info("[6/7] Mengirim info host (CMD:SET-HOST-INFO)..."))
        try {
            da.setHostInfo()
            onEvent(FlashLogEvent.Success("CMD:SET-HOST-INFO OK"))
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Warning("CMD:SET-HOST-INFO gagal (mungkin opsional): ${e.message}"))
        }

        // DA stage-2 (DRAM agent) — required for partition / storage commands.
        val da2 = parsed.stages
            .filter { it.loadAddress != parsed.bromStage.loadAddress }
            .maxByOrNull { it.loadAddress }
        if (da2 != null) {
            onEvent(FlashLogEvent.Phase(7, 7, "BOOT-TO DA2…"))
            val stripped = if (da2.sigLen > 0 && da2.payload.size > da2.sigLen) {
                da2.payload.copyOf(da2.payload.size - da2.sigLen)
            } else null
            // mtkclient strips DA2 signature before BOOT-TO when not hash-patching.
            // Full+sig causes "DA Hash mismatch" then no CMD:START.
            val attempts = buildList {
                if (stripped != null) {
                    add("tanpa sig (${stripped.size} B)" to stripped)
                }
                add("penuh+sig (${da2.payload.size} B)" to da2.payload)
            }
            var da2Ok = false
            var lastErr: Exception? = null
            for ((label, payload) in attempts) {
                onEvent(
                    FlashLogEvent.Info(
                        "[7/7] BOOT-TO DA2 @ 0x%08X — coba $label…"
                            .format(da2.loadAddress)
                    )
                )
                try {
                    da.bootDa2(da2.loadAddress, payload)
                    onEvent(
                        FlashLogEvent.Success(
                            "DA2 BOOT-TO OK @ 0x%08X ($label)".format(da2.loadAddress)
                        )
                    )
                    da2Ok = true
                    break
                } catch (e: Exception) {
                    lastErr = e
                    onEvent(FlashLogEvent.Warning("BOOT-TO DA2 ($label) gagal: ${e.message}"))
                }
            }
            if (!da2Ok) {
                onEvent(FlashLogEvent.Error("BOOT-TO DA2 gagal semua percobaan: ${lastErr?.message}", lastErr))
                onEvent(
                    FlashLogEvent.Warning(
                        "Tanpa DA2, baca/tulis partisi tidak bisa. Cabut OTG → tahan Volume → colok ulang → Hubungkan."
                    )
                )
                // Jangan tandai sesi aktif — hindari baca partisi pada koneksi mati
                throw lastErr ?: IOException("BOOT-TO DA2 gagal")
            }
        } else {
            onEvent(FlashLogEvent.Warning("Tidak ada stage-2 di DA — lewati BOOT-TO"))
        }

        daSession = da
        onEvent(FlashLogEvent.Phase(7, 7, "DA aktif — siap"))
        onEvent(FlashLogEvent.Success("=== KONEKSI DA AKTIF — siap operasi partisi ==="))
        onEvent(FlashLogEvent.Success("Sesi DA aktif. Baca partisi atau buka PGPT Manager."))
    }

    /**
     * Reads hardware info from the DA — RAM size, storage, product id, and
     * (per PROTOCOL_NOTES.md) where EFUSE sla_en/daa_en indicators tend to
     * surface in the DA's own logging. Useful as a standalone diagnostic step
     * even before doing any partition operations.
     */
    fun getHwInfo(): Map<String, String> {
        val da = daSession ?: error("Belum terhubung ke DA")
        onEvent(FlashLogEvent.Info("Membaca HW info + SLA (Penumbra GET-HW-INFO / GET-SYS-PROPERTY)..."))
        return try {
            val info = linkedMapOf<String, String>()
            if (FlashSessionHolder.lastChipSummary.isNotEmpty()) {
                info["Chip (BROM)"] = FlashSessionHolder.lastChipSummary
            }
            info.putAll(da.getHwInfo())
            onEvent(FlashLogEvent.Success("HW info: ${info.filterKeys { it != "raw" }}"))
            info
        } catch (e: Exception) {
            // Still return chip if available
            if (FlashSessionHolder.lastChipSummary.isNotEmpty()) {
                val fallback = linkedMapOf(
                    "Chip (BROM)" to FlashSessionHolder.lastChipSummary,
                    "error" to (e.message ?: "gagal"),
                )
                onEvent(FlashLogEvent.Info("HW info parsial (GET-HW-INFO mungkin unsupported)"))
                return fallback
            }
            onEvent(FlashLogEvent.Error("Gagal membaca HW info: ${e.message}", e))
            throw e
        }
    }

    fun readPartitionTable(): List<PartitionEntry> {
        val da = daSession ?: error("Belum terhubung ke DA")
        onEvent(FlashLogEvent.Info("Membaca tabel partisi (PGPT / Penumbra)..."))
        return try {
            val entries = da.readPartitionTable()
            onEvent(FlashLogEvent.Success("Tabel partisi: ${entries.size} entri"))
            entries.forEachIndexed { i, e ->
                onEvent(FlashLogEvent.Info("  [$i] ${e.name}  start=0x${(e.startSector * 512).toString(16)}  size=${e.sizeBytes} B"))
            }
            FlashSessionHolder.lastPartitionTable = entries
            entries
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Error("Gagal membaca tabel partisi: ${e.message}", e))
            throw e
        }
    }

    fun readPartition(entry: PartitionEntry, output: OutputStream) {
        val da = daSession ?: error("Belum terhubung ke DA")
        onEvent(FlashLogEvent.Info("Membaca partisi '${entry.name}' (${entry.sizeMb} MB / ${entry.sizeBytes} bytes)..."))
        try {
            da.readPartition(entry, output)
            onEvent(FlashLogEvent.Success("Selesai membaca '${entry.name}' (${entry.sizeBytes} bytes)"))
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Error("Gagal membaca partisi '${entry.name}': ${e.message}", e))
            throw e
        }
    }

    /**
     * Writes [input] to the given partition. [sizeBytes] should be the actual
     * image file size; DaSession itself refuses sizes larger than the
     * partition's declared capacity, but callers should still confirm this
     * with the user before calling (see PartitionSafety).
     */
    fun writePartition(entry: PartitionEntry, input: InputStream, sizeBytes: Long) {
        val da = daSession ?: error("Belum terhubung ke DA")
        val policy = FlashSessionHolder.writePolicy
        when (val decision = FlashGuards.evaluateWrite(entry, sizeBytes, policy)) {
            is FlashGuards.Decision.Block -> {
                onEvent(FlashLogEvent.Error("GUARD blokir write '${entry.name}': ${decision.reason}"))
                throw FlashGuardException(decision.reason)
            }
            is FlashGuards.Decision.Allow -> {
                decision.notes.forEach { onEvent(FlashLogEvent.Warning(it)) }
            }
        }
        ensureAutoBackup(entry, policy)
        onEvent(FlashLogEvent.Info(
            "Menulis '${entry.name}' ($sizeBytes B / max ${entry.sizeBytes} B) [guard OK]..."
        ))
        try {
            da.writePartition(entry, input, sizeBytes)
            onEvent(FlashLogEvent.Success(
                "Selesai menulis '${entry.name}' ($sizeBytes B) — DA status OK"
            ))
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Error(
                "Gagal menulis '${entry.name}': ${e.message}. " +
                    "Partisi ini mungkin rusak sebagian — jangan power-cycle berulang; " +
                    "flash ulang image yang benar ke '${entry.name}'.",
                e,
            ))
            throw e
        }
    }

    /**
     * For HIGH (and CRITICAL if allowed): dump partition to backupDir before
     * first write in this session.
     */
    private fun ensureAutoBackup(entry: PartitionEntry, policy: FlashGuards.Policy) {
        if (!FlashGuards.needsAutoBackup(entry.name, policy)) return
        if (FlashSessionHolder.wasBackedUp(entry.name)) {
            onEvent(FlashLogEvent.Info("Auto-backup '${entry.name}': sudah ada di sesi ini — lewati"))
            return
        }
        val dir = FlashSessionHolder.backupDir
            ?: throw FlashGuardException(
                "Auto-backup wajib untuk '${entry.name}' tapi folder backup belum diset. " +
                    "Buka PGPT Manager sekali agar folder dibuat."
            )
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "${entry.name}.prewrite.bak")
        onEvent(FlashLogEvent.Info("Auto-backup '${entry.name}' → ${out.absolutePath} ..."))
        try {
            FileOutputStream(out).use { fos ->
                daSession!!.readPartition(entry, fos)
            }
            FlashSessionHolder.markBackedUp(entry.name)
            onEvent(FlashLogEvent.Success("Auto-backup OK: ${out.name} (${out.length()} B)"))
        } catch (e: Exception) {
            onEvent(FlashLogEvent.Error("Auto-backup gagal untuk '${entry.name}': ${e.message}", e))
            throw FlashGuardException(
                "Write '${entry.name}' dibatalkan — auto-backup gagal: ${e.message}"
            )
        }
    }

    /**
     * Reads the device's current partition table and returns the entry whose
     * name matches [partitionName], or null if not present. Convenience for
     * the PGPT manager UI, which lets the user pick a partition by name
     * rather than holding a full-table reference across screens.
     */
    fun findPartitionByName(table: List<PartitionEntry>, partitionName: String): PartitionEntry? =
        table.firstOrNull { it.name.equals(partitionName, ignoreCase = true) }

    /**
     * Flashes multiple partitions in sequence — the "full flash firmware"
     * flow. [items] is typically built from a parsed scatter file matched
     * against files the user picked (one per partition with a file_name).
     *
     * STOPS on the first failure rather than continuing to the next partition
     * — a failed write mid-firmware-flash is not something to paper over by
     * moving on, since partitions can have cross-dependencies (e.g. a boot
     * image built for one vbmeta won't necessarily boot with another).
     *
     * This method does NOT filter out sensitive/critical partitions itself —
     * that decision belongs to the UI layer (see PartitionSafety), which
     * should build [items] only from partitions the user explicitly
     * confirmed, especially anything PartitionSafety.riskLevel() flags.
     */
    @Throws(Exception::class)
    fun flashAll(items: List<FlashJobItem>) {
        val da = daSession ?: error("Belum terhubung ke DA")
        val policy = FlashSessionHolder.writePolicy

        // Pre-flight: block entire job if any partition fails guards (no partial start)
        for (item in items) {
            when (val d = FlashGuards.evaluateWrite(item.entry, item.fileSizeBytes, policy)) {
                is FlashGuards.Decision.Block -> {
                    onEvent(FlashLogEvent.Error("GUARD pre-flight: ${d.reason}"))
                    throw FlashGuardException("Full flash dibatalkan sebelum mulai: ${d.reason}")
                }
                is FlashGuards.Decision.Allow -> { }
            }
        }

        // Penumbra-style: large partitions last
        val ordered = items.sortedWith(
            compareBy<FlashJobItem> { flashOrderRank(it.entry.name) }
                .thenBy { it.fileSizeBytes }
        )
        if (ordered.map { it.entry.name } != items.map { it.entry.name }) {
            onEvent(
                FlashLogEvent.Info(
                    "Urutan flash: partisi besar (super/userdata) di akhir"
                )
            )
        }
        onEvent(FlashLogEvent.Info("Memulai full flash (Penumbra WRITE-PARTITION): ${ordered.size} partisi, super/userdata di akhir"))

        ordered.forEachIndexed { index, item ->
            onEvent(FlashLogEvent.PartitionStep(index + 1, items.size, item.entry.name))
            onEvent(
                FlashLogEvent.Info(
                    "[${index + 1}/${items.size}] Menulis '${item.entry.name}' " +
                        "(file ${"%.1f".format(item.fileSizeBytes / 1024.0 / 1024.0)} MB" +
                        ", partisi ${"%.1f".format(item.entry.sizeBytes / 1024.0 / 1024.0)} MB" +
                        (item.entry.region?.let { ", $it" } ?: "") +
                        ")..."
                )
            )
            try {
                item.resolveInput().use { raw ->
                    // Peek sparse magic; expand if needed so DA receives raw length.
                    val head = ByteArray(4)
                    val n = raw.read(head)
                    val push = java.io.SequenceInputStream(
                        java.io.ByteArrayInputStream(if (n > 0) head.copyOf(n) else ByteArray(0)),
                        raw,
                    )
                    if (n >= 4 && SparseImageInputStream.looksSparse(head)) {
                        onEvent(FlashLogEvent.Info("  '${item.entry.name}': sparse image — expand dulu"))
                        val sparse = SparseImageInputStream(push)
                        val expanded = sparse.rawSize
                        if (expanded <= 0L) {
                            throw java.io.IOException("Sparse header invalid untuk '${item.entry.name}'")
                        }
                        if (expanded > item.entry.sizeBytes) {
                            throw java.io.IOException(
                                "Sparse expanded $expanded > partisi ${item.entry.sizeBytes} (${item.entry.name})"
                            )
                        }
                        writePartition(item.entry, sparse, expanded)
                    } else {
                        val size = item.fileSizeBytes
                        writePartition(item.entry, push, size)
                    }
                }
                onEvent(FlashLogEvent.Success("  OK '${item.entry.name}'"))
            } catch (e: Exception) {
                onEvent(FlashLogEvent.Error("Full flash berhenti di partisi '${item.entry.name}': ${e.message}", e))
                throw FlashAbortedException(
                    "Full flash dihentikan pada partisi '${item.entry.name}' (${index + 1}/${items.size}): ${e.message}"
                )
            }
        }

        onEvent(FlashLogEvent.Success("Full flash selesai: ${items.size}/${items.size} partisi"))
    }

    /**
     * Lower rank = earlier in flashAll. super/userdata last (Penumbra practice).
     */
    private fun flashOrderRank(name: String): Int {
        val n = name.lowercase()
        return when {
            n == "preloader" || n == "preloader_backup" -> 0
            n.startsWith("lk") || n == "logo" || n.startsWith("logo_") -> 1
            n.startsWith("boot") || n.startsWith("vendor_boot") || n.startsWith("dtbo") ||
                n.startsWith("tee") || n.startsWith("gz") -> 2
            n.startsWith("vbmeta") || n.startsWith("tkv") -> 3
            n.startsWith("md1") || n.startsWith("spm") || n.startsWith("scp") ||
                n.startsWith("sspm") || n.startsWith("mcupm") || n.startsWith("dpm") ||
                n.startsWith("pi_img") -> 4
            n == "super" -> 90
            n == "userdata" || n == "user_data" -> 95
            n == "tranfs" || n.contains("cache") -> 80
            else -> 50
        }
    }


    override fun close() {
        try {
            daSession?.end()
        } catch (_: Exception) {
            // best-effort — device may already be unresponsive, don't mask the original error
        }
        transport?.close()
        transport = null
        daSession = null
    }
}
