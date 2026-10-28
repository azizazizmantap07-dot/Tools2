package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
            }

            else -> return
        }
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (Settings.startedByUser) {
                    if (ServiceWatchdog.autoRestartEnabled) {
                        // Auto restart on: an unread crash report no longer blocks the start. The
                        // restart budget is what protects against a crash loop across boots.
                        if (!ServiceWatchdog.consumeRestartBudget()) {
                            ServiceWatchdog.giveUp("sing-box kept crashing, automatic start is paused. Open the app to start it again.")
                            return@launch
                        }
                    } else {
                        CrashReportManager.refresh()
                        if (CrashReportManager.unreadCount.value > 0) {
                            Settings.startedByUser = false
                            return@launch
                        }
                    }
                    withContext(Dispatchers.Main) {
                        BoxService.start(automatic = true).join()
                    }
                }
            } finally {
                Settings.dataStore.flush()
                pendingResult.finish()
            }
        }
    }
}
