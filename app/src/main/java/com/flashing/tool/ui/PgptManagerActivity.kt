package com.flashing.tool.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.flashing.tool.R
import com.flashing.tool.model.PartitionEntry
import com.flashing.tool.model.PartitionSafety
import com.flashing.tool.model.FlashGuards
import com.flashing.tool.model.FlashGuardException
import com.flashing.tool.model.RiskLevel
import com.flashing.tool.model.ScatterParser
import com.flashing.tool.protocol.FlashJobItem
import com.flashing.tool.protocol.FlashSessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PGPT Manager — three modes (no dual-checkbox confusion):
 *  - BACKUP: checklist → save selected partitions to host phone storage
 *  - WRITE: checklist + per-row "+" to pick image → flash selected
 *  - SCATTER: load scatter + FW folder → checklist → flash selected
 */
class PgptManagerActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvBackupPath: TextView
    private lateinit var lvPartitions: ListView
    private lateinit var btnRefreshTable: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnUnselectAll: Button
    private lateinit var btnRunAction: Button
    private lateinit var btnModeBackup: Button
    private lateinit var btnModeWrite: Button
    private lateinit var btnModeScatter: Button
    private lateinit var btnFullFlash: Button
    private lateinit var rowDeviceActions: View
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressLabel: TextView
    private lateinit var etSearch: EditText
    private var searchQuery: String = ""

    private var mode: PgptUiMode = PgptUiMode.BACKUP
    private val deviceRows = mutableListOf<DevicePartRow>()
    private var deviceAdapter: DevicePartitionAdapter? = null

    private var scatterRows: MutableList<ScatterRow> = mutableListOf()
    private var scatterAdapter: ScatterListAdapter? = null
    private var pendingScatterEntries: List<PartitionEntry> = emptyList()
    private var pendingPickPosition: Int = -1

    private val scope = CoroutineScope(Dispatchers.Main)

    private val pickWriteFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val pos = pendingPickPosition
        pendingPickPosition = -1
        if (uri == null || pos < 0 || pos >= deviceRows.size) return@registerForActivityResult
        val row = deviceRows[pos]
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "image.bin"
        val size = querySize(uri)
        row.imageUri = uri
        row.imageName = name
        row.imageSize = size
        row.selected = true
        deviceAdapter?.notifyDataSetChanged()
        updateStatusCounts()
    }

    private val pickScatterForFullFlash = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        loadScatterForFullFlash(uri)
    }

    private val pickFirmwareFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        treeUri ?: return@registerForActivityResult
        onFirmwareFolderPicked(treeUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pgpt_manager)

        tvStatus = findViewById(R.id.tvPgptStatus)
        tvBackupPath = findViewById(R.id.tvBackupPath)
        etSearch = findViewById(R.id.etSearchPartition)
        lvPartitions = findViewById(R.id.lvPgptPartitions)
        btnRefreshTable = findViewById(R.id.btnRefreshTable)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnUnselectAll = findViewById(R.id.btnUnselectAll)
        btnRunAction = findViewById(R.id.btnRunAction)
        btnModeBackup = findViewById(R.id.btnModeBackup)
        btnModeWrite = findViewById(R.id.btnModeWrite)
        btnModeScatter = findViewById(R.id.btnModeScatter)
        btnFullFlash = findViewById(R.id.btnFullFlash)
        rowDeviceActions = findViewById(R.id.rowDeviceActions)
        progressBar = findViewById(R.id.progressBar)
        tvProgressLabel = findViewById(R.id.tvProgressLabel)

        if (FlashSessionHolder.controller == null) {
            tvStatus.text = "Tidak ada sesi aktif. Hubungkan perangkat di Flasher dulu."
            btnRefreshTable.isEnabled = false
            btnRunAction.isEnabled = false
            btnFullFlash.isEnabled = false
            return
        }

        ensureBackupDir()
        FlashSessionHolder.backupDir = backupRoot()
        FlashSessionHolder.writePolicy = FlashGuards.Policy(allowCritical = false)
        tvBackupPath.text = "Folder backup: ${backupRoot().absolutePath}\n" +
            "Guard: CRITICAL diblok · HIGH auto-backup · stop on first fail"

        btnModeBackup.setOnClickListener { setMode(PgptUiMode.BACKUP) }
        btnModeWrite.setOnClickListener { setMode(PgptUiMode.WRITE) }
        btnModeWrite.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Override partisi CRITICAL?")
                .setMessage(
                    "Mengizinkan write ke preloader/seccfg/otp/efuse/pgpt. " +
                        "Salah tulis bisa PERMANEN. Hanya untuk recovery lanjutan."
                )
                .setNegativeButton("Tidak") { _, _ ->
                    FlashSessionHolder.writePolicy =
                        FlashSessionHolder.writePolicy.copy(allowCritical = false)
                    tvStatus.text = "Override CRITICAL: OFF"
                }
                .setPositiveButton("Izinkan (berbahaya)") { _, _ ->
                    FlashSessionHolder.writePolicy =
                        FlashSessionHolder.writePolicy.copy(allowCritical = true)
                    tvStatus.text = "Override CRITICAL: ON — hati-hati"
                }
                .show()
            true
        }
        btnModeScatter.setOnClickListener { setMode(PgptUiMode.SCATTER) }

        btnRefreshTable.setOnClickListener { refreshTable() }
        btnSelectAll.setOnClickListener {
            if (mode == PgptUiMode.SCATTER) scatterAdapter?.selectAllFound()
            else if (searchQuery.isNotEmpty()) deviceAdapter?.selectAllVisible()
            else deviceAdapter?.selectAll()
            updateStatusCounts()
        }
        btnUnselectAll.setOnClickListener {
            if (mode == PgptUiMode.SCATTER) scatterAdapter?.unselectAll()
            else if (searchQuery.isNotEmpty()) deviceAdapter?.unselectAllVisible()
            else deviceAdapter?.unselectAll()
            updateStatusCounts()
        }
        btnRunAction.setOnClickListener { onRunAction() }
        btnFullFlash.setOnClickListener { pickScatterForFullFlash.launch("*/*") }

        if (FlashSessionHolder.lastPartitionTable.isNotEmpty()) {
            bindDeviceRows(FlashSessionHolder.lastPartitionTable)
        } else {
            tvStatus.text = "Tekan Baca tabel untuk memuat partisi dari perangkat."
        }
        setMode(PgptUiMode.BACKUP)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applySearchFilter()
            }
        })
    }

    private fun applySearchFilter() {
        when (mode) {
            PgptUiMode.SCATTER -> {
                scatterAdapter?.setFilter(searchQuery)
                updateStatusCounts()
            }
            else -> {
                deviceAdapter?.setFilter(searchQuery)
                updateStatusCounts()
            }
        }
    }

    private fun setMode(m: PgptUiMode) {
        mode = m
        when (m) {
            PgptUiMode.BACKUP -> {
                rowDeviceActions.visibility = View.VISIBLE
                btnFullFlash.visibility = View.GONE
                tvBackupPath.visibility = View.VISIBLE
                btnRunAction.text = "Backup"
                bindDeviceAdapter()
                tvStatus.text = "Mode Backup — centang partisi, lalu Backup. Disimpan di folder app."
            }
            PgptUiMode.WRITE -> {
                rowDeviceActions.visibility = View.VISIBLE
                btnFullFlash.visibility = View.GONE
                tvBackupPath.visibility = View.GONE
                btnRunAction.text = "Flash"
                bindDeviceAdapter()
                tvStatus.text = "Mode Write — + pilih file, centang partisi, lalu Flash."
            }
            PgptUiMode.SCATTER -> {
                rowDeviceActions.visibility = View.VISIBLE
                btnFullFlash.visibility = View.VISIBLE
                tvBackupPath.visibility = View.GONE
                btnRunAction.text = "Flash Selected"
                if (scatterAdapter != null) {
                    lvPartitions.adapter = scatterAdapter
                } else {
                    lvPartitions.adapter = null
                    tvStatus.text = "Mode Scatter — Muat Scatter + folder FW."
                }
            }
        }
        updateStatusCounts()
    }

    private fun bindDeviceRows(entries: List<PartitionEntry>) {
        deviceRows.clear()
        entries.forEach { deviceRows += DevicePartRow(it) }
        bindDeviceAdapter()
    }

    private fun bindDeviceAdapter() {
        deviceAdapter = DevicePartitionAdapter(
            this,
            deviceRows,
            mode,
            onChanged = { updateStatusCounts() },
            onPickFile = { pos ->
                pendingPickPosition = pos
                pickWriteFile.launch("*/*")
            },
        )
        deviceAdapter?.setMode(mode)
        deviceAdapter?.setFilter(searchQuery)
        lvPartitions.adapter = deviceAdapter
    }

    private fun updateStatusCounts() {
        when (mode) {
            PgptUiMode.BACKUP -> {
                val n = deviceRows.count { it.selected }
                val vis = deviceAdapter?.visibleCount() ?: deviceRows.size
                val q = if (searchQuery.isNotEmpty()) " · filter $vis/${deviceRows.size}" else ""
                tvStatus.text = "Backup: ${deviceRows.size} partisi · dipilih $n$q"
            }
            PgptUiMode.WRITE -> {
                val n = deviceRows.count { it.selected }
                val withFile = deviceRows.count { it.selected && it.imageUri != null }
                val vis = deviceAdapter?.visibleCount() ?: deviceRows.size
                val q = if (searchQuery.isNotEmpty()) " · filter $vis/${deviceRows.size}" else ""
                tvStatus.text = "Write: dipilih $n · siap file $withFile$q"
            }
            PgptUiMode.SCATTER -> {
                val found = scatterRows.count { it.fileFound }
                val selected = scatterRows.count { it.selected && it.fileFound }
                val vis = scatterAdapter?.visibleCount() ?: scatterRows.size
                val q = if (searchQuery.isNotEmpty()) " · filter $vis/${scatterRows.size}" else ""
                tvStatus.text = "Scatter: ${scatterRows.size} · file OK $found · dipilih $selected$q"
            }
        }
    }

    private fun refreshTable() {
        val controller = FlashSessionHolder.controller ?: return
        scope.launch {
            try {
                tvStatus.text = "Membaca tabel partisi…"
                val entries = withContext(Dispatchers.IO) { controller.readPartitionTable() }
                FlashSessionHolder.lastPartitionTable = entries
                bindDeviceRows(entries)
                if (mode == PgptUiMode.SCATTER) setMode(PgptUiMode.BACKUP)
                tvStatus.text = "Tabel: ${entries.size} partisi"
            } catch (e: Exception) {
                tvStatus.text = "Gagal baca tabel: ${e.message}"
            }
        }
    }

    private fun onRunAction() {
        when (mode) {
            PgptUiMode.BACKUP -> confirmBackup()
            PgptUiMode.WRITE -> confirmWrite()
            PgptUiMode.SCATTER -> confirmScatterFlash()
        }
    }

    // ---- backup ----

    private fun backupRoot(): File {
        // App-specific external storage (no permission): Android/data/<pkg>/files/FlashingTool/backup
        val base = getExternalFilesDir("FlashingTool") ?: filesDir.resolve("FlashingTool")
        val dir = File(base, "backup")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun ensureBackupDir() {
        backupRoot()
    }

    private fun confirmBackup() {
        val selected = deviceRows.filter { it.selected }
        if (selected.isEmpty()) {
            tvStatus.text = "Centang minimal 1 partisi untuk backup."
            return
        }
        // Skip huge partitions warning
        val huge = selected.filter { it.entry.sizeBytes >= 1L * 1024 * 1024 * 1024 }
        val msg = buildString {
            append("${selected.size} partisi → ${backupRoot().absolutePath}\n")
            append(selected.joinToString("\n") {
                "• ${it.entry.name} (%.1f MB)".format(it.entry.sizeMb)
            })
            if (huge.isNotEmpty()) {
                append("\n\n⚠️ Besar (≥1GB): ${huge.joinToString { it.entry.name }} — lama di OTG.")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Backup Selected")
            .setMessage(msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("Mulai Backup") { _, _ -> runBackupSelected(selected) }
            .show()
    }

    private fun runBackupSelected(selected: List<DevicePartRow>) {
        val controller = FlashSessionHolder.controller ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessionDir = File(backupRoot(), stamp).also { it.mkdirs() }

        scope.launch {
            showProgress(true)
            var ok = 0
            try {
                for ((i, row) in selected.withIndex()) {
                    val entry = row.entry
                    tvStatus.text = "Backup [${i + 1}/${selected.size}] ${entry.name}…"
                    val outFile = File(sessionDir, "${entry.name}.img")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(outFile).use { fos ->
                            controller.readPartition(entry, fos)
                        }
                    }
                    ok++
                    tvProgressLabel.text = "$ok/${selected.size} · ${outFile.name}"
                }
                tvStatus.text = "Backup selesai: $ok partisi → ${sessionDir.absolutePath}"
            } catch (e: Exception) {
                tvStatus.text = "Backup gagal setelah $ok: ${e.message}"
            } finally {
                showProgress(false)
            }
        }
    }

    // ---- write ----

    private fun confirmWrite() {
        val selected = deviceRows.filter { it.selected && it.imageUri != null }
        val missingFile = deviceRows.filter { it.selected && it.imageUri == null }
        if (selected.isEmpty()) {
            tvStatus.text = "Centang partisi dan tekan + untuk pilih file."
            return
        }
        val policy = FlashSessionHolder.writePolicy
        val blocked = selected.mapNotNull { row ->
            val size = if (row.imageSize > 0) row.imageSize else querySize(row.imageUri!!)
            when (val d = FlashGuards.evaluateWrite(row.entry, size, policy)) {
                is FlashGuards.Decision.Block -> row.entry.name to d.reason
                else -> null
            }
        }
        if (blocked.isNotEmpty()) {
            val detail = blocked.joinToString("\n") { "• ${it.first}: ${it.second}" }
            AlertDialog.Builder(this)
                .setTitle("Guard memblokir")
                .setMessage(detail + "\n\nUntuk partisi CRITICAL, tahan tombol Write 3 detik untuk override (berbahaya).")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val risky = selected.filter { PartitionSafety.riskLevel(it.entry.name) >= RiskLevel.HIGH }
        val msg = buildString {
            append("${selected.size} partisi akan ditulis:\n")
            append(selected.joinToString("\n") {
                "• ${it.entry.name} ← ${it.imageName}"
            })
            if (missingFile.isNotEmpty()) {
                append("\n\nDilewati (tanpa file): ${missingFile.joinToString { it.entry.name }}")
            }
            if (risky.isNotEmpty()) {
                append("\n\n⚠️ HIGH: auto-backup dulu → ${risky.joinToString { it.entry.name }}")
            }
            append("\n\nGuard aktif: stop on first fail, cek ukuran, backup HIGH.")
        }
        AlertDialog.Builder(this)
            .setTitle("Flash Selected")
            .setMessage(msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("Mulai Flash") { _, _ -> runWriteSelected(selected) }
            .show()
    }

    private fun runWriteSelected(selected: List<DevicePartRow>) {
        val controller = FlashSessionHolder.controller ?: return
        val jobs = selected.map { row ->
            val uri = row.imageUri!!
            val size = if (row.imageSize > 0) row.imageSize else querySize(uri)
            FlashJobItem(entry = row.entry, fileSizeBytes = size) {
                contentResolver.openInputStream(uri)
                    ?: throw IOException("Gagal buka ${row.imageName}")
            }
        }
        scope.launch {
            showProgress(true)
            try {
                withContext(Dispatchers.IO) { controller.flashAll(jobs) }
                tvStatus.text = "Flash selesai: ${jobs.size} partisi"
            } catch (e: Exception) {
                tvStatus.text = "Flash gagal: ${e.message}"
            } finally {
                showProgress(false)
            }
        }
    }

    // ---- scatter ----

    private fun loadScatterForFullFlash(scatterUri: Uri) {
        try {
            val result = contentResolver.openInputStream(scatterUri)?.use {
                ScatterParser.parseDetailed(it, ScatterParser.StoragePref.AUTO)
            } ?: run {
                tvStatus.text = "Tidak bisa membuka scatter."
                return
            }
            val flashable = result.entries.filter { !it.isReserved && it.imagePath != null }
            if (flashable.isEmpty()) {
                tvStatus.text = "Scatter tanpa partisi ber-file_name."
                return
            }
            pendingScatterEntries = flashable
            tvStatus.text = "${flashable.size} partisi scatter (${result.storageUsed}). Pilih folder FW…"
            pickFirmwareFolder.launch(null)
        } catch (e: Exception) {
            tvStatus.text = "Gagal scatter: ${e.message}"
        }
    }

    private fun onFirmwareFolderPicked(treeUri: Uri) {
        val scatterEntries = pendingScatterEntries
        if (scatterEntries.isEmpty()) return
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        val tree = DocumentFile.fromTreeUri(this, treeUri) ?: run {
            tvStatus.text = "Folder FW tidak bisa dibuka."
            return
        }
        val children = tree.listFiles().filter { it.isFile }
        scatterRows = scatterEntries.map { entry ->
            val fileName = entry.imagePath
            val file = if (fileName != null) {
                tree.findFile(fileName)
                    ?: children.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
            } else null
            ScatterRow(entry, file, selected = file != null && file.exists())
        }.toMutableList()
        scatterAdapter = ScatterListAdapter(this, scatterRows) { updateStatusCounts() }
        mode = PgptUiMode.SCATTER
        btnFullFlash.visibility = View.VISIBLE
        rowDeviceActions.visibility = View.VISIBLE
        btnRunAction.text = "Flash Selected"
        scatterAdapter?.setFilter(searchQuery)
        lvPartitions.adapter = scatterAdapter
        scatterAdapter?.selectAllFound()
        updateStatusCounts()
    }

        private fun confirmScatterFlash() {
        val selected = scatterAdapter?.selectedRows().orEmpty()
        if (selected.isEmpty()) {
            tvStatus.text = "Tidak ada partisi scatter dipilih."
            return
        }
        val policy = FlashSessionHolder.writePolicy
        val blocked = selected.mapNotNull { row ->
            val size = row.file?.length() ?: 0L
            when (val d = FlashGuards.evaluateWrite(row.entry, size, policy)) {
                is FlashGuards.Decision.Block -> row.entry.name to d.reason
                else -> null
            }
        }
        if (blocked.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Guard memblokir")
                .setMessage(blocked.joinToString("\n") { "• ${it.first}: ${it.second}" })
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val msg = selected.joinToString("\n") {
            "• ${it.entry.name} ← ${it.entry.imagePath}"
        }
        AlertDialog.Builder(this)
            .setTitle("Flash Selected (Scatter)")
            .setMessage("${selected.size} partisi:\n$msg\n\nGuard: stop on fail, size check, auto-backup HIGH.")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("Mulai Flash") { _, _ ->
                executeScatterFlash(selected)
            }
            .show()
    }

private fun executeScatterFlash(selected: List<ScatterRow>) {
        val controller = FlashSessionHolder.controller ?: return
        val jobs = selected.map { row ->
            val file = row.file!!
            FlashJobItem(entry = row.entry, fileSizeBytes = file.length()) {
                contentResolver.openInputStream(file.uri)
                    ?: throw IOException("Gagal buka ${file.name}")
            }
        }
        scope.launch {
            showProgress(true)
            try {
                withContext(Dispatchers.IO) { controller.flashAll(jobs) }
                tvStatus.text = "Scatter flash selesai: ${jobs.size} partisi"
            } catch (e: Exception) {
                tvStatus.text = "Scatter flash gagal: ${e.message}"
            } finally {
                showProgress(false)
            }
        }
    }

    private fun showProgress(visible: Boolean) {
        progressBar.visibility = if (visible) View.VISIBLE else View.GONE
        tvProgressLabel.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) progressBar.progress = 0
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        return null
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) return c.getLong(0)
            }
        return 0L
    }
}
