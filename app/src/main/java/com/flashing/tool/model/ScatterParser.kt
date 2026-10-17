package com.flashing.tool.model

import java.io.InputStream

/**
 * Parser for MediaTek scatter files (.txt YAML-like and .xml).
 *
 * Real firmware packages (e.g. MT6789 / itel RS4) often ship **two** layouts in
 * one file — EMMC and UFS — with the same partition names twice. Flashing both
 * would write each image twice. We keep one storage type:
 *   - [preferredStorage] = "UFS" | "EMMC" | "AUTO" (default AUTO prefers UFS
 *     when the scatter has any downloadable UFS partitions, else EMMC).
 *
 * Only entries with is_download=true and a concrete file_name (not NONE) are
 * returned for full-flash matching.
 */
object ScatterParser {

    enum class StoragePref { AUTO, EMMC, UFS }

    data class ParseResult(
        val platform: String?,
        val project: String?,
        val storageUsed: String,
        val entries: List<PartitionEntry>,
        val skippedDuplicateStorage: Int,
    )

    fun parse(
        input: InputStream,
        preferredStorage: StoragePref = StoragePref.AUTO,
    ): List<PartitionEntry> = parseDetailed(input, preferredStorage).entries

    fun parseDetailed(
        input: InputStream,
        preferredStorage: StoragePref = StoragePref.AUTO,
    ): ParseResult {
        val text = input.bufferedReader(Charsets.UTF_8).readText().trimStart()
        return if (text.startsWith("<?xml", ignoreCase = true) || text.startsWith("<root")) {
            parseXml(text, preferredStorage)
        } else {
            parseYamlLike(text, preferredStorage)
        }
    }

    // ---- YAML-like .txt ----

    private data class RawPart(
        val name: String,
        val file: String?,
        val start: Long,
        val size: Long,
        val isDownload: Boolean,
        val operationType: String?,
        val isReserved: Boolean,
        val region: String?,
        val storage: String?,
    )

    private fun parseYamlLike(text: String, preferred: StoragePref): ParseResult {
        var platform: String? = null
        var project: String? = null
        val raw = mutableListOf<RawPart>()

        var name: String? = null
        var file: String? = null
        var start: Long? = null
        var size: Long? = null
        var isDownload = true
        var op: String? = null
        var reserved = false
        var region: String? = null
        var storage: String? = null

        fun flush() {
            val n = name
            if (n != null && size != null) {
                raw += RawPart(
                    name = n,
                    file = file,
                    start = start ?: 0L,
                    size = size!!,
                    isDownload = isDownload,
                    operationType = op,
                    isReserved = reserved,
                    region = region,
                    storage = storage,
                )
            }
            name = null; file = null; start = null; size = null
            isDownload = true; op = null; reserved = false
            // keep region/storage context until next storage_type header changes them
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("- partition_index:")) {
                flush()
                continue
            }
            // storage_type: EMMC / UFS section header
            if (line.startsWith("- storage_type:") || line.startsWith("storage_type:")) {
                flush()
                val st = line.substringAfter(':').trim()
                storage = when {
                    st.contains("UFS", true) -> "HW_STORAGE_UFS"
                    st.contains("EMMC", true) -> "HW_STORAGE_EMMC"
                    else -> storage
                }
                continue
            }
            val (key, value) = splitKeyValue(line) ?: continue
            when (key) {
                "platform" -> platform = value
                "project" -> project = value
                "partition_name" -> name = value
                "file_name" -> file = value.ifBlank { null }
                    .takeUnless { it.equals("NONE", ignoreCase = true) }
                "is_download" -> isDownload = value.equals("true", ignoreCase = true)
                "linear_start_addr", "physical_start_addr" ->
                    start = parseHexOrDec(value) ?: start
                "partition_size" -> size = parseHexOrDec(value)
                "operation_type" -> op = value
                "is_reserved" -> reserved = value.equals("true", ignoreCase = true)
                "region" -> region = value
                "storage" -> storage = value
            }
        }
        flush()

