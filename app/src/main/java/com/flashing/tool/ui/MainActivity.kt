package com.flashing.tool.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import com.flashing.tool.R
import com.flashing.tool.model.PartitionEntry
import com.flashing.tool.model.PartitionSafety
import com.flashing.tool.model.ScatterParser
import com.flashing.tool.protocol.FlashController
import com.flashing.tool.protocol.FlashLogEvent
import com.flashing.tool.protocol.FlashSessionHolder
import com.flashing.tool.usb.MtkUsbIds
import com.flashing.tool.usb.UsbPermissionHelper
import com.flashing.tool.usb.UsbProcessCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Entry point UI. Deliberately simple/utilitarian for an MVP — wires buttons
 * directly to FlashController rather than a full ViewModel/state-machine
 * layer, since the priority right now is validating the protocol works at
 * all against real hardware, not architectural polish.
 *
 * Threading note: all USB/BROM/DA calls are blocking I/O, so they're run on
 * Dispatchers.IO — never call FlashController methods directly on the main
 * thread.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var lvPartitions: ListView
    private lateinit var btnConnect: Button
    private lateinit var btnLoadDa: Button
    private lateinit var btnLoadScatter: Button
    private lateinit var btnHwInfo: Button
    private lateinit var btnOpenPgpt: Button
    private lateinit var tvUsbStatus: TextView
    private lateinit var tvUsbDetail: TextView
    private lateinit var viewUsbDot: View
    private lateinit var btnRefreshUsb: Button
    private lateinit var btnCopyAllLog: Button
    private lateinit var btnCopyErrorLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var scrollLog: ScrollView

    private lateinit var usbPermissionHelper: UsbPermissionHelper
    private lateinit var usbManager: UsbManager

    /** In-memory log buffer used for copy-to-clipboard (plain text, no spans). */
    private data class LogEntry(
        val timestamp: String,
        val level: LogLevel,
        val message: String,
        val stackTrace: String? = null,
    )

    private enum class LogLevel { INFO, SUCCESS, WARNING, ERROR, RAW }

    private val logBuffer = mutableListOf<LogEntry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Controller lives in FlashSessionHolder so PgptManagerActivity can reuse the same live session. */
    private val flashController: FlashController?
        get() = FlashSessionHolder.controller

    private var daPayload: ByteArray? = null
    private var scatterEntries: List<PartitionEntry> = emptyList()
    private var deviceEntries: List<PartitionEntry>
        get() = FlashSessionHolder.lastPartitionTable
        set(value) { FlashSessionHolder.lastPartitionTable = value }

    /** Partition currently being backed up — set right before launching the save-file picker, consumed in its callback. */
    private var pendingBackupEntry: PartitionEntry? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    private val pickDaFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        loadDaFile(uri)
    }

    private val pickScatterFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        loadScatterFile(uri)
    }

    private val pickBackupDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val entry = pendingBackupEntry
        pendingBackupEntry = null
        if (uri == null || entry == null) return@registerForActivityResult
        runBackup(entry, uri)
    }

    /** Prevents overlapping auto-connect attempts while one is already in flight. */
    @Volatile
    private var connectInProgress = false
    private var connectJob: Job? = null
    @Volatile private var connectCancelled = false

    /**
     * Continuous BROM watch: polls USB bus every ~25 ms while waiting.
     * Attach broadcasts alone are too late — the BROM window is often gone
     * by the time the system delivers ACTION_USB_DEVICE_ATTACHED + permission.
     *
     * Permission is requested *without* stopping this watch, so after the user
     * grants the system dialog the next poll (~25 ms) can handshake immediately
     * without requiring an unplug/replug.
     */
    @Volatile private var bromWatchRunning = false
    private var bromWatchJob: Job? = null
    /** Avoid spamming the system USB permission dialog every poll iteration. */
    private var bromPermissionRequestedFor = ""

    /**
     * Listens for USB attach/detach. On attach, if DA is already loaded,
     * immediately tries connect (and kicks the watch loop).
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isFinishing || isDestroyed) return
            try {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        refreshUsbStatus()
                        if (daPayload != null && flashController == null && !connectInProgress) {
                            if (UsbProcessCoordinator.owner == UsbProcessCoordinator.Owner.FORCE_FASTBOOT) {
                                appendLog(
                                    "MTK terdeteksi, tapi Force Fastboot aktif — auto-connect ditunda. " +
                                        "Hentikan Force Fastboot dulu.",
                                    LogLevel.WARNING
                                )
                            } else {
                                val device = usbPermissionHelper.findMtkDevice()
                                if (device != null) {
                                    appendLog(
                                        "MediaTek terdeteksi — mencoba handshake BROM…",
                                        LogLevel.INFO
                                    )
                                    if (usbManager.hasPermission(device)) {
                                        startConnect(device)
                                    } else {
                                        // Request permission once; keep sniper watch running
                                        // so after grant the next poll can handshake without replug
                                        ensureBromWatch()
                                    }
                                } else {
                                    ensureBromWatch()
                                }
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        refreshUsbStatus()
                        // New plug cycle → allow permission request again
                        bromPermissionRequestedFor = ""
                        if (flashController == null && !connectInProgress) {
                            appendLog(
                                "USB terputus (normal jika BROM timeout). " +
                                    "Jaga Volume tetap ditekan — watch tetap aktif.",
                                LogLevel.WARNING
                            )
                            if (daPayload != null) ensureBromWatch()
                        }
                    }
                }
            } catch (e: Exception) {
                try {
                    appendLog("USB receiver: ${e.message}", LogLevel.WARNING)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        GlassUi.install(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        lvPartitions = findViewById(R.id.lvPartitions)
        btnConnect = findViewById(R.id.btnConnect)
        btnLoadDa = findViewById(R.id.btnLoadDa)
        btnLoadScatter = findViewById(R.id.btnLoadScatter)
        btnHwInfo = findViewById(R.id.btnHwInfo)
        btnOpenPgpt = findViewById(R.id.btnOpenPgpt)
        tvUsbStatus = findViewById(R.id.tvUsbStatus)
        tvUsbDetail = findViewById(R.id.tvUsbDetail)
        viewUsbDot = findViewById(R.id.viewUsbDot)
        btnRefreshUsb = findViewById(R.id.btnRefreshUsb)
        btnCopyAllLog = findViewById(R.id.btnCopyAllLog)
        btnCopyErrorLog = findViewById(R.id.btnCopyErrorLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        scrollLog = findViewById(R.id.scrollLog)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbPermissionHelper = UsbPermissionHelper(this)

        btnLoadDa.setOnClickListener { pickDaFile.launch("*/*") }
        btnLoadScatter.setOnClickListener { pickScatterFile.launch("*/*") }
        btnConnect.setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.btnStopConnect).setOnClickListener { stopConnectProcess(userInitiated = true) }
        btnHwInfo.setOnClickListener { onHwInfoClicked() }
        btnOpenPgpt.setOnClickListener {
            startActivity(Intent(this, PgptManagerActivity::class.java))
        }
        btnRefreshUsb.setOnClickListener { refreshUsbStatus() }
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(
                    OVERRIDE_TRANSITION_CLOSE,
                    R.anim.fade_in,
                    R.anim.slide_down_out,
                )
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.fade_in, R.anim.slide_down_out)
            }
        }
        btnCopyAllLog.setOnClickListener { copyAllLogs() }
        btnCopyErrorLog.setOnClickListener { copyErrorLogs() }
        btnClearLog.setOnClickListener { clearLogs() }

        // Log starts empty — operational lines appear after DA load / connect

        // Reflect an already-connected session (e.g. returning from PgptManagerActivity).
        if (flashController != null) {
            tvStatus.text = "Terhubung — DA aktif"
            btnHwInfo.isEnabled = true
            btnOpenPgpt.isEnabled = true
        }

        lvPartitions.setOnItemClickListener { _, _, position, _ ->
            val entry = deviceEntries.getOrNull(position) ?: return@setOnItemClickListener
            showPartitionActionsDialog(entry)
        }

        // Initial USB status check
        refreshUsbStatus()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        refreshUsbStatus()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
    }

    /**
     * Scans currently attached USB devices and updates the status indicator.
     * This lets the user immediately distinguish:
     *  - No USB at all (cable / OTG / host problem)
     *  - USB present but not MediaTek BROM/Preloader (wrong mode or wrong device)
     *  - MediaTek BROM or Preloader detected (ready to attempt handshake)
     */
    private fun refreshUsbStatus() {
        val devices = usbManager.deviceList.values.toList()

        if (devices.isEmpty()) {
            setUsbIndicator(
                colorRes = R.color.status_error,
                statusText = getString(R.string.usb_none),
                detail = ""
            )
            return
        }

        // Accept ANY MediaTek vendor device (including OEM custom PIDs)
        val mtkDevice = devices.firstOrNull { MtkUsbIds.isMediaTek(it.vendorId) }

        if (mtkDevice != null) {
            val modeDesc = MtkUsbIds.describe(mtkDevice.productId)
            val hasPerm = usbManager.hasPermission(mtkDevice)
            val known = MtkUsbIds.isKnownMtkProduct(mtkDevice.productId)
            val statusText = when {
                mtkDevice.productId in MtkUsbIds.BROM_PRODUCT_IDS ->
                    getString(R.string.usb_brom)
                mtkDevice.productId in MtkUsbIds.PRELOADER_PRODUCT_IDS ->
                    getString(R.string.usb_preloader)
                else -> getString(R.string.usb_mtk_unknown)
            }
            val detail = buildString {
                append("VID=0x%04X PID=0x%04X %s".format(
                    mtkDevice.vendorId, mtkDevice.productId, modeDesc
                ))
                if (!known) append(" · PID non-standar")
                append(if (hasPerm) " · izin OK" else " · ${getString(R.string.usb_permission_needed)}")
            }
            setUsbIndicator(
                colorRes = R.color.status_ok,
                statusText = statusText,
                detail = detail
            )
            return
        }

        // Non-MediaTek USB device(s) — compact dump
        val dump = devices.joinToString(" · ") { d ->
            "0x%04X:0x%04X".format(d.vendorId, d.productId)
        }
        setUsbIndicator(
            colorRes = R.color.status_warn,
            statusText = getString(R.string.usb_other),
            detail = dump
        )
    }

    private fun setUsbIndicator(colorRes: Int, statusText: String, detail: String) {
        val color = ContextCompat.getColor(this, colorRes)
        tvUsbStatus.text = statusText
        tvUsbStatus.setTextColor(color)
        if (detail.isBlank()) {
            tvUsbDetail.visibility = android.view.View.GONE
            tvUsbDetail.text = ""
        } else {
            tvUsbDetail.visibility = android.view.View.VISIBLE
            tvUsbDetail.text = detail
        }

        val dot = GradientDrawable()
        dot.shape = GradientDrawable.OVAL
        dot.setColor(color)
        viewUsbDot.background = dot
    }

    private fun appendLog(message: String, level: LogLevel = LogLevel.INFO, cause: Throwable? = null) {
        if (isFinishing || isDestroyed) return
        val ts = timeFmt.format(Date())
        val stack = cause?.let { t ->
            buildString {
                append(t.toString())
                t.stackTrace.take(12).forEach { el ->
                    append("\n    at ")
                    append(el.toString())
                }
                if (t.stackTrace.size > 12) append("\n    ... ${t.stackTrace.size - 12} more")
            }
        }
        val entry = LogEntry(ts, level, message, stack)
        logBuffer.add(entry)

        val colorRes = when (level) {
            LogLevel.INFO -> R.color.log_info
            LogLevel.SUCCESS -> R.color.log_success
            LogLevel.WARNING -> R.color.log_warning
            LogLevel.ERROR -> R.color.log_error
            LogLevel.RAW -> R.color.log_raw
        }
        val color = ContextCompat.getColor(this, colorRes)
        val tsColor = ContextCompat.getColor(this, R.color.log_timestamp)

        val prefix = when (level) {
            LogLevel.INFO -> "I"
            LogLevel.SUCCESS -> "S"
            LogLevel.WARNING -> "W"
            LogLevel.ERROR -> "E"
            LogLevel.RAW -> "R"
        }

        val line = "[$ts] [$prefix] $message" +
            if (stack != null) "\n$stack" else ""

        val spannable = SpannableStringBuilder()
        val start = 0
        spannable.append(line)
        spannable.append("\n")

        // Colour the timestamp portion
        val tsEnd = ts.length + 2 // "[HH:mm:ss.SSS]"
        if (spannable.length >= tsEnd) {
            spannable.setSpan(
                ForegroundColorSpan(tsColor),
                start,
                tsEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        // Colour the rest of the line
        spannable.setSpan(
            ForegroundColorSpan(color),
            tsEnd,
            spannable.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        tvLog.append(spannable)
        // Auto-scroll to bottom
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyAllLogs() {
        if (logBuffer.isEmpty()) {
            Toast.makeText(this, R.string.toast_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val text = logBuffer.joinToString("\n") { e ->
            val stack = e.stackTrace?.let { "\n$it" } ?: ""
            "[${e.timestamp}] [${e.level.name.first()}] ${e.message}$stack"
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Flasher Log", text))
        Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyErrorLogs() {
        val errors = logBuffer.filter { it.level == LogLevel.ERROR || it.level == LogLevel.WARNING }
        if (errors.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_error_log, Toast.LENGTH_SHORT).show()
            return
        }
        val text = errors.joinToString("\n") { e ->
            val stack = e.stackTrace?.let { "\n$it" } ?: ""
            "[${e.timestamp}] [${e.level.name}] ${e.message}$stack"
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FlashingTool Error Log", text))
        Toast.makeText(this, R.string.toast_error_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun clearLogs() {
        logBuffer.clear()
        tvLog.text = ""
        appendLog("Log dihapus.", LogLevel.INFO)
    }

    private fun loadDaFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                input.copyTo(buffer)
                daPayload = buffer.toByteArray()
                appendLog("DA dimuat: ${daPayload?.size} bytes ($uri)", LogLevel.SUCCESS)
                appendLog(
                    "Memantau BROM. Matikan HP → tahan Volume → colok OTG (jangan lepas Volume).",
                    LogLevel.INFO
                )
                ensureBromWatch()
                tvStatus.text = "Memantau BROM (siaga)…"
            findViewById<android.widget.ProgressBar>(R.id.progressBar).apply {
                visibility = android.view.View.VISIBLE
                isIndeterminate = true
            }
            }
        } catch (e: Exception) {
            appendLog("Gagal memuat DA: ${e.message}", LogLevel.ERROR, e)
        }
    }

    private fun loadScatterFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val result = ScatterParser.parseDetailed(input, ScatterParser.StoragePref.AUTO)
                scatterEntries = result.entries
                appendLog(
                    "Scatter dimuat: ${result.entries.size} partisi " +
                        "(storage ${result.storageUsed}" +
                        (result.platform?.let { ", $it" } ?: "") +
                        (result.project?.let { " / $it" } ?: "") +
                        ")",
                    LogLevel.SUCCESS,
                )
                if (result.skippedDuplicateStorage > 0) {
                    appendLog(
                        "Scatter berisi layout EMMC+UFS — memakai ${result.storageUsed} saja.",
                        LogLevel.INFO,
                    )
                }
            }
        } catch (e: Exception) {
            appendLog("Gagal memuat scatter: ${e.message}", LogLevel.ERROR, e)
        }
    }

    private fun onConnectClicked() {
        refreshUsbStatus()

        val payload = daPayload
        if (payload == null) {
            appendLog(
                "Muat DA dulu. Setelah itu tekan Hubungkan — app akan memantau BROM terus-menerus.",
                LogLevel.WARNING
            )
            return
        }

        if (flashController != null) {
            appendLog("Sudah terhubung. Hentikan dulu jika ingin koneksi ulang.", LogLevel.INFO)
            return
        }

        val device = usbPermissionHelper.findMtkDevice()
        if (device != null) {
            startConnect(device)
        } else {
            val allDevices = usbPermissionHelper.listAllDevices()
            if (allDevices.isEmpty()) {
                appendLog(
                    "Memantau BROM…\n" +
                        "1. Matikan HP target\n" +
                        "2. Tahan Volume (Up/Down)\n" +
                        "3. Colok OTG ke HP ini\n" +
                        "Jendela BROM hanya ±0,5–2 detik — biarkan app tetap di layar ini.",
                    LogLevel.INFO
                )
            } else {
                val dump = allDevices.joinToString("\n") { d ->
                    "  VID=0x%04X PID=0x%04X  name=${d.deviceName}".format(d.vendorId, d.productId)
                }
                appendLog(
                    "Belum ada MediaTek (VID 0x0E8D). Perangkat di bus:\n$dump\n" +
                        "Memantau terus — colok ulang sambil tahan Volume.",
                    LogLevel.WARNING
                )
            }
            ensureBromWatch()
            tvStatus.text = "Memantau BROM (siaga)…"
            findViewById<android.widget.ProgressBar>(R.id.progressBar).apply {
                visibility = android.view.View.VISIBLE
                isIndeterminate = true
            }
        }
    }

    /**
     * Poll USB bus for a MediaTek device and attempt handshake as soon as one
     * appears with permission already granted. Survives short BROM windows.
     *
     * Permission is requested *inside* the loop without stopping the watch.
     * After the user grants the dialog, the next ~25 ms poll sees hasPermission
     * and starts the handshake immediately — no unplug/replug required if the
     * device is still in the BROM window (or appears again on the next plug).
     */
    private fun ensureBromWatch() {
        if (bromWatchRunning || connectInProgress || flashController != null) return
        if (daPayload == null) return
        if (isFinishing || isDestroyed) return

        bromWatchRunning = true
        appendLog("Watch BROM aktif (sniper poll ~25 ms).", LogLevel.INFO)
        bromWatchJob = scope.launch(Dispatchers.IO) {
            var lastLogMs = 0L
            while (isActive && bromWatchRunning && !connectInProgress && flashController == null) {
                try {
                    val dev = usbPermissionHelper.findMtkDevice()
                    if (dev != null) {
                        val hasPerm = usbManager.hasPermission(dev)
                        if (hasPerm) {
                            withContext(Dispatchers.Main) {
                                if (!connectInProgress && flashController == null && !isFinishing) {
                                    appendLog(
                                        "MTK muncul di bus (izin OK) — handshake segera…",
                                        LogLevel.SUCCESS
                                    )
                                    startConnect(dev)
                                }
                            }
                            // startConnect sets connectInProgress; exit watch
                            break
                        } else {
                            // Request permission once per device key; do NOT stop the watch
                            val key = "%04x:%04x:%s".format(
                                dev.vendorId, dev.productId, dev.deviceName
                            )
                            if (bromPermissionRequestedFor != key) {
                                bromPermissionRequestedFor = key
                                withContext(Dispatchers.Main) {
                                    if (!isFinishing && !isDestroyed) {
                                        appendLog(
                                            "MTK terlihat tapi belum diizinkan — minta izin… " +
                                                "(setelah Izinkan, sniper lanjut otomatis tanpa cabut-colok)",
                                            LogLevel.INFO
                                        )
                                        usbPermissionHelper.requestPermission(dev) { granted ->
                                            if (isFinishing || isDestroyed) return@requestPermission
                                            if (granted) {
                                                appendLog(
                                                    "Izin USB diberikan — sniper tetap aktif, " +
                                                        "handshake otomatis jika perangkat masih di bus.",
                                                    LogLevel.SUCCESS
                                                )
                                                // Device may still be present; next poll will connect.
                                                // If BROM already timed out, next plug is instant (permission cached).
                                            } else {
                                                bromPermissionRequestedFor = ""
                                                appendLog(
                                                    "Izin USB ditolak. Izinkan di dialog sistem.",
                                                    LogLevel.ERROR
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - lastLogMs > 2500) {
                                    lastLogMs = now
                                    withContext(Dispatchers.Main) {
                                        if (!isFinishing) {
                                            appendLog(
                                                "Menunggu dialog izin USB… (sniper tetap jalan)",
                                                LogLevel.INFO
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                // Sniper poll (~25 ms): BROM window often only 0.3–1 s
                delay(25)
            }
            bromWatchRunning = false
            bromWatchJob = null
        }
    }

    private fun stopBromWatch() {
        bromWatchRunning = false
        bromWatchJob?.cancel()
        bromWatchJob = null
        if (!connectInProgress) {
            findViewById<android.widget.ProgressBar?>(R.id.progressBar)?.apply {
                isIndeterminate = false
                visibility = android.view.View.GONE
            }
        }
    }

    /**
     * Core connect path used both by the button, USB attach, and BROM watch.
     * Must be fast when permission is already granted.
     */
    private fun startConnect(device: android.hardware.usb.UsbDevice) {
        if (isFinishing || isDestroyed) return
        val payload = daPayload
        if (payload == null) {
            appendLog("DA belum dimuat — koneksi dibatalkan.", LogLevel.WARNING)
            return
        }
        if (connectInProgress) {
            return
        }
        if (!UsbProcessCoordinator.tryAcquire(UsbProcessCoordinator.Owner.FLASHER_BROM)) {
            appendLog(
                "USB sedang dipakai ${UsbProcessCoordinator.describeOwner()}. Hentikan prosesnya dulu.",
                LogLevel.ERROR
            )
            return
        }
        connectInProgress = true
        connectCancelled = false
        stopBromWatch()
        setConnectProgress(0, 7, "Memicu BROM / meminta izin USB…")

        val alreadyGranted = usbManager.hasPermission(device)
        appendLog(
            "MTK: VID=0x%04X PID=0x%04X (%s)%s".format(
                device.vendorId,
                device.productId,
                MtkUsbIds.describe(device.productId),
                if (alreadyGranted) " — izin sudah ada" else " — meminta izin…"
            ),
            LogLevel.SUCCESS
        )

        // Permission is normally already granted by ensureBromWatch before we get here.
        // Still request as a safety net (no-op if already granted).
        usbPermissionHelper.requestPermission(device) { granted ->
            if (isFinishing || isDestroyed) {
                connectInProgress = false
                UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FLASHER_BROM)
                return@requestPermission
            }
            if (!granted) {
                appendLog(
                    "Izin USB ditolak. Izinkan dialog sistem — sniper akan lanjut otomatis.",
                    LogLevel.ERROR
                )
                connectInProgress = false
                UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FLASHER_BROM)
                bromPermissionRequestedFor = ""
                refreshUsbStatus()
                ensureBromWatch()
                return@requestPermission
            }

            // CRITICAL: re-fetch device from bus — attach-time reference may be stale
            // after the permission dialog, and BROM may have already re-enumerated.
            val liveDevice = usbPermissionHelper.findMtkDevice()
            if (liveDevice == null) {
                appendLog(
                    "Izin OK, tapi perangkat sudah hilang dari bus (BROM timeout). " +
                        "Sniper tetap aktif — colok ulang sambil tahan Volume (izin sudah tersimpan).",
                    LogLevel.WARNING
                )
                connectInProgress = false
                UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FLASHER_BROM)
                refreshUsbStatus()
                ensureBromWatch()
                return@requestPermission
            }

            appendLog("Izin OK — handshake BROM…", LogLevel.INFO)
            setConnectProgress(1, 7, "Izin OK — memulai handshake BROM…")
            refreshUsbStatus()

            FlashSessionHolder.clear()
            val controller = FlashController(usbManager, liveDevice) { event ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) handleEvent(event)
                }
            }
            FlashSessionHolder.controller = controller

            connectJob = scope.launch {
                try {
                    if (connectCancelled) {
                        appendLog("Koneksi dibatalkan sebelum handshake.", LogLevel.WARNING)
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        if (connectCancelled) return@withContext
                        // Preferred address; FlashController auto-tries fallbacks
                        // (0x200000, 0x40000000, 0x41E00000, …) if rejected.
                        val daLoadAddress = 0x200000L
                        controller.connectAndBoostrapDa(payload, daLoadAddress)
                    }
                    if (isFinishing || isDestroyed) return@launch
                    if (connectCancelled) {
                        appendLog("Dibatalkan — sesi ditutup.", LogLevel.WARNING)
                        FlashSessionHolder.clear()
                        tvStatus.text = "Dibatalkan"
                        return@launch
                    }
                    stopBromWatch()
                    tvStatus.text = "Terhubung — DA aktif"
                            btnHwInfo.isEnabled = true
                    btnOpenPgpt.isEnabled = true
                    appendLog("Sesi DA aktif — auto baca tabel partisi…", LogLevel.SUCCESS)
                    try {
                        val entries = withContext(Dispatchers.IO) { controller.readPartitionTable() }
                        deviceEntries = entries
                        lvPartitions.adapter = PartitionListAdapter(this@MainActivity, entries)
                        appendLog("Tabel partisi auto: ${entries.size} entri (siap di PGPT Manager)", LogLevel.SUCCESS)
                    } catch (e: Exception) {
                        appendLog("Auto baca partisi gagal: ${e.message}", LogLevel.WARNING)
                    }
                    refreshUsbStatus()
                } catch (e: Exception) {
                    if (isFinishing || isDestroyed) return@launch
                    if (connectCancelled) {
                        appendLog("Proses Flasher dihentikan.", LogLevel.WARNING)
                    } else {
                        appendLog("Gagal terhubung: ${e.message}", LogLevel.ERROR, e)
                        appendLog(
                            "Tips: tahan Volume, colok ulang (jangan lepas Volume). " +
                                "Izin USB sudah ada — sniper akan tangkap plug berikutnya otomatis.",
                            LogLevel.WARNING
                        )
                        tvStatus.text = "Gagal — memantau lagi…"
                    }
                    FlashSessionHolder.clear()
                    refreshUsbStatus()
                    if (!connectCancelled) ensureBromWatch()
                } finally {
                    connectInProgress = false
                    connectJob = null
                    if (FlashSessionHolder.controller == null) {
                        UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FLASHER_BROM)
                    }
                }
            }
        }
    }

    /**
     * Stop BROM auto-connect / handshake / bootstrap in progress and release USB ownership
     * so Force Fastboot (or another module) can take over.
     */
    private fun stopConnectProcess(userInitiated: Boolean) {
        stopBromWatch()
        if (!connectInProgress && connectJob == null && FlashSessionHolder.controller == null) {
            if (userInitiated) {
                appendLog("Tidak ada proses berjalan.", LogLevel.INFO)
            }
            UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FLASHER_BROM)
            return
        }
        connectCancelled = true
        connectInProgress = false
        connectJob?.cancel()
        connectJob = null
        try {
            FlashSessionHolder.clear()
        } catch (_: Exception) {
        }
        UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FLASHER_BROM)
        btnHwInfo.isEnabled = false
        btnOpenPgpt.isEnabled = false
        tvStatus.text = "Proses dihentikan"
        if (userInitiated) {
            appendLog(
                "Proses dihentikan. USB dilepas. Tekan Hubungkan untuk memantau lagi.",
                LogLevel.WARNING
            )
        }
        refreshUsbStatus()
    }


    /**
     * Reads CMD:GET-HW-INFO from the DA and shows the raw field map. This is
     * the most direct way to see what the DA reports about the connected
     * chip — including, per PROTOCOL_NOTES.md, where EFUSE sla_en/daa_en
     * indicators tend to appear in the DA's own diagnostic output. Treat
     * this as a debugging tool, not a guaranteed "SLA status" readout — the
     * exact field names returned here haven't been captured from a live
     * session yet, so read whatever comes back and compare against what you
     * see in the log.
     */
    private fun onHwInfoClicked() {
        val controller = flashController ?: return
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { controller.getHwInfo() }
                val lines = buildString {
                    info.forEach { (k, v) ->
                        if (k == "raw") return@forEach
                        append(k).append(": ").append(v).append('\n')
                    }
                    if (info["sla_enabled"] == "true") {
                        append('\n')
                        append("⚠ SLA ENABLED — write/full flash mungkin butuh auth/sign (Penumbra).")
                    }
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Info Hardware / DA")
                    .setMessage(lines)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                appendLog("Gagal baca info HW: ${e.message}", LogLevel.ERROR, e)
            }
        }
    }

    /**
     * MainActivity's partition list only exposes backup (read) — full PGPT
     * management (write/flash per partition, full-flash) lives in
     * PgptManagerActivity, reached via "Buka PGPT Manager" once a table has
     * been read at least once. Keeping write actions out of the quick list
     * here is deliberate: this screen is for a fast look/backup, not where
     * destructive actions should be one tap away.
     */
    private fun showPartitionActionsDialog(entry: PartitionEntry) {
        val warning = PartitionSafety.warningFor(entry.name)
        val message = buildString {
            append("Ukuran: ${"%.1f".format(entry.sizeMb)} MB\n")
            if (warning != null) {
                append("\n⚠️ $warning\n")
            }
            append("\nTulis/flash: lewat PGPT Manager.")
        }

        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(message)
            .setPositiveButton(R.string.action_backup) { _, _ ->
                pendingBackupEntry = entry
                pickBackupDestination.launch("${entry.name}.img")
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun runBackup(entry: PartitionEntry, destination: Uri) {
        val controller = flashController ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destination)?.use { output ->
                        controller.readPartition(entry, output)
                    } ?: throw java.io.IOException("Tujuan file tidak bisa dibuka")
                }
                appendLog("Backup '${entry.name}' tersimpan: $destination", LogLevel.SUCCESS)
            } catch (e: Exception) {
                appendLog("Backup '${entry.name}' gagal: ${e.message}", LogLevel.ERROR, e)
            }
        }
    }

    private fun handleEvent(event: FlashLogEvent) {
        when (event) {
            is FlashLogEvent.Info -> appendLog(event.message, LogLevel.INFO)
            is FlashLogEvent.Success -> {
                appendLog(event.message, LogLevel.SUCCESS)
                if (event.message.contains("KONEKSI DA AKTIF")) {
                    setConnectProgress(7, 7, "Terhubung — DA aktif", complete = true)
                }
            }
            is FlashLogEvent.Warning -> appendLog(event.message, LogLevel.WARNING)
            is FlashLogEvent.Raw -> {
                // Truncate very long hex dumps to keep UI responsive
                val hex = if (event.hex.length > 240) event.hex.take(240) + " …" else event.hex
                appendLog("[${event.direction}] $hex", LogLevel.RAW)
            }
            is FlashLogEvent.Error -> {
                appendLog(event.message, LogLevel.ERROR, event.cause)
                setConnectProgress(0, 7, "Gagal — ${event.message.take(48)}", complete = true, failed = true)
            }
            is FlashLogEvent.Progress -> {
                val pct = if (event.bytesTotal > 0) (event.bytesDone * 100 / event.bytesTotal) else 0
                val bar = findViewById<android.widget.ProgressBar>(R.id.progressBar)
                bar.visibility = android.view.View.VISIBLE
                bar.isIndeterminate = false
                // Map byte progress into phase 4 band (~40–55% of overall bar) when connecting
                if (connectInProgress && event.bytesTotal > 0) {
                    val mapped = 40 + (pct * 15 / 100).toInt().coerceIn(0, 15)
                    bar.progress = mapped
                    tvStatus.text = "Mengirim DA… $pct% (${event.bytesDone}/${event.bytesTotal} B)"
                } else {
                    bar.progress = pct.toInt().coerceIn(0, 100)
                    tvStatus.text = "Progres: $pct% (${event.bytesDone}/${event.bytesTotal} B)"
                    if (event.bytesDone >= event.bytesTotal && event.bytesTotal > 0) {
                        bar.postDelayed({
                            if (!connectInProgress) bar.visibility = android.view.View.GONE
                        }, 600)
                    }
                }
            }
            is FlashLogEvent.Phase -> {
                setConnectProgress(event.step, event.total, event.label)
            }
            is FlashLogEvent.PartitionStep -> {
                appendLog("[${event.index}/${event.total}] ${event.partitionName}", LogLevel.INFO)
                val bar = findViewById<android.widget.ProgressBar>(R.id.progressBar)
                bar.visibility = android.view.View.VISIBLE
                bar.isIndeterminate = false
                bar.max = 100
                bar.progress = if (event.total > 0) (event.index * 100 / event.total) else 0
                tvStatus.text = "Partisi ${event.index}/${event.total}: ${event.partitionName}"
            }
        }
    }

    /**
     * Updates the header status line and horizontal progress bar for BROM→DA connect.
     */
    private fun setConnectProgress(
        step: Int,
        total: Int,
        label: String,
        complete: Boolean = false,
        failed: Boolean = false,
    ) {
        val bar = findViewById<android.widget.ProgressBar>(R.id.progressBar)
        bar.visibility = android.view.View.VISIBLE
        bar.isIndeterminate = false
        bar.max = 100
        val pct = when {
            complete && !failed -> 100
            failed -> bar.progress.coerceAtLeast(5)
            total > 0 -> ((step.coerceAtLeast(0) * 100) / total).coerceIn(0, 100)
            else -> 0
        }
        bar.progress = pct
        tvStatus.text = if (total > 0 && !complete) {
            "[$step/$total] $label"
        } else {
            label
        }
        if (complete) {
            bar.postDelayed({
                if (!connectInProgress) {
                    bar.visibility = android.view.View.GONE
                }
            }, if (failed) 2000 else 800)
        }
    }

    /**
     * Deliberately does NOT close flashController here — the session lives in
     * FlashSessionHolder and is shared with PgptManagerActivity. It's closed
     * explicitly on reconnect (see onConnectClicked) or when the user leaves
     * both screens for good. For a single-developer debugging tool this is an
     * acceptable simplification; a production app would want a bound Service
     * to own the USB connection's lifecycle more rigorously.
     */
    override fun onDestroy() {
        stopBromWatch()
        connectCancelled = true
        connectJob?.cancel()
        connectJob = null
        connectInProgress = false
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        // Do not clear FlashSessionHolder here — may still be used by PgptManager
        super.onDestroy()
    }
}
