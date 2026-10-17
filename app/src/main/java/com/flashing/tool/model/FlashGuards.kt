package com.flashing.tool.model

/**
 * Central write/flash safety policy for BROM (PGPT/scatter) and guidance for
 * Fastboot. Fail-closed: blocked writes throw; never "best effort" past CRITICAL.
 */
object FlashGuards {

    data class Policy(
        /** Allow seccfg/otp/efuse/… — default false. */
        val allowCritical: Boolean = false,
        /** Skip automatic pre-write backup for HIGH partitions. */
        val skipAutoBackup: Boolean = false,
        /** Max image size as fraction of partition (1.0 = exact max). */
        val maxFillRatio: Double = 1.0,
    )

    sealed class Decision {
        data class Allow(val notes: List<String> = emptyList()) : Decision()
        data class Block(val reason: String) : Decision()
    }

    /**
     * Evaluate whether [sizeBytes] may be written to [entry].
     * Does not perform I/O — only policy.
     */
    fun evaluateWrite(
        partitionName: String,
        partitionSizeBytes: Long,
        imageSizeBytes: Long,
        policy: Policy = Policy(),
    ): Decision {
        val name = partitionName.trim()
        if (name.isEmpty()) {
            return Decision.Block("Nama partisi kosong")
        }
        if (imageSizeBytes <= 0L) {
            return Decision.Block("Ukuran image 0 — write dibatalkan ($name)")
        }
        if (partitionSizeBytes <= 0L) {
            return Decision.Block("Ukuran partisi tidak valid untuk '$name'")
        }
        val maxAllowed = (partitionSizeBytes * policy.maxFillRatio).toLong()
        if (imageSizeBytes > maxAllowed) {
            return Decision.Block(
                "Image ($imageSizeBytes B) lebih besar dari partisi '$name' " +
                    "($partitionSizeBytes B) — overflow ditolak"
            )
        }

        val risk = PartitionSafety.riskLevel(name)
        if (risk == RiskLevel.CRITICAL && !policy.allowCritical) {
            return Decision.Block(
                "Partisi CRITICAL '$name' diblok. " +
                    "Salah tulis bisa permanent (fuse/secure boot). " +
                    "Aktifkan 'Izinkan partisi kritis' hanya jika benar-benar yakin."
            )
        }

        val notes = mutableListOf<String>()
        PartitionSafety.warningFor(name)?.let { notes += it }
        if (risk == RiskLevel.HIGH && !policy.skipAutoBackup) {
            notes += "Auto-backup wajib sebelum write HIGH ('$name')"
        }
        if (risk == RiskLevel.CRITICAL && policy.allowCritical) {
            notes += "CRITICAL override aktif untuk '$name'"
        }
        // Large transfer warning
        if (imageSizeBytes >= 512L * 1024 * 1024) {
            notes += "Transfer besar (≥512MB) — jangan cabut OTG/USB"
        }
        return Decision.Allow(notes)
    }

    fun evaluateWrite(
        entry: PartitionEntry,
        imageSizeBytes: Long,
        policy: Policy = Policy(),
    ): Decision = evaluateWrite(entry.name, entry.sizeBytes, imageSizeBytes, policy)

    /** Fastboot partition name (may be boot / boot_a / system_a). */
    fun evaluateFastbootPartition(
        partition: String,
        imageSizeBytes: Long,
        policy: Policy = Policy(),
    ): Decision {
        // Unknown partition size in fastboot until getvar — only risk + non-zero size
        if (imageSizeBytes <= 0L) {
            return Decision.Block("Ukuran image 0 untuk '$partition'")
        }
        val risk = PartitionSafety.riskLevel(partition)
        if (risk == RiskLevel.CRITICAL && !policy.allowCritical) {
            return Decision.Block(
                "Fastboot: partisi CRITICAL '$partition' diblok tanpa allowCritical"
            )
        }
        val notes = mutableListOf<String>()
        PartitionSafety.warningFor(partition)?.let { notes += it }
        return Decision.Allow(notes)
    }

    fun needsAutoBackup(partitionName: String, policy: Policy = Policy()): Boolean {
        if (policy.skipAutoBackup) return false
        val r = PartitionSafety.riskLevel(partitionName)
        return r == RiskLevel.HIGH || r == RiskLevel.CRITICAL
    }
}

/** Thrown when a write is refused by FlashGuards (not a transport error). */
class FlashGuardException(message: String) : Exception(message)
