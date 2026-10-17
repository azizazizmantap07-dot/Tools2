package com.flashing.tool.model

/**
 * Risk classification for partition write operations.
 * Used by UI warnings and FlashGuards hard blocks.
 */
object PartitionSafety {

    /**
     * CRITICAL: permanent / fuse / boot-ROM security. Hard-blocked unless
     * policy.allowCritical = true.
     */
    private val CRITICAL_NAMES = setOf(
        "seccfg", "otp", "efuse", "sec1",
        "preloader", "preloader_backup",
        "pgpt", "sgpt",
    )

    /** HIGH: identity / modem — auto-backup required before write. */
    private val HIGH_RISK_NAMES = setOf(
        "nvram", "nvdata", "nvcfg", "proinfo", "protect1", "protect2",
        "md1img", "md1img_a", "md1img_b",
        "tkv", "tkv_a", "tkv_b",
        "persist",
    )

    /** MEDIUM: policy / FRP / tables related. */
    private val MEDIUM_RISK_NAMES = setOf(
        "frp", "dpm", "dpm_a", "dpm_b",
        "flashinfo", "misc", "expdb", "para", "boot_para",
    )

    private fun normalize(name: String): String = name.trim().lowercase()

    fun riskLevel(partitionName: String): RiskLevel {
        val n = normalize(partitionName)
        return when {
            CRITICAL_NAMES.any { n == it || n.startsWith("${it}_") } -> RiskLevel.CRITICAL
            HIGH_RISK_NAMES.any { n == it || n.startsWith("${it}_") } -> RiskLevel.HIGH
            MEDIUM_RISK_NAMES.any { n == it || n.startsWith("${it}_") } -> RiskLevel.MEDIUM
            else -> RiskLevel.NORMAL
        }
    }

    fun isSensitive(partitionName: String): Boolean = riskLevel(partitionName) != RiskLevel.NORMAL

    fun isCritical(partitionName: String): Boolean = riskLevel(partitionName) == RiskLevel.CRITICAL

    fun warningFor(partitionName: String): String? {
        return when (riskLevel(partitionName)) {
            RiskLevel.CRITICAL -> "⚠️⚠️ '$partitionName': CRITICAL (fuse/preloader/tabel). " +
                "Salah tulis bisa PERMANEN. Diblok kecuali override aktif."
            RiskLevel.HIGH -> "⚠️ '$partitionName': identitas/modem. Auto-backup wajib sebelum write."
            RiskLevel.MEDIUM -> "'$partitionName': FRP/policy — pastikan image benar."
            RiskLevel.NORMAL -> null
        }
    }
}

enum class RiskLevel { NORMAL, MEDIUM, HIGH, CRITICAL }

operator fun RiskLevel.compareTo(other: RiskLevel): Int = this.ordinal.compareTo(other.ordinal)
