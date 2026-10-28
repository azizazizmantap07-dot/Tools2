package io.nekohasekai.sfa.bg

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceDataStore
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.provider.Settings as AndroidSettings

/**
 * Service-level watchdog: when every outbound of every `urltest` group keeps failing its URL
 * test for about [Settings.airplaneRecoveryFailureThreshold] seconds, toggle airplane mode via
 * root to reset the cellular radio.
 *
 * Lifecycle: [BoxService] calls [start] once the sing-box service is up and [stop] when it goes
 * down. The watcher owns its own local [CommandClient] (groups stream), so it keeps working
 * while the app UI is in the background or the screen is off. It is NOT driven by any UI.
 *
 * Notes on the failure heuristic:
 *  - Only `urltest` groups are watched. They are the only groups that run periodic tests; a
 *    `selector` with no URL-test history would look "failed" forever. (`urltest` groups are
 *    never `selectable` in libbox, so filtering on `selectable` would skip exactly the groups
 *    we need.)
 *  - An outbound counts as healthy when 0 < urlTestDelay < 10_000 (same convention as the UI).
 *    sing-box deletes the history of a failed outbound, so "failed" and "not tested yet" both
 *    read as 0; a startup grace period keeps the first seconds after start from firing.
 *
 * Cost model ("sweet spot" between battery/CPU/RAM and reaction time):
 *  - The groups stream is opened with a slow tick ([WATCH_INTERVAL_MS]) instead of the UI's 1 Hz.
 *    URL-test results only change when a test round finishes (every `interval` of the config), so
 *    re-reading them every second adds nothing but JNI/GC churn. Detection is therefore a
 *    cheap sweep; the expensive part (a fresh URL test) only runs once a failure is sustained.
 *  - The failure threshold is measured in TIME, not in snapshots, so its meaning does not depend
 *    on the tick rate: failures must last `(threshold - 1)` seconds (the old 1 Hz semantics).
 *  - Nothing is evaluated while the device is in Doze (the core is paused, no tests run).
 *  - The failure threshold and the cooldown are used exactly as configured, every time. There is
 *    no escalation: a recovery that did not help is followed by the same cooldown (8 -> 8 -> 8),
 *    so the watchdog keeps reacting at the same speed however long an outage lasts.
 */
object AirplaneModeRecovery {
    private const val TAG = "AirplaneRecovery"

    private const val URLTEST_GROUP_TYPE = "urltest"
    private const val FAILURE_DELAY_MS = 10_000

    /** No failures are counted this long after the watcher starts (history is empty at boot). */
    private const val STARTUP_GRACE_MS = 20_000L

    /** Minimum quiet period after a toggle so the modem can re-register and URL tests re-run. */
    private const val POST_RECOVERY_GRACE_MS = 8_000L

    /**
     * Tick of the groups stream. A failure is detected at most this late after a test round
     * records it, and the verification after a probe waits for at most one tick (see
     * [probeUrlTestGroups]). 5 s keeps the JNI/GC work at a fifth of the UI's 1 Hz.
     */
    private const val WATCH_INTERVAL_MS = 5_000L

    private const val AIRPLANE_OFF_DELAY_MS = 1_750L
    private const val STATE_CHANGE_TIMEOUT_MS = 2_000L
    private const val PROBE_TIMEOUT_MS = 12_000L
    private const val PROBE_SETTLE_MS = 1_500L

    /** A snapshot must be this much newer than the end of the tests to count as their result. */
    private const val SNAPSHOT_MARGIN_MS = 250L
    private const val SNAPSHOT_POLL_MS = 250L

    private const val RECONNECT_MIN_DELAY_MS = 3_000L
    private const val RECONNECT_MAX_DELAY_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guards serviceActive + session.
    private val access = Any()
    private var serviceActive = false
    private var session: Session? = null

    private val toggling = AtomicBoolean(false)

    // The recovery run that is in flight (probe / toggle), so it can be cancelled when the
    // service goes down. Only one runs at a time ([toggling]).
    @Volatile private var recoveryJob: Job? = null

    // Toggles in a row that were not followed by a single passing URL test. Only used for logging;
    // it does not influence the cooldown or the threshold.
    private val recoveryAttempts = AtomicInteger(0)

    // When the current uninterrupted run of "every outbound failed" snapshots began
    // (elapsedRealtime); 0 = not failing.
    @Volatile private var failingSince = 0L

    // When the last snapshot was processed (elapsedRealtime). The probe uses it to know that the
    // stream has caught up with the tests it just ran.
    @Volatile private var lastSnapshotAt = 0L

    // SystemClock.elapsedRealtime() deadlines; 0 = not set.
    @Volatile private var startupGraceUntil = 0L

    @Volatile private var cooldownUntil = 0L

