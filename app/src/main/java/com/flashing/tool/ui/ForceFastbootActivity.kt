package com.flashing.tool.ui

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.flashing.tool.R
import com.flashing.tool.usb.FastbootUsbIds
import com.flashing.tool.usb.PreloaderForcer
import com.flashing.tool.usb.UsbPermissionHelper
import com.flashing.tool.usb.UsbProcessCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Force Fastboot page with detailed progress log (aligned with FastbootForge):
 * burst counters, USB attach/detach, permission, serial write results, and
 * automatic hand-off to [FastbootActivity] when fastboot appears.
 */
class ForceFastbootActivity : AppCompatActivity() {

    private enum class Level { INFO, SUCCESS, WARNING, ERROR, RAW }

    private lateinit var tvStatus: TextView
    private lateinit var tvBurst: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton

    private lateinit var usbManager: UsbManager
    private lateinit var usbPermissionHelper: UsbPermissionHelper

    private val scope = CoroutineScope(Dispatchers.Main)
    private var forceJob: Job? = null
    @Volatile private var running = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logPlain = StringBuilder()
    /** Structured entries for filtering warning/error when copying. */
    private data class LogEntry(val level: Level, val line: String)
    private val logEntries = mutableListOf<LogEntry>()
    private var lastDeviceFingerprint = ""
    private var lastPhase = ""

