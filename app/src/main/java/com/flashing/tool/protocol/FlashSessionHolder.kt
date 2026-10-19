package com.flashing.tool.protocol

import com.flashing.tool.model.FlashGuards
import com.flashing.tool.model.PartitionEntry
import java.io.File

/**
 * Process-wide holder for live FlashController + safety session state.
 */
object FlashSessionHolder {
    var controller: FlashController? = null
    var lastPartitionTable: List<PartitionEntry> = emptyList()

    /** Chip identity from BROM (survives for HW dialog even if GET-HW-INFO unsupported). */
    var lastChipSummary: String = ""
    var lastSlaEnabled: Boolean? = null

    /** Host folder for auto-backup before HIGH writes. */
    var backupDir: File? = null

    /** Partitions already backed up this DA session (name lowercase). */
    val backedUpThisSession: MutableSet<String> = mutableSetOf()

    /** Global write policy for this session (UI can toggle allowCritical). */
    var writePolicy: FlashGuards.Policy = FlashGuards.Policy()

    fun clear() {
        controller?.close()
        controller = null
        lastPartitionTable = emptyList()
        lastChipSummary = ""
        lastSlaEnabled = null
        backedUpThisSession.clear()
        writePolicy = FlashGuards.Policy()
        // keep backupDir path
    }

    fun markBackedUp(name: String) {
        backedUpThisSession.add(name.trim().lowercase())
    }

    fun wasBackedUp(name: String): Boolean =
        backedUpThisSession.contains(name.trim().lowercase())
}