    // When the current cooldown began (elapsedRealtime); 0 = none. Lets a changed cooldown setting
    // be applied to a cooldown that is already running.
    @Volatile private var cooldownStartedAt = 0L

    // Last time a snapshot showed at least one healthy outbound (elapsedRealtime).
    @Volatile private var lastHealthyAt = 0L

    @Volatile private var urlTestGroupTags: List<String> = emptyList()

    private val settingsListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            when (key) {
                SettingsKey.AIRPLANE_RECOVERY_ENABLED -> sync()
                SettingsKey.AIRPLANE_RECOVERY_FAILURE_THRESHOLD,
                SettingsKey.AIRPLANE_RECOVERY_COOLDOWN_SEC,
                -> applyConfig()
            }
        }
    }

    /** Called by [BoxService] once the sing-box service is running. Idempotent. */
    fun start() {
        synchronized(access) {
            if (serviceActive) return
            serviceActive = true
        }
        Settings.dataStore.registerChangeListener(settingsListener)
        sync()
    }

    /** Called by [BoxService] when the service stops. Idempotent. */
    fun stop() {
        val wasActive = synchronized(access) {
            val previous = serviceActive
            serviceActive = false
            previous
        }
        if (wasActive) {
            Settings.dataStore.unregisterChangeListener(settingsListener)
        }
        sync()
    }

    /** Opens or closes the groups stream depending on service state and the user's toggle. */
    private fun sync() {
        var toOpen: Session? = null
        var toClose: Session? = null
        synchronized(access) {
            val wanted = serviceActive && Settings.airplaneRecoveryEnabled
            if (wanted && session == null) {
                toOpen = Session().also { session = it }
            } else if (!wanted && session != null) {
                toClose = session
                session = null
            }
        }
        toClose?.let {
            it.close()
            resetState(withStartupGrace = false)
            // Service stopped (or feature switched off): an attempt that is still probing must
            // not go on to toggle airplane mode. If it is cancelled in the middle of a toggle,
            // executeAirplaneToggle() still switches airplane mode back off (NonCancellable).
            recoveryJob?.cancel()
            recoveryJob = null
        }
        toOpen?.let {
            resetState(withStartupGrace = true)
            it.connect()
        }
    }

    /**
     * Makes a changed failure threshold / cooldown take effect right away, without a service
     * restart. The threshold is read on every groups update, so only a cooldown that is already
     * running needs to be recomputed from its start with the new length. Triggered by the
     * settings listener, so the "Apply" button in the Service screen only has to save the values.
     */
    fun applyConfig() {
        val startedAt = cooldownStartedAt
        if (startedAt != 0L) {
            cooldownUntil = startedAt + cooldownMs()
        }
        Log.i(TAG, "config applied: threshold=${failureThreshold()} cooldown=${cooldownMs() / 1000}s")
    }

    private fun resetState(withStartupGrace: Boolean) {
        failingSince = 0L
        recoveryAttempts.set(0)
        urlTestGroupTags = emptyList()
        cooldownUntil = 0L
        cooldownStartedAt = 0L
        startupGraceUntil = if (withStartupGrace) SystemClock.elapsedRealtime() + STARTUP_GRACE_MS else 0L
    }

    private fun isWatching(): Boolean = synchronized(access) { serviceActive && session != null } &&
        Settings.airplaneRecoveryEnabled && BoxService.serviceWanted

    /** Doze: the core is paused (see BoxService), no URL test runs, so snapshots carry no news. */
    private fun isDeviceIdle(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
        try {
            Application.powerManager.isDeviceIdleMode
        } catch (e: Exception) {
            false
        }

    private fun onGroupsUpdated(groups: List<OutboundGroup>) {
        if (!Settings.airplaneRecoveryEnabled || !BoxService.serviceWanted || isDeviceIdle()) {
            failingSince = 0L
            return
        }

        var tested = 0
        var healthy = false
        val tags = ArrayList<String>(2)
        for (group in groups) {
            if (group.type != URLTEST_GROUP_TYPE) continue
            tags += group.tag
            // One healthy outbound settles the verdict. Skip the per-outbound JNI calls of the
            // rest (hundreds in a big group) and only keep the tags for later probes.
            if (healthy) continue
            val items = group.items ?: continue
            while (items.hasNext()) {
                tested++
                val ms = items.next().urlTestDelay
                if (ms > 0 && ms < FAILURE_DELAY_MS) {
                    healthy = true
                    break
                }
            }
        }
        urlTestGroupTags = tags

        val now = SystemClock.elapsedRealtime()
        lastSnapshotAt = now

        // No urltest outbounds (yet): nothing to judge.
        if (tested == 0) {
            failingSince = 0L
            return
        }

        if (healthy) {
            failingSince = 0L
            lastHealthyAt = now
            // A real test passed, so the test machinery is running: end the startup grace early.
            startupGraceUntil = 0L
            // The connection works again: the next failure is a new incident.
            if (recoveryAttempts.getAndSet(0) > 0) {
                Log.i(TAG, "url test passing again, attempt counter reset")
            }
            return
        }

        // A recovery attempt is running (probe / toggle): it owns the state, don't count on top.
        if (toggling.get()) return

        if (now < startupGraceUntil || now < cooldownUntil) {
            failingSince = 0L
            return
        }

        // Debounce on elapsed time, not on the number of snapshots: the stream ticks slowly, and
        // repeated snapshots of the same failed round are not independent evidence anyway.
        if (failingSince == 0L) {
            failingSince = now
            Log.i(TAG, "every url test is failing, recovery in ${debounceMs() / 1000}s if it persists")
        }
        val failingFor = now - failingSince
        if (failingFor < debounceMs()) return

        // The debounce is over. Total failure is expected while the user is in airplane mode.
        failingSince = 0L
        if (isAirplaneModeOn()) return

        trigger("urltest_fail_${failingFor / 1000}s")
    }

    private fun failureThreshold(): Int = Settings.airplaneRecoveryFailureThreshold.coerceIn(1, 20)

    /** How long every URL test must keep failing: `threshold - 1` seconds (the old 1 Hz meaning). */
    private fun debounceMs(): Long = (failureThreshold() - 1) * 1000L

    private fun trigger(reason: String) {
        if (!toggling.compareAndSet(false, true)) return
        recoveryJob = scope.launch {
            try {
                runRecovery(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "airplane recovery failed", e)
            } finally {
                toggling.set(false)
            }
        }
    }

    private suspend fun runRecovery(reason: String) {
        // Active only while the service is on: the service may have stopped between the trigger
        // and this coroutine actually running.
        if (!isWatching()) return
        if (!hasRoot()) {
            Log.w(TAG, "root not available, skip airplane toggle")
            // The (cached) shell is not root: wait one normal cooldown before checking again.
            enterCooldown()
            return
        }

        // Asking for root can take a while (manager prompt); the service may be off by now.
        if (!isWatching()) return

        // The failure state can be stale (e.g. history wiped by a short network switch while
        // the next scheduled test is still ~30s away). Run a fresh test before pulling the plug.
        val probeStartedAt = SystemClock.elapsedRealtime()
        probeUrlTestGroups()
        if (!isWatching()) return
        if (lastHealthyAt >= probeStartedAt) {
            // Only a real, successful test can produce a healthy snapshot, so this is a recovery.
            Log.i(TAG, "connection recovered before toggle, skip ($reason)")
            failingSince = 0L
            return
        }

        val attempt = recoveryAttempts.incrementAndGet()
        Log.w(TAG, "executing airplane toggle: $reason (attempt $attempt since last good test)")
        StartTrace.mark("AIRPLANE TOGGLE begin: $reason")
        try {
            executeAirplaneToggle()
        } finally {
            enterCooldown()
        }
    }

    /**
     * Cooldown length: the live setting (1–900 s, never below the post-recovery grace so the
     * modem can re-register). It is the same for every attempt, no matter how many recoveries in
     * a row did not help.
     */
    private fun cooldownMs(): Long =
        maxOf(Settings.airplaneRecoveryCooldownSec.coerceIn(1, 900) * 1000L, POST_RECOVERY_GRACE_MS)

    private fun enterCooldown() {
        failingSince = 0L
        val now = SystemClock.elapsedRealtime()
        cooldownStartedAt = now
        cooldownUntil = now + cooldownMs()
    }

    /**
     * Asks sing-box to re-run the URL tests of all watched groups, then waits until the groups
     * stream has delivered a snapshot taken after them. The stream ticks slowly now, so the old
     * fixed 1.5 s pause could end before the result arrived and make a recovered connection
     * look dead. Normally the wait is short; it is bounded by one tick plus [PROBE_SETTLE_MS].
     */
    private suspend fun probeUrlTestGroups() {
        val tags = urlTestGroupTags
        if (tags.isNotEmpty()) {
            // The native call blocks until the test finishes and cannot be interrupted, so it
            // runs detached: the timeout only stops us from waiting for it.
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                tags.map { tag ->
                    scope.async {
                        try {
                            Libbox.newStandaloneCommandClient().urlTest(tag)
                        } catch (e: Exception) {
                            Log.w(TAG, "urlTest($tag) failed: ${e.message}")
                        }
                    }
                }.awaitAll()
            }
        }
        val testsDoneAt = SystemClock.elapsedRealtime()
        val deadline = testsDoneAt + WATCH_INTERVAL_MS + PROBE_SETTLE_MS
        while (lastSnapshotAt < testsDoneAt + SNAPSHOT_MARGIN_MS && SystemClock.elapsedRealtime() < deadline) {
            delay(SNAPSHOT_POLL_MS)
        }
    }

    // region airplane mode control (root)

    private fun hasRoot(): Boolean = try {
        RootClient.shell().isRoot
    } catch (e: Exception) {
        Log.w(TAG, "root shell unavailable", e)
        false
    }

    private fun runRoot(vararg commands: String): Boolean = try {
        val result = RootClient.shell().newJob().add(*commands).exec()
        if (!result.isSuccess) {
            Log.w(TAG, "root command failed (code=${result.code}): ${commands.joinToString(" ; ")}")
        }
        result.isSuccess
    } catch (e: Exception) {
        Log.w(TAG, "root command error: ${commands.joinToString(" ; ")}", e)
        false
    }

    private fun isAirplaneModeOn(): Boolean = try {
        AndroidSettings.Global.getInt(
            Application.application.contentResolver,
            AndroidSettings.Global.AIRPLANE_MODE_ON,
            0,
        ) != 0
    } catch (e: Exception) {
        false
    }

    private suspend fun awaitAirplaneState(expected: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STATE_CHANGE_TIMEOUT_MS
        while (true) {
            if (isAirplaneModeOn() == expected) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(100)
        }
    }

    /**
     * Switches airplane mode and verifies the global setting actually changed.
     * Android 11+ goes through ConnectivityService (`cmd connectivity airplane-mode`), which is
     * the same path the system UI uses; older releases, or ROMs where that command is missing,
     * fall back to writing the setting and broadcasting the change.
     */
    private suspend fun setAirplaneMode(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runRoot("cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}")
            if (awaitAirplaneState(enabled)) return true
        }
        runRoot(
            "settings put global airplane_mode_on ${if (enabled) 1 else 0}",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled",
        )
        return awaitAirplaneState(enabled)
    }

    private suspend fun executeAirplaneToggle() {
        if (isAirplaneModeOn()) {
            Log.i(TAG, "airplane mode already on, skip")
            return
        }
        try {
            if (!setAirplaneMode(true)) {
                Log.e(TAG, "could not turn airplane mode on")
            }
            delay(AIRPLANE_OFF_DELAY_MS)
        } finally {
            // Whatever happened above (error, cancellation), never leave the phone offline.
            withContext(NonCancellable) { leaveAirplaneMode() }
        }
        Log.i(TAG, "airplane toggle finished")
        StartTrace.mark("AIRPLANE TOGGLE finished")
    }

    private suspend fun leaveAirplaneMode() {
        repeat(3) {
            if (!isAirplaneModeOn()) return
            if (setAirplaneMode(false)) return
            delay(500)
        }
        if (isAirplaneModeOn()) {
            Log.e(TAG, "FAILED to turn airplane mode off, manual action needed")
        }
    }

    // endregion

    /** One groups-stream connection, with automatic reconnect while it is still wanted. */
    private class Session : CommandClient.Handler {
        @Volatile private var closed = false
        private var reconnectJob: Job? = null

        // Delay before the next reconnect; doubles on repeated failures. Guarded by `this`.
        private var reconnectDelayMs = RECONNECT_MIN_DELAY_MS
        private val client = CommandClient(
            scope,
            CommandClient.ConnectionType.Groups,
            this,
            localOnly = true,
            statusIntervalMs = WATCH_INTERVAL_MS,
        )

        fun connect() {
            if (!closed) client.connect()
        }

        fun close() {
            closed = true
            synchronized(this) {
                reconnectJob?.cancel()
                reconnectJob = null
            }
            client.disconnect()
        }

        override fun onConnected() {
            synchronized(this) { reconnectDelayMs = RECONNECT_MIN_DELAY_MS }
            Log.i(TAG, "watching url-test groups (tick ${WATCH_INTERVAL_MS / 1000}s)")
        }

        override fun onDisconnected() {
            scheduleReconnect()
        }

        override fun onConnectionError(message: String) {
            Log.w(TAG, "groups stream error: $message")
            scheduleReconnect()
        }

        override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
            if (closed) return
            try {
                onGroupsUpdated(newGroups)
            } catch (e: Exception) {
                Log.w(TAG, "group update failed", e)
            }
        }

        private fun scheduleReconnect() {
            if (closed) return
            synchronized(this) {
                if (closed || reconnectJob?.isActive == true) return
                val delayMs = reconnectDelayMs
                reconnectDelayMs = minOf(delayMs * 2, RECONNECT_MAX_DELAY_MS)
                reconnectJob = scope.launch {
                    delay(delayMs)
                    if (!closed) client.connect()
                }
            }
        }
    }
}