        return selectStorage(raw, preferred, platform, project)
    }

    // ---- XML scatter ----

    private fun parseXml(text: String, preferred: StoragePref): ParseResult {
        var platform: String? = null
        var project: String? = null
        val raw = mutableListOf<RawPart>()

        // crude tag extractors
        fun tag(body: String, name: String): String? {
            val open = "<$name>"
            val close = "</$name>"
            val s = body.indexOf(open)
            if (s < 0) return null
            val e = body.indexOf(close, s + open.length)
            if (e < 0) return null
            return body.substring(s + open.length, e).trim()
        }

        val plat = Regex("""<platform>([^<]+)</platform>""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        val proj = Regex("""<project>([^<]+)</project>""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        platform = plat
        project = proj

        // Each <partition_index>...</partition_index>
        val partRe = Regex(
            """<partition_index[^>]*>(.*?)</partition_index>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        // storage_type wrappers give context
        val storageBlocks = Regex(
            """<storage_type\s+name="([^"]+)"[^>]*>(.*?)</storage_type>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(text).toList()

        fun ingest(block: String, defaultStorage: String?) {
            for (m in partRe.findAll(block)) {
                val b = m.groupValues[1]
                val n = tag(b, "partition_name") ?: continue
                val fRaw = tag(b, "file_name")
                val f = fRaw?.takeUnless { it.equals("NONE", true) || it.isBlank() }
                val start = parseHexOrDec(tag(b, "linear_start_addr") ?: tag(b, "physical_start_addr") ?: "0") ?: 0L
                val size = parseHexOrDec(tag(b, "partition_size") ?: "0") ?: 0L
                val isDl = (tag(b, "is_download") ?: "true").equals("true", true)
                val op = tag(b, "operation_type")
                val reserved = (tag(b, "is_reserved") ?: "false").equals("true", true)
                val region = tag(b, "region")
                val storage = tag(b, "storage") ?: defaultStorage
                raw += RawPart(n, f, start, size, isDl, op, reserved, region, storage)
            }
        }

        if (storageBlocks.isNotEmpty()) {
            for (m in storageBlocks) {
                val stName = m.groupValues[1]
                val defaultSt = when {
                    stName.contains("UFS", true) -> "HW_STORAGE_UFS"
                    stName.contains("EMMC", true) -> "HW_STORAGE_EMMC"
                    else -> null
                }
                ingest(m.groupValues[2], defaultSt)
            }
        } else {
            ingest(text, null)
        }

        return selectStorage(raw, preferred, platform, project)
    }

    private fun selectStorage(
        raw: List<RawPart>,
        preferred: StoragePref,
        platform: String?,
        project: String?,
    ): ParseResult {
        val emmc = raw.filter { it.storage?.contains("EMMC", true) == true }
        val ufs = raw.filter { it.storage?.contains("UFS", true) == true }
        val chosen: List<RawPart>
        val label: String
        when (preferred) {
            StoragePref.EMMC -> {
                chosen = emmc.ifEmpty { raw }
                label = "EMMC"
            }
            StoragePref.UFS -> {
                chosen = ufs.ifEmpty { raw }
                label = "UFS"
            }
            StoragePref.AUTO -> {
                // Prefer UFS when it has downloadable partitions; many MT6789
                // phones (itel RS4) use UFS even though scatter lists both.
                val ufsDl = ufs.any { it.isDownload && it.file != null }
                val emmcDl = emmc.any { it.isDownload && it.file != null }
                when {
                    ufsDl -> { chosen = ufs; label = "UFS" }
                    emmcDl -> { chosen = emmc; label = "EMMC" }
                    else -> { chosen = raw; label = "ALL" }
                }
            }
        }

        val flashable = chosen.filter { it.isDownload && !it.isReserved && it.file != null && it.size > 0 }
            // de-dupe by name (keep first)
            .fold(mutableListOf<RawPart>()) { acc, p ->
                if (acc.none { it.name.equals(p.name, true) }) acc += p
                acc
            }

        val entries = flashable.map {
            PartitionEntry(
                name = it.name,
                startSector = it.start / 512L,
                sizeBytes = it.size,
                imagePath = it.file,
                operationType = it.operationType,
                isReserved = it.isReserved,
                region = it.region,
                storage = it.storage,
            )
        }

        val skipped = (emmc.size + ufs.size - chosen.size).coerceAtLeast(0)
        return ParseResult(platform, project, label, entries, skipped)
    }

    private fun splitKeyValue(line: String): Pair<String, String>? {
        val idx = line.indexOf(':')
        if (idx <= 0) return null
        val key = line.substring(0, idx).trim()
        // skip YAML list markers already handled
        if (key.startsWith("#") || key.startsWith("- general") || key.startsWith("- storage")) return null
        val value = line.substring(idx + 1).trim()
        return key to value
    }

    private fun parseHexOrDec(value: String): Long? {
        val v = value.trim()
        return try {
            if (v.startsWith("0x", ignoreCase = true)) v.substring(2).toLong(16) else v.toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }
}
