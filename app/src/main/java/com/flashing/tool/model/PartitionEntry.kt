package com.flashing.tool.model

/**
 * One entry from the device's partition table (PGPT) or from a parsed Scatter file.
 */
data class PartitionEntry(
    val name: String,
    val startSector: Long,
    val sizeBytes: Long,
    /** Scatter: declared image file name (e.g. boot.img). Null for device-read entries. */
    val imagePath: String? = null,
    /** Scatter: operation_type (BOOTLOADERS, UPDATE, INVISIBLE, …). */
    val operationType: String? = null,
    val isReserved: Boolean = false,
    /** Scatter: EMMC_USER / EMMC_BOOT1 / UFS_LU2 / … */
    val region: String? = null,
    /** Scatter: HW_STORAGE_EMMC / HW_STORAGE_UFS */
    val storage: String? = null,
) {
    val sizeMb: Double get() = sizeBytes / (1024.0 * 1024.0)
}
