package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"

        /** Start attempts made again by the service itself when the core fails to come up. */
        private const val MAX_START_RETRIES = 5
        private const val RETRY_BASE_DELAY_MS = 2_000L
        private const val RETRY_MAX_DELAY_MS = 30_000L

        /** Libbox is set up asynchronously in Application.onCreate; wait for it after a cold start. */
        private const val LIBBOX_READY_TIMEOUT_MS = 15_000L

        /** Intent extra: the start was not asked for by the user (boot, watchdog alarm). */
        const val EXTRA_AUTOMATIC = "io.nekohasekai.sfa.AUTOMATIC_START"

        /**
         * True from the first onStartCommand until onDestroy of the service in this process.
         * [ServiceWatchdog] uses it to tell "service is running" from "service was killed".
         */
        @Volatile
        var serviceAlive = false
            private set

        /**
         * True while the service is switched ON and not on its way out: set when a start sequence
         * begins, cleared by every stop path (user stop, final failure, ignored restart, destroy).
         * Both watchdogs, the alarm heartbeat in [ServiceWatchdog] and [AirplaneModeRecovery], act
         * only while this is true, so neither can wake the device, restart the service or toggle
         * airplane mode once the service has been turned off.
         */
        @Volatile
        var serviceWanted = false
            private set

        @OptIn(DelicateCoroutinesApi::class)
        fun start(automatic: Boolean = false) = GlobalScope.launch(Dispatchers.Main.immediate) {
            Settings.dataStore.initialize()
            val intent = Intent(Application.application, Settings.serviceClass())
            if (automatic) intent.putExtra(EXTRA_AUTOMATIC, true)
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer

    // True while commandServer is a live, started instance (so teardown never closes it twice).
    @Volatile private var commandServerStarted = false
    private val idleModeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleModeUpdates = Channel<Boolean>(Channel.UNLIMITED)

    init {
        idleModeScope.launch {
            for (idle in idleModeUpdates) {
                if (idle) {
                    commandServer.pause()
                } else {
                    commandServer.wake()
                }
            }
        }
    }

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private fun startCommandServer() {
        Libbox.promoteOOMDraft()
        Libbox.discardPowerReportDraft()
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
        commandServerStarted = true
    }

    private var lastProfileName = ""

    // Start retry / auto restart state.
    @Volatile private var stopRequested = false
    @Volatile private var retryWaiting = false
    @Volatile private var automaticStart = false
    @Volatile private var hadSuccessfulStart = false

    // True while the airplane watcher was paused because the core was closed by serviceStop().
    @Volatile private var airplaneWatcherPaused = false
    private var startAttempt = 0
    private var retryJob: Job? = null

    private suspend fun startService() {
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = Settings.selectedProfile
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val content = File(profile.typed.path).readText()
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            StartTrace.mark("DefaultNetworkMonitor.start")
            DefaultNetworkMonitor.start()
            StartTrace.mark("DefaultNetworkMonitor started, startOrReloadService")

            try {
                commandServer.startOrReloadService(
                    content,
                    OverrideOptions().apply {
                        autoRedirect = Settings.autoRedirect
                        if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                            val appList = Settings.getEffectivePerAppProxyList()
                            if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                                includePackage =
                                    PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                            } else {
                                excludePackage =
                                    PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            StartTrace.mark("core started")
            status.postValue(Status.Started)
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
            // Service-level watchdog: independent of the UI, keeps running in background.
            // It is an add-on, so a failure here must never take the VPN service down.
            // Only while the service is really on: if the user pressed stop while the core was
            // still starting, the watcher must not even begin (stopService() runs right below).
            if (!stopRequested) {
                runCatching { AirplaneModeRecovery.start() }
                    .onFailure { Log.e(TAG, "airplane recovery start failed", it) }
            }

            // A good start resets the retry budget and remembers that this config works.
            startAttempt = 0
            hadSuccessfulStart = true
            if (stopRequested) {
                // The user pressed stop while the core was still starting.
                withContext(Dispatchers.Main) { stopService() }
            }
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    override fun serviceStop() {
        StartTrace.mark("serviceStop (core closing)")
        // The core is being closed: there is nothing to watch until it runs again.
        airplaneWatcherPaused = true
        AirplaneModeRecovery.stop()
        notification.close()
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        closeService()
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val content = File(profile.typed.path).readText()
        if (content.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        lastProfileName = profile.name
        try {
            commandServer.startOrReloadService(
                content,
                OverrideOptions().apply {
                    autoRedirect = Settings.autoRedirect
                    if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                        val appList = Settings.getEffectivePerAppProxyList()
                        if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                            includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                        } else {
                            excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                        }
                    }
                },
            )
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }

        // The core runs again after a serviceStop(): resume the airplane watcher (fresh grace).
        if (airplaneWatcherPaused && !stopRequested) {
            airplaneWatcherPaused = false
            runCatching { AirplaneModeRecovery.start() }
                .onFailure { Log.e(TAG, "airplane recovery resume failed", it) }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (::commandServer.isInitialized) {
            idleModeUpdates.trySend(Application.powerManager.isDeviceIdleMode)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        // An explicit stop (button, tile, VPN revoked): the service is OFF from this moment, so
        // neither watchdog may act and nothing may bring the service back. The flag goes first
        // so that a heartbeat or an airplane evaluation already in flight sees it.
        serviceWanted = false
        stopRequested = true
        AirplaneModeRecovery.stop()
        ServiceWatchdog.cancel()
        if (retryWaiting) {
            cancelRetryAndStop()
            return
        }

        if (status.value != Status.Started) return
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            DefaultNetworkMonitor.stop()
            closeService()
            commandServer.apply {
                close()
//                Seq.destroyRef(refnum)
            }
            Libbox.discardPowerReportDraft()
            PowerReportManager.refresh()
            Settings.startedByUser = false
            Settings.dataStore.flush()
            withContext(Dispatchers.Main) {
                status.value = Status.Stopped
                service.stopSelf()
            }
        }
    }

    private fun closeService() {
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    /** Shuts the core down (not the Android service). Safe to call more than once. */
    private suspend fun teardownCore() {
        AirplaneModeRecovery.stop()
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop()
        if (commandServerStarted) {
            commandServerStarted = false
            runCatching { closeService() }
            runCatching { commandServer.close() }
        }
    }

    private fun isRetryable(type: Alert): Boolean = when (type) {
        Alert.StartCommandServer, Alert.StartService -> true
        // A config that never ran and was started by hand is most likely just broken: tell the
        // user right away instead of making them wait for the retries.
        Alert.CreateService -> automaticStart || hadSuccessfulStart
        else -> false
    }

    /**
     * Instead of dying on a start error, keep the (foreground) service alive, tear the core down and
     * try again after a backoff. Returns false when the error must be reported and the service stopped.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun scheduleRetry(type: Alert, message: String?): Boolean {
        if (stopRequested || !ServiceWatchdog.autoRestartEnabled || !isRetryable(type)) return false
        if (startAttempt >= MAX_START_RETRIES) return false

        val attempt = ++startAttempt
        val delayMs = minOf(RETRY_BASE_DELAY_MS shl (attempt - 1), RETRY_MAX_DELAY_MS)
        Log.w(TAG, "start failed (${type.name}: $message), retry $attempt/$MAX_START_RETRIES in ${delayMs}ms")

        teardownCore()
        retryWaiting = true
        withContext(Dispatchers.Main) {
            status.value = Status.Starting
            // Never leave the foreground state here: Android 12+ may refuse entering it again.
            runCatching { notification.show(lastProfileName, R.string.status_reconnecting) }
        }
        retryJob = GlobalScope.launch(Dispatchers.IO) {
            delay(delayMs)
            retryWaiting = false
            if (stopRequested) return@launch
            runStartSequence(restoredBySystem = false)
        }
        return true
    }

    /** The user stopped the service while it was waiting between two start attempts. */
    @OptIn(DelicateCoroutinesApi::class)
    private fun cancelRetryAndStop() {
        retryJob?.cancel()
        retryJob = null
        retryWaiting = false
        GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = false
            Settings.dataStore.flush()
            withContext(Dispatchers.Main) {
                if (receiverRegistered) {
                    service.unregisterReceiver(receiver)
                    receiverRegistered = false
                }
                notification.close()
                status.value = Status.Stopped
                service.stopSelf()
            }
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        StartTrace.mark("stopAndAlert ${type.name}: $message")
        if (scheduleRetry(type, message)) return

        serviceWanted = false
        ServiceWatchdog.cancel()
        Settings.startedByUser = false
        Settings.dataStore.flush()
        teardownCore()
        if (ServiceWatchdog.autoRestartEnabled && startAttempt >= MAX_START_RETRIES) {
            // Nobody may be looking at the app: say it out loud.
            ServiceWatchdog.notifyStopped("Could not start sing-box after $MAX_START_RETRIES retries: ${message ?: type.name}")
        }
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    /** The service was recreated by Android after being killed. Returns true to go on starting. */
    private suspend fun prepareSystemRestore(): Boolean {
        if (!ServiceWatchdog.autoRestartEnabled || !Settings.startedByUser) {
            Log.i(TAG, "ignoring system restart: auto restart is off or the user had stopped the service")
            // Nothing is to be supervised any more: don't leave a heartbeat behind.
            ServiceWatchdog.cancel()
            endWithoutRestart()
            return false
        }
        if (!ServiceWatchdog.consumeRestartBudget()) {
            ServiceWatchdog.giveUp("sing-box was restarted too many times in a short period, automatic restart is paused.")
            endWithoutRestart()
            return false
        }
        // A restarted service has to re-enter the foreground by itself.
        val foreground = try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_reconnecting)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "system restart cannot enter the foreground, leaving it to the watchdog", e)
            false
        }
        if (!foreground) {
            // Keep startedByUser: the alarm heartbeat will start us again (root fallback if needed),
            // so use the short cadence instead of waiting a full relaxed beat.
            ServiceWatchdog.arm(ServiceWatchdog.RESTART_RECHECK_MS)
            endWithoutRestart()
            return false
        }
        return true
    }

    private suspend fun endWithoutRestart() {
        serviceWanted = false
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    private suspend fun runStartSequence(restoredBySystem: Boolean) {
        StartTrace.mark("runStartSequence begin restoredBySystem=$restoredBySystem")
        Settings.dataStore.initialize()
        if (restoredBySystem && !prepareSystemRestore()) return
        Settings.startedByUser = true
        // The service is switched on from here and both watchdogs may act. If the user already
        // pressed stop meanwhile (stopRequested), arm nothing: startService() finishes that stop.
        if (!stopRequested) {
            serviceWanted = true
            ServiceWatchdog.arm()
        }
        // After a cold start (system restart, boot) libbox may still be initializing.
        withTimeoutOrNull(LIBBOX_READY_TIMEOUT_MS) { Application.libboxReady.await() }
        StartTrace.mark("libbox ready, starting command server")
        try {
            startCommandServer()
        } catch (e: Exception) {
            stopAndAlert(Alert.StartCommandServer, e.message)
            return
        }
        StartTrace.mark("command server started")
        startService()
    }

    @OptIn(DelicateCoroutinesApi::class)
    internal fun onStartCommand(intent: Intent?): Int {
        serviceAlive = true
        StartTrace.mark("onStartCommand intent=${if (intent == null) "null(system restart)" else "present"} status=${status.value}")
        // The value returned by the LAST onStartCommand decides whether Android restarts a killed
        // service, so it must also be returned from the early exit below. A null intent means the
        // system recreated the service after killing it.
        val restartPolicy =
            if (ServiceWatchdog.autoRestartEnabled) Service.START_STICKY else Service.START_NOT_STICKY
        if (status.value != Status.Stopped) return restartPolicy
        status.value = Status.Starting

        val restoredBySystem = intent == null
        automaticStart = restoredBySystem || intent?.getBooleanExtra(EXTRA_AUTOMATIC, false) == true
        stopRequested = false
        hadSuccessfulStart = false
        startAttempt = 0

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            runStartSequence(restoredBySystem)
        }
        return restartPolicy
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        // The alarm heartbeat is deliberately left alone here: if this was not a user stop it has to
        // survive the service. (stopService / the final stopAndAlert already cancelled it.)
        serviceAlive = false
        serviceWanted = false
        retryJob?.cancel()
        AirplaneModeRecovery.stop()
        idleModeUpdates.cancel()
        idleModeScope.cancel()
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val channel = "notification-${notification.typeID}"
        val builder =
            NotificationCompat.Builder(service, channel).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.identifier, notification.typeID, builder.build())
        }
    }

    internal fun cancelNotification(identifier: String, typeID: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            Application.notification.cancel(identifier, typeID)
        }
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
