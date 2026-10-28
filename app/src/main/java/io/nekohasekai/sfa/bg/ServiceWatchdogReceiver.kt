package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the [ServiceWatchdog] heartbeat. Registered in the manifest (not exported), so the
 * system can deliver it even when the service and the whole process are dead.
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceWatchdog"
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ServiceWatchdog.ACTION_CHECK) return
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(ServiceWatchdog.CHECK_TIMEOUT_MS) {
                    ServiceWatchdog.check()
                }
            } catch (e: Exception) {
                Log.w(TAG, "watchdog check failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
