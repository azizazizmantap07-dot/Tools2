package com.flashing.tool.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.flashing.tool.R
import com.flashing.tool.protocol.AdbClient
import com.flashing.tool.protocol.FastbootSession
import com.flashing.tool.protocol.NeedFastbootdException
import com.flashing.tool.protocol.NeedBootloaderException
import com.flashing.tool.usb.FastbootUsbIds
import com.flashing.tool.usb.PreloaderForcer
import com.flashing.tool.usb.UsbPermissionHelper
import com.flashing.tool.usb.UsbProcessCoordinator
import com.flashing.tool.usb.UsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone Fastboot terminal (Bugjaeger-style command entry) over USB OTG.
 * Independent of BROM/DA — device must be in fastboot/bootloader mode.
 */
class FastbootActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_CONNECT = "auto_connect"
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvLogCmd: TextView
    private lateinit var tvLogStatus: TextView
    private lateinit var scrollLogCmd: ScrollView
    private lateinit var scrollLogStatus: ScrollView
    private lateinit var btnTabCmd: com.google.android.material.button.MaterialButton
    private lateinit var btnTabStatus: com.google.android.material.button.MaterialButton
    private lateinit var etCommand: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnRun: Button

    /** 0 = Command (exec), 1 = Status / Debug */
    private var activeLogTab = 0

    private data class LogEntry(val level: Level, val line: String, val tab: Int)
    private val logEntries = mutableListOf<LogEntry>()

    private lateinit var usbManager: UsbManager
    private lateinit var usbPermissionHelper: UsbPermissionHelper

    private var transport: UsbTransport? = null
    private var session: FastbootSession? = null

    private var pendingFlashPartition: String? = null
    private var pendingFlashUri: Uri? = null
    private var pendingFlashOpt: FastbootSession.FlashOptions? = null
    @Volatile private var resumeFlashAfterFastbootd = false

    private val scope = CoroutineScope(Dispatchers.Main)
    private var forceJob: Job? = null
    @Volatile private var forceRunning = false
    @Volatile private var connectInFlight = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isFinishing || isDestroyed) return
            try {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val dev = extractUsbDevice(intent)
                        log("USB terpasang: " + describeDevice(dev), Level.INFO)
                        refreshDeviceStatus()
                        // Auto-connect if it looks like fastboot and we are idle
                        if (session == null && dev != null &&
                            FastbootUsbIds.isLikelyFastbootDevice(dev)
                        ) {
                            log("Perangkat fastboot terdeteksi — menghubungkan otomatis…", Level.SUCCESS)
                            connectDevice(dev)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val dev = extractUsbDevice(intent)
                        log("USB dicabut: " + describeDevice(dev), Level.WARNING)
                        // If our session device was removed, disconnect
                        if (session != null) {
                            log("Sesi ditutup: perangkat terlepas.", Level.WARNING)
                            disconnect()
                        }
                        refreshDeviceStatus()
                    }
                }
            } catch (e: Exception) {
                // Never let a USB broadcast crash the activity
                try {
                    log("USB receiver error: ${e.message}", Level.WARNING)
                } catch (_: Exception) {
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    /**
     * Maps a path token shown in the command line → SAF Uri.
     * Used when the user picks a file with (+) so Run can open the real content.
     */
    private val pickedPathToUri = linkedMapOf<String, Uri>()

    /** When true, next file pick inserts path into etCommand (Bugjaeger style). */
    private var pickModeInsertIntoCommand = true

    private val pickFlashFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            pendingFlashPartition = null
            return@registerForActivityResult
        }
        // Persist read access across reboots when possible
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* not all providers support persistable */ }

        if (pickModeInsertIntoCommand) {
            insertPickedFileIntoCommand(uri)
            pendingFlashPartition = null
            return@registerForActivityResult
        }

        // Legacy immediate-flash path (tombol "flash file…")
        val part = pendingFlashPartition
        pendingFlashPartition = null
        if (part == null) return@registerForActivityResult
        runFlash(part, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fastboot)

        tvStatus = findViewById(R.id.tvFbStatus)
        tvLogCmd = findViewById(R.id.tvFbLogCmd)
        tvLogStatus = findViewById(R.id.tvFbLogStatus)
        scrollLogCmd = findViewById(R.id.scrollFbLogCmd)
        scrollLogStatus = findViewById(R.id.scrollFbLogStatus)
        btnTabCmd = findViewById(R.id.btnTabCmd)
        btnTabStatus = findViewById(R.id.btnTabStatus)
        etCommand = findViewById(R.id.etFbCommand)
        btnConnect = findViewById(R.id.btnFbConnect)
        btnDisconnect = findViewById(R.id.btnFbDisconnect)
        btnRun = findViewById(R.id.btnFbRun)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbPermissionHelper = UsbPermissionHelper(this)

        btnConnect.setOnClickListener { connect() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnRun.setOnClickListener { runTypedCommand() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFbPickFile)
            .setOnClickListener { pickFileIntoCommand() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAdbRebootBootloader)
            .setOnClickListener { adbReboot("bootloader") }
        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.slide_down_out)
        }
        btnTabCmd.setOnClickListener { showLogTab(0) }
        btnTabStatus.setOnClickListener { showLogTab(1) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFbCopyAllLog)
            .setOnClickListener { copyAllLogs() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFbCopyErrorLog)
            .setOnClickListener { copyErrorLogs() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFbClearLog)
            .setOnClickListener { clearActiveLog() }
        showLogTab(0)

        etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                runTypedCommand()
                true
            } else false
        }

        // Quick-command chips removed; type commands at the fastboot> prompt

        // Terminal starts empty — output appears after commands (platform-tools style)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(usbAttachReceiver, filter)
            }
        } catch (e: Exception) {
            log("Gagal register USB receiver: ${e.message}", Level.WARNING)
        }

        refreshDeviceStatus()

        if (intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) {
            log("Auto-connect setelah Force Fastboot…", Level.INFO)
            btnConnect.post { connect() }
        } else {
            // Jika device sudah tercolok saat membuka halaman
            btnConnect.post {
                val existing = findFastbootDevices()
                if (existing.isNotEmpty() && session == null) {
                    log(
                        "${existing.size} perangkat fastboot ditemukan — menghubungkan yang pertama…",
                        Level.SUCCESS
                    )
                    connectDevice(existing.first())
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbAttachReceiver)
        } catch (_: Exception) {
        }
        stopForceFastboot()
        disconnect()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceStatus()
    }

    private enum class Level { INFO, SUCCESS, WARNING, ERROR, RAW }

    private fun showLogTab(tab: Int) {
        activeLogTab = tab
        if (tab == 0) {
            scrollLogCmd.visibility = View.VISIBLE
            scrollLogStatus.visibility = View.GONE
            btnTabCmd.setStylePrimary()
            btnTabStatus.setStyleTonal()
        } else {
            scrollLogCmd.visibility = View.GONE
            scrollLogStatus.visibility = View.VISIBLE
            btnTabCmd.setStyleTonal()
            btnTabStatus.setStylePrimary()
        }
    }

    private fun com.google.android.material.button.MaterialButton.setStylePrimary() {
        // Visual: filled = active tab
        setBackgroundColor(ContextCompat.getColor(this@FastbootActivity, R.color.accent_purple))
        setTextColor(ContextCompat.getColor(this@FastbootActivity, R.color.text_primary))
    }

    private fun com.google.android.material.button.MaterialButton.setStyleTonal() {
        setBackgroundColor(ContextCompat.getColor(this@FastbootActivity, R.color.card_bg))
        setTextColor(ContextCompat.getColor(this@FastbootActivity, R.color.text_secondary))
    }

    private fun clearActiveLog() {
        if (activeLogTab == 0) {
            tvLogCmd.text = ""
            logEntries.removeAll { it.tab == 0 }
        } else {
            tvLogStatus.text = ""
            logEntries.removeAll { it.tab == 1 }
        }
    }

    /** Status / progress / debug (USB, izin, force, koneksi, tips). */
    private fun log(msg: String, level: Level = Level.INFO) {
        appendToLog(tvLogStatus, scrollLogStatus, msg, level, tab = 1)
    }

    /** Command exec + respon protokol (hasil getvar/flash/erase, RAW session). */
    private fun logCmd(msg: String, level: Level = Level.INFO) {
        appendToLog(tvLogCmd, scrollLogCmd, msg, level, tab = 0)
    }

    private fun appendToLog(
        tv: TextView,
        scroll: ScrollView,
        msg: String,
        level: Level,
        tab: Int,
    ) {
        val colorRes = when (level) {
            Level.INFO -> R.color.log_info
            Level.SUCCESS -> R.color.log_success
            Level.WARNING -> R.color.log_warning
            Level.ERROR -> R.color.log_error
            Level.RAW -> R.color.log_raw
        }
        val color = ContextCompat.getColor(this, colorRes)
        // Detailed lines for bug/error reports (timestamp + section + level)
        val ts = timeFmt.format(Date())
        val tag = when (level) {
            Level.INFO -> "I"
            Level.SUCCESS -> "S"
            Level.WARNING -> "W"
            Level.ERROR -> "E"
            Level.RAW -> "R"
        }
        val section = if (tab == 0) "CMD" else "STS"
        val plain = "[$ts] [$section][$tag] $msg"
        logEntries.add(LogEntry(level, plain, tab))
        val line = "$plain\n"
        val sb = SpannableStringBuilder(line)
        sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.append(sb)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Copy both Command + Status logs (or only the active tab if preferred: all combined). */
    private fun copyAllLogs() {
        if (logEntries.isEmpty()) {
            Toast.makeText(this, R.string.toast_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val text = logEntries.joinToString("\n") { it.line }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Fastboot Log", text))
        Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyErrorLogs() {
        val errors = logEntries.filter {
            it.level == Level.WARNING || it.level == Level.ERROR
        }
        if (errors.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_error_log, Toast.LENGTH_SHORT).show()
            return
        }
        val text = errors.joinToString("\n") { it.line }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Fastboot W+E Log", text))
        Toast.makeText(this, R.string.toast_error_log_copied, Toast.LENGTH_SHORT).show()
    }


    // ---------- Force fastboot (MTK Preloader → FASTBOOT text) ----------

    private fun startForceFastboot() {
        if (session != null) {
            log("Sudah terhubung. Putuskan dulu untuk force ulang.", Level.WARNING)
            return
        }
        if (forceRunning) {
            log("Force sudah berjalan…", Level.INFO)
            return
        }
        forceRunning = true
        log("Force Fastboot dimulai. Colok perangkat via OTG (Preloader).", Level.INFO)
        log("FASTBOOT dikirim berulang sampai mode fastboot muncul.", Level.INFO)
        tvStatus.text = "Force Fastboot: menunggu Preloader…"
        forceJob = scope.launch(Dispatchers.IO) {
            var burst = 0
            while (forceRunning && isActive) {
                val devices = usbManager.deviceList.values.toList()

                // Already in fastboot?
                val fb = devices.firstOrNull {
                    FastbootUsbIds.isLikelyFastbootDevice(it)
                }
                if (fb != null) {
                    log("Fastboot terdeteksi — force dihentikan, menghubungkan…", Level.SUCCESS)
                    forceRunning = false
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Fastboot terdeteksi — menghubungkan…"
                        connectDevice(fb)
                    }
                    return@launch
                }

                val pre = devices.firstOrNull { PreloaderForcer.isPreloader(it) }
                if (pre != null) {
                    if (!usbManager.hasPermission(pre)) {
                        withContext(Dispatchers.Main) {
                            usbPermissionHelper.requestPermission(pre) { granted ->
                                if (granted) {
                                    log(
                                        "Izin Preloader diberikan — burst lanjut otomatis " +
                                            "(tanpa cabut-colok jika perangkat masih di bus).",
                                        Level.SUCCESS
                                    )
                                } else {
                                    log("Izin Preloader ditolak.", Level.ERROR)
                                }
                            }
                        }
                        // Keep polling while dialog is open so bursts resume ASAP after grant
                        delay(80)
                        continue
                    }
                    burst++
                    val verbose = burst <= 5 || burst % 40 == 0
                    if (verbose) {
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Force Fastboot: burst #$burst (kirim FASTBOOT…)"
                        }
                    }
                    // Re-resolve each burst — Preloader can re-enumerate after permission
                    val livePre = devices.firstOrNull { PreloaderForcer.isPreloader(it) }
                        ?: pre
                    PreloaderForcer.sendFastboot(usbManager, livePre, burst, verbose) { msg ->
                        if (verbose) {
                            scope.launch(Dispatchers.Main) { log(msg, Level.RAW) }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (burst > 0) {
                            tvStatus.text = "Force: menunggu Preloader muncul lagi…"
                        }
                    }
                }
                delay(80)
            }
            withContext(Dispatchers.Main) {
                if (session == null) {
                    tvStatus.text = getString(R.string.fastboot_status_idle)
                    log("Force Fastboot dihentikan.", Level.INFO)
                }
            }
        }
    }

    private fun stopForceFastboot() {
        forceRunning = false
        forceJob?.cancel()
        forceJob = null
        log("Stop Force diminta.", Level.INFO)
        if (session == null) {
            tvStatus.text = getString(R.string.fastboot_status_idle)
        }
    }


    /**
     * Bugjaeger-style: while target is on Android home with USB debugging,
     * use ADB over OTG to `reboot bootloader` / `reboot fastboot`.
     * First connection may show the «Allow USB debugging» dialog on the target.
     */
    private fun adbReboot(target: String) {
        val label = if (target == "fastboot") "fastbootd" else target
        log("ADB reboot → $label — mencari perangkat dengan USB debugging…", Level.INFO)

        val all = try {
            usbManager.deviceList.values.toList()
        } catch (_: Exception) {
            emptyList()
        }

        // Prefer real ADB interface
        val adbDevice = all.firstOrNull { AdbClient.isAdbDevice(it) }
            ?: all.firstOrNull { FastbootUsbIds.hasAdbInterface(it) }

        if (adbDevice == null) {
            // True fastboot only — not every MediaTek VID while on Android UI
            val realFb = all.firstOrNull { FastbootUsbIds.isLikelyFastbootDevice(it) }
            if (realFb != null) {
                log(
                    "HP target sudah di mode bootloader/fastboot (${describeDevice(realFb)}), " +
                        "bukan Android + USB debugging.\n" +
                        "Tombol «ADB → Bootloader» hanya untuk HP yang masih di home Android.\n" +
                        "Untuk kasus ini: tekan Hubungkan (atau biarkan auto-connect).",
                    Level.WARNING
                )
            } else {
                log(
                    "Interface ADB tidak terlihat.\n" +
                        "HP target harus di *menu Android*, lalu:\n" +
                        "  1. Opsi pengembang → Debugging USB = ON\n" +
                        "  2. (MTK) Debugging USB (setelan keamanan) = ON jika ada\n" +
                        "  3. Mode USB = File transfer / MTP (bukan hanya charging)\n" +
                        "  4. Colok ulang OTG ke HP *host*\n" +
                        "  5. Dialog izin USB di HP *host* → Izinkan\n" +
                        "  6. Popup «Izinkan debugging USB» di HP *target* → Izinkan\n" +
                        "Lalu tekan lagi «ADB → Bootloader».",
                    Level.ERROR
                )
            }
            if (all.isEmpty()) {
                log("Bus USB kosong.", Level.WARNING)
            } else {
                log("Perangkat di bus (dump interface):", Level.INFO)
                all.forEach { d ->
                    val adb = AdbClient.isAdbDevice(d)
                    val fb = FastbootUsbIds.isLikelyFastbootDevice(d)
                    val androidish = FastbootUsbIds.looksLikeAndroidComposite(d)
                    log(
                        "  ${describeDevice(d)} adb=$adb fastboot=$fb androidComposite=$androidish ifaces=${d.interfaceCount}",
                        Level.RAW
                    )
                    for (i in 0 until d.interfaceCount) {
                        val intf = d.getInterface(i)
                        log(
                            "    iface$i class=0x%02X sub=0x%02X proto=0x%02X eps=%d".format(
                                intf.interfaceClass,
                                intf.interfaceSubclass,
                                intf.interfaceProtocol,
                                intf.endpointCount
                            ),
                            Level.RAW
                        )
                    }
                }
            }
            return
        }

        log("ADB device: ${describeDevice(adbDevice)}", Level.SUCCESS)
        log(
            "Catatan: popup «Izinkan debugging USB» muncul di HP *target* (bukan host). " +
                "Dialog «Izinkan akses USB» muncul di HP *host*.",
            Level.INFO
        )
        try {
            usbPermissionHelper.requestPermission(adbDevice) { granted ->
                if (isFinishing || isDestroyed) return@requestPermission
                if (!granted) {
                    log(
                        "Izin USB host ditolak di HP *host*. " +
                            "Dialog «Izinkan FlashingTool mengakses perangkat USB?» → pilih OK / Izinkan. " +
                            "Setelah Izinkan, ulangi (izin tersimpan — tidak perlu cabut-colok).",
                        Level.ERROR
                    )
                    return@requestPermission
                }
                // Re-fetch after permission dialog so openDevice works on a fresh handle
                val liveAdb = usbManager.deviceList.values.firstOrNull { d ->
                    AdbClient.isAdbDevice(d) &&
                        d.vendorId == adbDevice.vendorId &&
                        d.productId == adbDevice.productId
                } ?: adbDevice
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            AdbClient.open(
                                usbManager,
                                liveAdb,
                                filesDir,
                                log = { msg ->
                                    runOnUiThread {
                                        if (!isFinishing && !isDestroyed) log(msg, Level.RAW)
                                    }
                                }
                            ).use { client ->
                                client.connectAndReboot(target)
                            }
                        }
                        if (isFinishing || isDestroyed) return@launch
                        log(
                            "Perintah reboot dikirim. Tunggu HP masuk mode $label, lalu Hubungkan / auto-connect.",
                            Level.SUCCESS
                        )
                        tvStatus.text = "Menunggu perangkat $label…"
                    } catch (e: Exception) {
                        if (isFinishing || isDestroyed) return@launch
                        log("ADB reboot gagal: ${e.message}", Level.ERROR)
                        log(
                            "Jika popup «Izinkan debugging USB» muncul di HP *target*: " +
                                "centang «Selalu izinkan dari komputer ini» → OK, lalu ulangi.",
                            Level.WARNING
                        )
                    }
                }
            }
        } catch (e: Exception) {
            log("Gagal meminta izin USB: ${e.message}", Level.ERROR)
        }
    }

    private fun connectDevice(device: android.hardware.usb.UsbDevice) {
        if (session != null) {
            log("Sudah terhubung. Putuskan dulu untuk ganti perangkat.", Level.INFO)
            return
        }
        if (connectInFlight) {
            log("Koneksi sedang diproses…", Level.INFO)
            return
        }
        if (isFinishing || isDestroyed) return
        connectInFlight = true
        log(
            "Menghubungkan: ${FastbootUsbIds.describe(device.vendorId, device.productId)} ${device.deviceName}",
            Level.INFO
        )
        usbPermissionHelper.requestPermission(device) { granted ->
            // Always clear the in-flight flag on early exits so re-plug works
            if (isFinishing || isDestroyed) {
                connectInFlight = false
                return@requestPermission
            }
            if (!granted) {
                connectInFlight = false
                log("Izin USB ditolak. Izinkan di dialog — lalu Hubungkan lagi (izin tersimpan).", Level.ERROR)
                return@requestPermission
            }
            if (!UsbProcessCoordinator.tryAcquire(UsbProcessCoordinator.Owner.FASTBOOT_TERMINAL)) {
                connectInFlight = false
                log(
                    "USB sedang dipakai ${UsbProcessCoordinator.describeOwner()}. Hentikan proses lain dulu.",
                    Level.ERROR
                )
                return@requestPermission
            }
            // Re-fetch from bus after permission dialog — device reference may be stale
            val live = findFastbootDevices().firstOrNull { d ->
                d.vendorId == device.vendorId && d.productId == device.productId
            } ?: device
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        transport?.close()
                        val tr = UsbTransport(usbManager, live)
                        tr.open()
                        transport = tr
                        session = FastbootSession(tr) { msg ->
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) logCmd(msg, Level.RAW)
                            }
                        }
                        session?.sendCommand("getvar:product")
                        // Probe fastbootd
                        val userspace = try {
                            session?.isUserspace() == true
                        } catch (_: Exception) {
                            false
                        }
                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            if (userspace) {
                                log("Mode: fastbootd (userspace) — siap flash partisi logical.", Level.SUCCESS)
                            } else {
                                log("Mode: bootloader fastboot. Untuk system/vendor: reboot-fastboot.", Level.INFO)
                            }
                        }
                    }
                    if (!isFinishing && !isDestroyed) {
                        tvStatus.text = getString(R.string.fastboot_status_connected) +
                            " (${FastbootUsbIds.describe(live.vendorId, live.productId)})"
                        log("Terhubung. Ketik perintah atau pakai tombol.", Level.SUCCESS)
                        log("Cek: devices · getvar product", Level.INFO)
                        maybeResumePendingFlash()
                    }
                } catch (e: Exception) {
                    if (!isFinishing && !isDestroyed) {
                        log("Gagal koneksi fastboot: ${e.message}", Level.ERROR)
                        tvStatus.text = getString(R.string.fastboot_status_idle)
                    }
                    disconnect()
                } finally {
                    connectInFlight = false
                }
            }
        }
    }

    private fun findFastbootDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.filter { d ->
            FastbootUsbIds.isLikelyFastbootDevice(d)
        }
    }

    private fun describeDevice(d: UsbDevice?): String {
        if (d == null) return "(null)"
        return FastbootUsbIds.describe(d.vendorId, d.productId) + " " + d.deviceName
    }

    /** Like `fastboot devices` — lists candidates on the USB host bus. */
    private fun listDevicesCommand() {
        val all = usbManager.deviceList.values.toList()
        val fb = findFastbootDevices()
        if (all.isEmpty()) {
            logCmd("fastboot devices: (tidak ada perangkat USB di host)", Level.WARNING)
            return
        }
        logCmd("fastboot devices — ${fb.size} fastboot / ${all.size} total USB:", Level.INFO)
        if (fb.isEmpty()) {
            all.forEach { d ->
                logCmd(
                    "  ${describeDevice(d)}  [bukan fastboot / mungkin ADB atau charging]",
                    Level.RAW
                )
            }
            logCmd(
                "Tip: pastikan perangkat dalam mode fastboot. " +
                    "Sebagian OEM memakai VID lain — coba Hubungkan.",
                Level.INFO
            )
        } else {
            fb.forEach { d ->
                val serial = try { d.serialNumber ?: d.deviceName } catch (_: Exception) { d.deviceName }
                logCmd("  $serial\tfastboot\t(${describeDevice(d)})", Level.SUCCESS)
            }
        }
    }

    private fun refreshDeviceStatus() {
        if (session != null) return
        val fb = findFastbootDevices()
        if (fb.isEmpty()) {
            tvStatus.text = getString(R.string.fastboot_status_idle) + " · bus: ${usbManager.deviceList.size} USB"
        } else {
            val d = fb.first()
            tvStatus.text = "Fastboot terdeteksi (${FastbootUsbIds.describe(d.vendorId, d.productId)}) — siap dihubungkan"
        }
    }

    private fun connect() {
        val devices = findFastbootDevices()
        if (devices.isEmpty()) {
            val any = usbManager.deviceList.values.toList()
            if (any.isEmpty()) {
                log(
                    "Tidak ada perangkat USB. Colok perangkat mode fastboot via OTG, atau pakai Force Fastboot.",
                    Level.ERROR
                )
            } else {
                log("Tidak ada yang dikenali sebagai fastboot. Perangkat di bus:", Level.ERROR)
                any.forEach { d ->
                    val hint = when {
                        AdbClient.isAdbDevice(d) -> "→ ini ADB (Android). Pakai tombol ADB → Bootloader"
                        FastbootUsbIds.looksLikeAndroidComposite(d) -> "→ mode Android/MTP, bukan fastboot"
                        else -> ""
                    }
                    log("  ${describeDevice(d)} $hint", Level.RAW)
                }
                // Jangan paksa connect sebagai fastboot jika jelas Android composite / ADB
                val candidate = any.firstOrNull {
                    !AdbClient.isAdbDevice(it) && !FastbootUsbIds.looksLikeAndroidComposite(it)
                }
                if (candidate != null) {
                    log("Mencoba kandidat non-Android: ${describeDevice(candidate)}", Level.WARNING)
                    connectDevice(candidate)
                    return
                }
                log(
                    "HP terlihat masih di Android. Untuk masuk bootloader: " +
                        "ADB → Bootloader (setelah Debugging USB + izinkan popup), " +
                        "atau Force Fastboot jika softbrick.",
                    Level.INFO
                )
            }
            tvStatus.text = getString(R.string.fastboot_status_idle)
            return
        }
        if (devices.size > 1) {
            log("Beberapa perangkat fastboot: memakai yang pertama.", Level.INFO)
        }
        connectDevice(devices.first())
    }

    private fun disconnect() {
        try {
            transport?.close()
        } catch (_: Exception) {
        }
        transport = null
        session = null
        UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FASTBOOT_TERMINAL)
        tvStatus.text = getString(R.string.fastboot_status_idle)
        log("Terputus.", Level.INFO)
    }

    private fun requireSession(): FastbootSession? {
        val s = session
        if (s == null) {
            logCmd("error: no device (connect first)", Level.ERROR)
        }
        return s
    }

    private fun runTypedCommand() {
        val line = etCommand.text?.toString()?.trim().orEmpty()
        if (line.isEmpty()) return
        etCommand.text?.clear()
        runPreset(line)
    }

    /**
     * Platform-tools / Bugjaeger-style free command entry.
     *
     * Accepts:
     *   devices | adb devices | fastboot devices
     *   adb reboot bootloader|fastboot|recovery
     *   fastboot getvar all | getvar:all | getvar product
     *   flash boot | fastboot --disable-verification flash vbmeta
     *   erase userdata | erase:userdata
     *   reboot | reboot-bootloader | continue | -w | oem unlock | …
     *
     * Flash that needs a local image opens the SAF file picker.
     * All other recognised fastboot verbs are sent over the active session.
     */
    private fun runPreset(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return

        // --- devices (works without an active session) ---
        val asDevices = trimmed.equals("devices", true) ||
            trimmed.equals("fastboot devices", true) ||
            trimmed.equals("adb devices", true)
        if (asDevices) {
            listDevicesCommand()
            return
        }

        // --- adb reboot variants (device may still be in Android/ADB mode) ---
        val adbRebootMatch = Regex(
            """^(?:adb\s+)?reboot(?:\s+|:)(bootloader|fastboot|recovery)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(trimmed)
        if (adbRebootMatch != null) {
            adbReboot(adbRebootMatch.groupValues[1].lowercase())
            return
        }
        if (trimmed.equals("adb reboot", true) ||
            trimmed.equals("adb reboot bootloader", true) ||
            trimmed.equals("reboot bootloader", true)
        ) {
            // bare "adb reboot" → normal reboot is not useful here; default to bootloader
            if (trimmed.equals("adb reboot", true)) {
                log("Gunakan: adb reboot bootloader | adb reboot fastboot | adb reboot recovery", Level.INFO)
                return
            }
            adbReboot("bootloader")
            return
        }

        // --- flash <partition> [path] [flags] — Bugjaeger style ---
        val parsed = parseFlashCommand(line)
        if (parsed != null) {
            val partition = parsed.partition
            val disableVerity = parsed.disableVerity
            val disableVerification = parsed.disableVerification
            val slot = parsed.slot
            val filePath = parsed.filePath

            if (requireSession() == null) return

            pendingDisableVerity = disableVerity
            pendingDisableVerification = disableVerification
            pendingSlot = slot
            pendingFlashPartition = partition

            val flags = buildList {
                if (disableVerity) add("--disable-verity")
                if (disableVerification) add("--disable-verification")
                if (slot != null) add("--slot=$slot")
            }.joinToString(" ")
            val display = buildString {
                append("flash")
                if (flags.isNotEmpty()) append(" $flags")
                append(" $partition")
                if (filePath != null) append(" $filePath")
            }

            if (filePath.isNullOrBlank()) {
                logCmd("fastboot> $display", Level.INFO)
                logCmd("error: no image file — press + to pick, then Run", Level.ERROR)
                etCommand.setText(line.trim())
                etCommand.setSelection(etCommand.text?.length ?: 0)
                return
            }

            val uri = resolveFileUri(filePath)
            if (uri == null) {
                logCmd("fastboot> $display", Level.ERROR)
                logCmd("error: cannot open $filePath", Level.ERROR)
                return
            }

            logCmd("fastboot> $display", Level.INFO)
            runFlash(partition, uri)
            return
        }

        // --- all other commands require an active fastboot session ---
        val s = requireSession() ?: return
        logCmd("fastboot> $line", Level.INFO)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { s.executeLine(line) }
                if (result.ok) {
                    val msg = result.message.trim()
                    if (msg.contains('\n')) {
                        msg.lineSequence().forEach { logCmd(it, Level.INFO) }
                    } else if (msg.isNotEmpty()) {
                        logCmd(msg, Level.SUCCESS)
                    }
                    result.allInfo.forEach { logCmd(it, Level.INFO) }
                    if (msg.isEmpty() && result.allInfo.isEmpty()) {
                        logCmd("OKAY", Level.SUCCESS)
                    }
                } else {
                    logCmd("${result.status} ${result.message}".trim(), Level.ERROR)
                    result.allInfo.forEach { logCmd(it, Level.INFO) }
                }
            } catch (e: Exception) {
                logCmd("error: ${e.message}", Level.ERROR)
            }
        }
    }

    /**
     * @param filePath optional path / content-uri token embedded in the command line
     *                 (Bugjaeger style after pressing +)
     */
    private data class ParsedFlash(
        val partition: String,
        val disableVerity: Boolean,
        val disableVerification: Boolean,
        val slot: String?,
        val filePath: String? = null,
    )

    /**
     * Platform-tools / Bugjaeger flash line parser.
     *
     * Flags may appear anywhere. A path token (from tombol +) is captured as [ParsedFlash.filePath]:
     *   fastboot flash boot /storage/emulated/0/boot.img --slot=all
     *   flash --disable-verity --disable-verification vbmeta content://… --slot=all
     */
    private fun parseFlashCommand(raw: String): ParsedFlash? {
        var rest = raw.trim()
        when {
            rest.startsWith("fastboot ", ignoreCase = true) ->
                rest = rest.substring(9).trim()
            rest.startsWith("adb ", ignoreCase = true) ->
                rest = rest.substring(4).trim()
        }
        var disableVerity = false
        var disableVerification = false
        var slot: String? = null
        var filePath: String? = null
        val tokens = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.equals("--disable-verity", ignoreCase = true) ||
                    t.equals("--disable_verity", ignoreCase = true) ->
                    disableVerity = true
                t.equals("--disable-verification", ignoreCase = true) ||
                    t.equals("--disable_verification", ignoreCase = true) ->
                    disableVerification = true
                t.startsWith("--slot=", ignoreCase = true) -> {
                    val v = t.substringAfter("=").lowercase().trim()
                    if (v in setOf("a", "b", "all")) slot = v
                    else {
                        log("--slot harus a, b, atau all (dapat: $v)", Level.WARNING)
                        return null
                    }
                }
                t.equals("--slot", ignoreCase = true) -> {
                    val v = tokens.getOrNull(i + 1)?.lowercase()
                    if (v in setOf("a", "b", "all")) {
                        slot = v
                        i++
                    } else {
                        log("--slot membutuhkan nilai a|b|all", Level.WARNING)
                        return null
                    }
                }
                looksLikeFilename(t) || t.startsWith("content://") -> {
                    // Keep the last path token (user may re-pick)
                    filePath = t
                }
                else -> kept.add(t)
            }
            i++
        }
        if (kept.isEmpty()) return null

        val flashIdx = kept.indexOfFirst {
            it.equals("flash", ignoreCase = true) || it.startsWith("flash:", ignoreCase = true)
        }
        if (flashIdx < 0) return null

        val flashTok = kept[flashIdx]
        val partition = when {
            flashTok.contains(':') -> flashTok.substringAfter(':').trim()
            flashIdx + 1 < kept.size -> kept[flashIdx + 1].trim()
            else -> {
                log(
                    "Format: flash <partition> [path] [flags]\n" +
                        "  ketik perintah → tekan + pilih file → Run\n" +
                        "  contoh: fastboot flash boot --slot=all",
                    Level.WARNING
                )
                return null
            }
        }
        if (partition.isEmpty() || partition.startsWith("-")) return null
        return ParsedFlash(partition, disableVerity, disableVerification, slot, filePath)
    }

    /** Heuristic: token looks like a local image path / filename, not a partition name. */
    private fun looksLikeFilename(t: String): Boolean {
        val lower = t.lowercase()
        if (lower.endsWith(".img") || lower.endsWith(".bin") || lower.endsWith(".raw") ||
            lower.endsWith(".sparse") || lower.endsWith(".zip") || lower.endsWith(".gz")
        ) return true
        if (t.contains('/') || t.contains('\\') || t.matches(Regex("""^[A-Za-z]:\\.*"""))) return true
        return false
    }

    /** Tombol + : pilih file dan sisipkan path ke baris perintah (gaya Bugjaeger). */
    private fun pickFileIntoCommand() {
        pickModeInsertIntoCommand = true
        pickFlashFile.launch(arrayOf("*/*"))
    }

    private fun insertPickedFileIntoCommand(uri: Uri) {
        val pathToken = uriToCommandPath(uri)
        pickedPathToUri[pathToken] = uri

        val current = etCommand.text?.toString()?.trim().orEmpty()
        val newLine = if (current.isEmpty()) {
            pathToken
        } else {
            // Remove any previous path token so re-picking replaces it
            val tokens = current.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val withoutOldPaths = tokens.filterNot {
                looksLikeFilename(it) || it.startsWith("content://") || pickedPathToUri.containsKey(it)
            }
            (withoutOldPaths + pathToken).joinToString(" ")
        }
        etCommand.setText(newLine)
        etCommand.setSelection(newLine.length)
        // Path only appears in the prompt line; no extra chatter
    }

    /**
     * Prefer a readable filesystem path when the provider exposes one;
     * otherwise use the content:// URI string (still resolvable via [pickedPathToUri]).
     */
    private fun uriToCommandPath(uri: Uri): String {
        // Try common column for media/documents
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        // Also try real path via MediaStore DATA (may be null on modern Android)
                        if (!name.isNullOrBlank() && uri.scheme == "file") {
                            return uri.path ?: name
                        }
                    }
                }
        } catch (_: Exception) { }

        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            return uri.path!!
        }

        // Fallback: content URI as token (mapped in pickedPathToUri)
        return uri.toString()
    }

    private fun resolveFileUri(pathToken: String): Uri? {
        pickedPathToUri[pathToken]?.let { return it }
        if (pathToken.startsWith("content://") || pathToken.startsWith("file://")) {
            return try { Uri.parse(pathToken) } catch (_: Exception) { null }
        }
        // Absolute filesystem path
        val f = java.io.File(pathToken)
        if (f.isFile && f.canRead()) {
            return Uri.fromFile(f)
        }
        // Try as URI anyway
        return try {
            val u = Uri.parse(pathToken)
            if (u.scheme != null) u else null
        } catch (_: Exception) {
            null
        }
    }

    private fun promptErase() {
        val input = EditText(this).apply {
            hint = "nama partisi (userdata, cache, frp, ...)"
            setText("userdata")
            setTextColor(ContextCompat.getColor(this@FastbootActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@FastbootActivity, R.color.text_secondary))
        }
        AlertDialog.Builder(this)
            .setTitle("erase:<partition>")
            .setView(input)
            .setPositiveButton("Erase") { _, _ ->
                val part = input.text?.toString()?.trim().orEmpty()
                if (part.isNotEmpty()) {
                    confirmDanger("Hapus partisi '$part'?") {
                        runPreset("erase:$part")
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private var pendingDisableVerity = false
    private var pendingDisableVerification = false
    private var pendingSlot: String? = null

    private fun promptFlash() {
        val input = EditText(this).apply {
            hint = "nama partisi (boot, vbmeta, system, ...)"
            setTextColor(ContextCompat.getColor(this@FastbootActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@FastbootActivity, R.color.text_secondary))
        }
        val options = arrayOf(
            "Normal",
            "vbmeta: --disable-verity",
            "vbmeta: --disable-verification",
            "vbmeta: --disable-verity + --disable-verification",
            "Normal + --slot=all",
        )
        var choice = 0
        AlertDialog.Builder(this)
            .setTitle("flash <partition>")
            .setMessage(
                "Isi nama partisi. Perintah akan diisi di baris input; " +
                    "tekan + untuk memilih file, lalu Run (gaya Bugjaeger)."
            )
            .setView(input)
            .setSingleChoiceItems(options, 0) { _, which -> choice = which }
            .setPositiveButton("Isi perintah") { _, _ ->
                val part = input.text?.toString()?.trim().orEmpty()
                if (part.isEmpty()) {
                    Toast.makeText(this, "Isi nama partisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val flags = buildList {
                    if (choice == 1 || choice == 3) add("--disable-verity")
                    if (choice == 2 || choice == 3) add("--disable-verification")
                    if (choice == 4) add("--slot=all")
                }
                val cmd = buildString {
                    append("fastboot flash")
                    flags.forEach { append(" $it") }
                    append(" $part")
                }
                etCommand.setText(cmd)
                etCommand.setSelection(cmd.length)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun runFlash(partition: String, uri: Uri) {
        val s = requireSession() ?: return
        val opt = pendingFlashOpt ?: FastbootSession.FlashOptions(
            disableVerity = pendingDisableVerity,
            disableVerification = pendingDisableVerification,
            slot = pendingSlot,
        )
        pendingDisableVerity = false
        pendingDisableVerification = false
        pendingSlot = null
        pendingFlashOpt = opt

        scope.launch {
            try {
                val targets = s.resolveSlotPartitions(partition, opt.slot)
                val probePart = targets.firstOrNull() ?: partition
                val slotLabel = if (opt.slot != null) " [slot=${opt.slot} → ${targets.joinToString(",")}]" else ""

                // Auto mode switch: logical↔physical vs current bootloader/fastbootd
                val modeSwitch = withContext(Dispatchers.IO) {
                    val userspace = try { s.isUserspace() } catch (_: Exception) { false }
                    val logical = try { s.isLogical(probePart) } catch (_: Exception) {
                        probePart.lowercase() in FastbootSession.LIKELY_LOGICAL
                    }
                    when {
                        logical && !userspace -> "to_fastbootd"
                        !logical && userspace -> "to_bootloader"
                        else -> "ok"
                    }
                }
                if (modeSwitch == "to_fastbootd") {
                    log(
                        "'$probePart' logical (super) — pindah ke fastbootd, flash dilanjutkan otomatis…",
                        Level.WARNING
                    )
                    queueResumeFlash(partition, uri, opt)
                    withContext(Dispatchers.IO) {
                        try { s.rebootFastbootd() } catch (e: Exception) {
                            try { s.sendCommand("reboot-fastboot") } catch (_: Exception) { throw e }
                        }
                    }
                    log("Menunggu perangkat masuk fastbootd…", Level.INFO)
                    tvStatus.text = "Menunggu fastbootd untuk flash $probePart…"
                    disconnect()
                    return@launch
                }
                if (modeSwitch == "to_bootloader") {
                    log(
                        "'$probePart' bukan logical — kembali ke bootloader, flash dilanjutkan otomatis…",
                        Level.WARNING
                    )
                    queueResumeFlash(partition, uri, opt)
                    withContext(Dispatchers.IO) {
                        try { s.rebootBootloader() } catch (e: Exception) {
                            try { s.sendCommand("reboot-bootloader") } catch (_: Exception) { throw e }
                        }
                    }
                    log("Menunggu perangkat masuk bootloader fastboot…", Level.INFO)
                    tvStatus.text = "Menunggu bootloader untuk flash $probePart…"
                    disconnect()
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val size = contentResolver.openFileDescriptor(uri, "r")?.statSize
                        ?: throw java.io.IOException("Ukuran file tidak terbaca")
                    contentResolver.openInputStream(uri)?.use { input ->
                        logCmd(
                            "> flash $partition$slotLabel ($size bytes) verity=${opt.disableVerity} verification=${opt.disableVerification}",
                            Level.INFO
                        )
                        log(
                            "Flash $partition$slotLabel dimulai ($size bytes)…",
                            Level.INFO
                        )
                        val r = s.flash(partition, input, size, opt) { done, total ->
                            if (total > 0 && (done == total || done % (2 * 1024 * 1024) == 0L)) {
                                runOnUiThread {
                                    tvStatus.text = "Flash $partition: ${done * 100 / total}%"
                                    val bar = findViewById<android.widget.ProgressBar>(R.id.progressFb)
                                    bar.visibility = android.view.View.VISIBLE
                                    bar.progress = (done * 100 / total).toInt()
                                }
                            }
                        }
                        runOnUiThread {
                            if (r.ok) {
                                // r.message already contains concrete target e.g. "OKAY — flashed 'vendor_boot_a'"
                                logCmd("${r.message}", Level.SUCCESS)
                                log("Selesai: ${r.message}", Level.SUCCESS)
                                tvStatus.text = r.message
                            } else {
                                logCmd("FAIL: ${r.message}", Level.ERROR)
                                log("Flash gagal: ${r.message}", Level.ERROR)
                            }
                            pendingFlashUri = null
                            pendingFlashPartition = null
                            pendingFlashOpt = null
                            resumeFlashAfterFastbootd = false
                        }
                    } ?: throw java.io.IOException("File tidak bisa dibuka")
                }
            } catch (e: NeedFastbootdException) {
                log("Perlu fastbootd: ${e.message}", Level.WARNING)
                log("reboot-fastboot otomatis, flash dilanjutkan…", Level.INFO)
                queueResumeFlash(partition, uri, opt)
                try {
                    withContext(Dispatchers.IO) { s.rebootFastbootd() }
                } catch (ex: Exception) {
                    log("reboot-fastboot gagal: ${ex.message}", Level.ERROR)
                    resumeFlashAfterFastbootd = false
                    return@launch
                }
                log("Menunggu perangkat masuk fastbootd…", Level.INFO)
                tvStatus.text = "Menunggu fastbootd untuk flash $partition…"
                disconnect()
            } catch (e: NeedBootloaderException) {
                log("Perlu bootloader: ${e.message}", Level.WARNING)
                log("reboot-bootloader otomatis, flash dilanjutkan…", Level.INFO)
                queueResumeFlash(partition, uri, opt)
                try {
                    withContext(Dispatchers.IO) { s.rebootBootloader() }
                } catch (ex: Exception) {
                    log("reboot-bootloader gagal: ${ex.message}", Level.ERROR)
                    resumeFlashAfterFastbootd = false
                    return@launch
                }
                log("Menunggu perangkat masuk bootloader…", Level.INFO)
                tvStatus.text = "Menunggu bootloader untuk flash $partition…"
                disconnect()
            } catch (e: Exception) {
                logCmd("Flash gagal: ${e.message}", Level.ERROR)
                log("Flash gagal: ${e.message}", Level.ERROR)
            }
        }
    }

    private fun queueResumeFlash(partition: String, uri: Uri, opt: FastbootSession.FlashOptions) {
        pendingFlashPartition = partition
        pendingFlashUri = uri
        pendingFlashOpt = opt
        resumeFlashAfterFastbootd = true
    }

    private fun maybeResumePendingFlash() {
        if (!resumeFlashAfterFastbootd) return
        val part = pendingFlashPartition
        val uri = pendingFlashUri
        if (part == null || uri == null || session == null) return
        resumeFlashAfterFastbootd = false
        log("Terhubung kembali — melanjutkan flash $part…", Level.SUCCESS)
        runFlash(part, uri)
    }

    private fun confirmDanger(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Konfirmasi")
            .setMessage(message)
            .setPositiveButton("Lanjut") { _, _ -> onYes() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
