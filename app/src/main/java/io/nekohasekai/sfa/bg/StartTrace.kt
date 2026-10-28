package io.nekohasekai.sfa.bg

import android.util.Log
import io.nekohasekai.sfa.Application
import java.io.File
import java.io.FileOutputStream

/**
 * Breadcrumbs for hunting freezes / reboots. Logcat is lost when the phone reboots, so every
 * mark is appended to `Android/data/<package>/files/start_trace.log` and fsync'ed at once: after
 * a reboot the LAST line is the last step that was reached before the system hung.
 */
object StartTrace {
    private const val TAG = "StartTrace"
    private const val MAX_BYTES = 64 * 1024L
    private val lock = Any()

    fun mark(step: String) {
        Log.i(TAG, step)
        try {
            synchronized(lock) {
                val app = Application.application
                val dir = app.getExternalFilesDir(null) ?: app.filesDir
                val file = File(dir, "start_trace.log")
                if (file.length() > MAX_BYTES) file.writeText("")
                FileOutputStream(file, true).use { out ->
                    out.write("${System.currentTimeMillis()} [${Thread.currentThread().name}] $step\n".toByteArray())
                    out.fd.sync()
                }
            }
        } catch (_: Throwable) {
        }
    }
}