    /** Avoid spamming the system USB permission dialog every loop iteration. */
    private var permissionRequestedFor = ""
    @Volatile private var permissionDenied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_force_fastboot)

        tvStatus = findViewById(R.id.tvForceStatus)
        tvBurst = findViewById(R.id.tvForceBurst)
        tvLog = findViewById(R.id.tvForceLog)
        scrollLog = findViewById(R.id.scrollForceLog)
        progress = findViewById(R.id.progressForce)
        btnStart = findViewById(R.id.btnForceStart)
        btnStop = findViewById(R.id.btnForceStop)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        usbPermissionHelper = UsbPermissionHelper(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            stopForce(userInitiated = false)
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.slide_down_out)
        }
        btnStart.setOnClickListener { startForce() }
        btnStop.setOnClickListener { stopForce(userInitiated = true) }

        findViewById<MaterialButton?>(R.id.btnForceCopyLog)?.setOnClickListener { copyLog() }
        findViewById<MaterialButton?>(R.id.btnForceCopyErrorLog)?.setOnClickListener { copyErrorLog() }
        findViewById<MaterialButton?>(R.id.btnForceClearLog)?.setOnClickListener { clearLog() }

        // Log starts empty — operational lines appear after Mulai / USB events
        dumpUsbSnapshot("Keadaan USB awal")
    }

    override fun onDestroy() {
        stopForce(userInitiated = false)
        super.onDestroy()
    }

    private fun startForce() {
        if (isFinishing || isDestroyed) return
        if (running) {
            appendLog("Force sudah berjalan. Tekan Stop untuk menghentikan.", Level.WARNING)
            return
        }
        if (!UsbProcessCoordinator.tryAcquire(UsbProcessCoordinator.Owner.FORCE_FASTBOOT)) {
            appendLog(
                "USB sedang dipakai ${UsbProcessCoordinator.describeOwner()}. Hentikan prosesnya dulu.",
                Level.ERROR
            )
            return
        }
        running = true
        lastDeviceFingerprint = ""
        lastPhase = ""
        permissionRequestedFor = ""
        permissionDenied = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        setStatus(getString(R.string.force_status_waiting), R.color.accent_pink)
        tvBurst.text = "Burst #0"
        appendLog("══════════════════════════════════════", Level.INFO)
        appendLog("Force Fastboot dimulai", Level.SUCCESS)
        appendLog("Target: Preloader MediaTek (VID 0x0E8D, PID 0x0003 / 0x0005 / 0x2000 / 0x2001)", Level.INFO)
        appendLog("Aksi: kirim ASCII \"FASTBOOT\" berulang (CDC-ACM lalu bulk OUT)", Level.INFO)
        appendLog("Colok perangkat sekarang (tahan Volume jika perlu).", Level.INFO)
        appendLog("Izin USB hanya diminta sekali — setelah Izinkan, burst lanjut otomatis (tanpa cabut-colok).", Level.INFO)

        forceJob = scope.launch(Dispatchers.IO) {
            var burst = 0
            var successWrites = 0
            var failWrites = 0
            var lastHeartbeat = 0L
            var idleLoops = 0

            try {
                while (running && isActive) {
                    if (isFinishing || isDestroyed) break

                    val devices = try {
                        usbManager.deviceList.values.toList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val fingerprint = devices.joinToString("|") {
                        "%04x:%04x:%s".format(it.vendorId, it.productId, it.deviceName)
                    }

                    // USB topology change
                    if (fingerprint != lastDeviceFingerprint) {
                        lastDeviceFingerprint = fingerprint
                        // New device set → allow permission request again for new node
                        permissionDenied = false
                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            if (devices.isEmpty()) {
                                appendLog("USB: tidak ada perangkat di host", Level.WARNING)
                            } else {
                                appendLog("USB berubah — ${devices.size} perangkat:", Level.INFO)
                                devices.forEach { d ->
                                    val kind = when {
                                        PreloaderForcer.isPreloader(d) -> "PRELOADER"
                                        FastbootUsbIds.isLikelyFastbootDevice(d) -> "FASTBOOT?"
                                        else -> "other"
                                    }
                                    appendLog(
                                        "  • VID=0x%04X PID=0x%04X  %s  [%s]".format(
                                            d.vendorId, d.productId, d.deviceName, kind
                                        ),
                                        Level.RAW
                                    )
                                }
                            }
                        }
                    }

                    // Already in fastboot? → hand off to terminal
                    val fb = devices.firstOrNull {
                        FastbootUsbIds.isLikelyFastbootDevice(it)
                    }
                    if (fb != null) {
                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            running = false
                            progress.visibility = View.GONE
                            setStatus(getString(R.string.force_status_success), R.color.status_ok)
                            tvBurst.text = "Burst #$burst · OK=$successWrites · gagal=$failWrites"
                            appendLog(
                                "Fastboot terdeteksi: VID=0x%04X PID=0x%04X %s".format(
                                    fb.vendorId, fb.productId, fb.deviceName
                                ),
                                Level.SUCCESS
                            )
                            appendLog(
                                "Ringkasan: $burst burst, $successWrites tulis OK, $failWrites gagal",
                                Level.SUCCESS
                            )
                            appendLog("Membuka Fastboot Terminal (auto-connect)…", Level.INFO)
                            UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FORCE_FASTBOOT)
                            try {
                                val intent = Intent(this@ForceFastbootActivity, FastbootActivity::class.java)
                                intent.putExtra(FastbootActivity.EXTRA_AUTO_CONNECT, true)
                                val opts = ActivityOptions.makeCustomAnimation(
                                    this@ForceFastbootActivity, R.anim.slide_up_in, R.anim.fade_out
                                )
                                startActivity(intent, opts.toBundle())
                                finish()
                            } catch (e: Exception) {
                                appendLog("Gagal buka Fastboot Terminal: ${e.message}", Level.ERROR)
                            }
                        }
                        return@launch
                    }

                    val pre = devices.firstOrNull { PreloaderForcer.isPreloader(it) }
                    if (pre != null) {
                        idleLoops = 0
                        val preKey = "%04x:%04x:%s".format(pre.vendorId, pre.productId, pre.deviceName)

                        if (!usbManager.hasPermission(pre)) {
                            phase("permission")
                            if (permissionDenied) {
                                // User already denied for this plug cycle — wait for re-plug
                                delay(150)
                                continue
                            }
                            if (permissionRequestedFor != preKey) {
                                permissionRequestedFor = preKey
                                withContext(Dispatchers.Main) {
                                    if (isFinishing || isDestroyed) return@withContext
                                    appendLog(
                                        "Preloader terdeteksi, izin USB belum ada — meminta izin (sekali)… " +
                                            "Setelah Izinkan, burst lanjut otomatis tanpa cabut-colok.",
                                        Level.WARNING
                                    )
                                    setStatus("Menunggu izin USB…", R.color.warn_amber)
                                    usbPermissionHelper.requestPermission(pre) { ok ->
                                        if (isFinishing || isDestroyed) return@requestPermission
                                        if (ok) {
                                            permissionDenied = false
                                            // Keep permissionRequestedFor so we don't spam the dialog.
                                            // Loop continues polling; next iteration with hasPermission
                                            // will start bursting immediately if device still present.
                                            appendLog(
                                                "Izin Preloader diberikan — sniper/burst lanjut otomatis " +
                                                    "(tidak perlu cabut-colok jika perangkat masih di bus).",
                                                Level.SUCCESS
                                            )
                                        } else {
                                            permissionDenied = true
                                            permissionRequestedFor = ""
                                            appendLog(
                                                "Izin Preloader ditolak. Izinkan di dialog sistem, lalu Start lagi.",
                                                Level.ERROR
                                            )
                                        }
                                    }
                                }
                            }
                            // Short poll while waiting for user to grant — resume bursts ASAP
                            delay(80)
                            continue
                        }

                        phase("forcing")
                        burst++
                        val verbose = burst <= 8 || (burst <= 100 && burst % 10 == 0) || burst % 25 == 0

                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            setStatus(getString(R.string.force_status_forcing), R.color.accent_pink)
                            tvBurst.text = "Burst #$burst · OK=$successWrites · gagal=$failWrites"
                        }

                        // Re-resolve device each burst — Preloader can re-enumerate
                        val livePre = try {
                            usbManager.deviceList.values.firstOrNull { PreloaderForcer.isPreloader(it) }
                        } catch (_: Exception) {
                            null
                        }
                        if (livePre == null || !usbManager.hasPermission(livePre)) {
                            failWrites++
                            delay(50)
                            continue
                        }

                        val ok = try {
                            PreloaderForcer.sendFastboot(
                                usbManager, livePre, burst, verbose = verbose
                            ) { msg ->
                                if (verbose) {
                                    scope.launch(Dispatchers.Main) {
                                        if (!isFinishing && !isDestroyed) appendLog(msg, Level.RAW)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (verbose) {
                                withContext(Dispatchers.Main) {
                                    if (!isFinishing && !isDestroyed) {
                                        appendLog(
                                            "[burst $burst] exception: ${e.message}",
                                            Level.WARNING
                                        )
                                    }
                                }
                            }
                            false
                        }
                        if (ok) successWrites++ else failWrites++

                        if (verbose) {
                            withContext(Dispatchers.Main) {
                                if (isFinishing || isDestroyed) return@withContext
                                appendLog(
                                    if (ok) "[burst $burst] tulis FASTBOOT OK (total OK=$successWrites)"
                                    else "[burst $burst] tulis GAGAL (total gagal=$failWrites)",
                                    if (ok) Level.INFO else Level.WARNING
                                )
                            }
                        } else if (burst % 50 == 0) {
                            withContext(Dispatchers.Main) {
                                if (isFinishing || isDestroyed) return@withContext
                                appendLog(
                                    "Masih memaksa… burst=$burst OK=$successWrites gagal=$failWrites",
                                    Level.INFO
                                )
                            }
                        }
                    } else {
                        idleLoops++
                        phase("waiting")
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                setStatus(getString(R.string.force_status_waiting), R.color.accent_pink)
                            }
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastHeartbeat > 3000) {
                            lastHeartbeat = now
                            withContext(Dispatchers.Main) {
                                if (!isFinishing && !isDestroyed) {
                                    appendLog(
                                        "Menunggu Preloader… (VID 0x0E8D PID BROM/Preloader belum di bus)",
                                        Level.INFO
                                    )
                                }
                            }
                        }
                    }
                    delay(80)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        appendLog("Force loop error: ${e.message}", Level.ERROR)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        if (progress.visibility == View.VISIBLE) {
                            progress.visibility = View.GONE
                        }
                    }
                }
                // Only release if we still own it (success path releases before finish)
                if (UsbProcessCoordinator.owner == UsbProcessCoordinator.Owner.FORCE_FASTBOOT) {
                    UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FORCE_FASTBOOT)
                }
                running = false
            }
        }
    }

    private fun stopForce(userInitiated: Boolean) {
        running = false
        forceJob?.cancel()
        forceJob = null
        if (UsbProcessCoordinator.owner == UsbProcessCoordinator.Owner.FORCE_FASTBOOT) {
            UsbProcessCoordinator.release(UsbProcessCoordinator.Owner.FORCE_FASTBOOT)
        }
        if (isFinishing || isDestroyed) return
        try {
            progress.visibility = View.GONE
            setStatus(getString(R.string.force_status_idle), R.color.text_secondary)
            if (userInitiated) {
                appendLog("Force Fastboot dihentikan. USB dilepas.", Level.WARNING)
            }
        } catch (_: Exception) {
        }
    }

    private fun phase(name: String) {
        if (name != lastPhase) {
            lastPhase = name
            scope.launch(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@launch
                when (name) {
                    "waiting" -> appendLog("Fase → menunggu Preloader", Level.INFO)
                    "permission" -> appendLog("Fase → izin USB", Level.WARNING)
                    "forcing" -> appendLog("Fase → mengirim FASTBOOT ke Preloader", Level.SUCCESS)
                }
            }
        }
    }

    private fun dumpUsbSnapshot(title: String) {
        if (isFinishing || isDestroyed) return
        val devices = try {
            usbManager.deviceList.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
        appendLog("$title: ${devices.size} perangkat", Level.INFO)
        if (devices.isEmpty()) {
            appendLog("  (bus kosong)", Level.RAW)
            return
        }
        devices.forEach { d ->
            val kind = when {
                PreloaderForcer.isPreloader(d) -> "PRELOADER"
                FastbootUsbIds.isLikelyFastbootDevice(d) -> "FASTBOOT?"
                else -> "other"
            }
            appendLog(
                "  • VID=0x%04X PID=0x%04X  %s  [%s]".format(
                    d.vendorId, d.productId, d.deviceName, kind
                ),
                Level.RAW
            )
        }
    }

    private fun setStatus(text: String, colorRes: Int) {
        if (isFinishing || isDestroyed) return
        try {
            tvStatus.text = text
            tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        } catch (_: Exception) {
        }
    }

    private fun appendLog(message: String, level: Level = Level.INFO) {
        if (isFinishing || isDestroyed) return
        try {
            val ts = timeFmt.format(Date())
            val tag = when (level) {
                Level.INFO -> "I"
                Level.SUCCESS -> "S"
                Level.WARNING -> "W"
                Level.ERROR -> "E"
                Level.RAW -> "R"
            }
            val line = "[$ts] [$tag] $message"
            logPlain.append(line).append('\n')
            logEntries.add(LogEntry(level, line))

            val colorRes = when (level) {
                Level.INFO -> R.color.log_info
                Level.SUCCESS -> R.color.log_success
                Level.WARNING -> R.color.log_warning
                Level.ERROR -> R.color.log_error
                Level.RAW -> R.color.log_raw
            }
            val color = ContextCompat.getColor(this, colorRes)
            val sb = SpannableStringBuilder(line + "\n")
            sb.setSpan(ForegroundColorSpan(color), 0, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvLog.append(sb)
            scrollLog.post {
                if (!isFinishing && !isDestroyed) {
                    scrollLog.fullScroll(View.FOCUS_DOWN)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun copyLog() {
        if (isFinishing || isDestroyed) return
        val text = logPlain.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.toast_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ForceFastboot Log", text))
        Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyErrorLog() {
        if (isFinishing || isDestroyed) return
        val errors = logEntries.filter {
            it.level == Level.WARNING || it.level == Level.ERROR
        }
        if (errors.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_error_log, Toast.LENGTH_SHORT).show()
            return
        }
        val text = errors.joinToString("\n") { it.line }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ForceFastboot W+E Log", text))
        Toast.makeText(this, R.string.toast_error_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        if (isFinishing || isDestroyed) return
        logPlain.clear()
        logEntries.clear()
        tvLog.text = ""
        appendLog("Log dihapus.", Level.INFO)
    }
}
