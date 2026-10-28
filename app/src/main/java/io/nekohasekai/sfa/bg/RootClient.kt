package io.nekohasekai.sfa.bg

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Root as seen by this app: [Unknown] until it was checked, [Checking] while su is being asked. */
enum class RootState { Unknown, Checking, Granted, Denied }

object RootClient {
    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10),
        )
    }

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable

    /**
     * What the UI shows (dashboard card, Service settings): follows [rootAvailable] and adds the
     * transient [RootState.Checking]. It is refreshed automatically every time the app is opened
     * (see [refreshRootState]), so nobody has to press a "recheck" button.
     */
    private val _rootState = MutableStateFlow(RootState.Unknown)
    val rootState: StateFlow<RootState> = _rootState

    private val checksRunning = AtomicInteger(0)

    /**
     * The libsu main shell. Going through RootClient guarantees the default builder in `init`
     * is installed before the shell is first created (Shell.setDefaultBuilder throws once the
     * main shell exists). Blocks while the shell is being opened, so call it off the main thread.
     */
    fun shell(): Shell = Shell.getShell()

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected

    private var service: IRootService? = null
    private var connection: ServiceConnection? = null
    private val connectionMutex = Mutex()

    /**
     * Root state that is already known, without ever showing a root prompt: `true` / `false` once
     * a shell has been opened (or when no su exists), `null` while it has not been determined yet.
     * Cheap and non-blocking; use [checkRootAvailable] to actively (re)request root.
     */
    fun peekRootState(): Boolean? = Shell.getCachedShell()?.isRoot ?: Shell.isAppGrantedRoot()

    private fun publishRootResult(available: Boolean) {
        _rootAvailable.value = available
        _rootState.value = if (available) RootState.Granted else RootState.Denied
    }

    suspend fun checkRootAvailable(): Boolean {
        checksRunning.incrementAndGet()
        _rootState.value = RootState.Checking
        try {
            Shell.getCachedShell()?.close()
            return suspendCancellableCoroutine { continuation ->
                Shell.getShell { shell ->
                    val available = shell.isRoot
                    publishRootResult(available)
                    continuation.resume(available)
                }
            }
        } finally {
            // Cancelled before su answered: go back to what was known instead of staying "Checking".
            if (checksRunning.decrementAndGet() == 0 && _rootState.value == RootState.Checking) {
                _rootState.value = when (_rootAvailable.value) {
                    true -> RootState.Granted
                    false -> RootState.Denied
                    null -> RootState.Unknown
                }
            }
        }
    }

    /**
     * The check run every time the app is opened. Unlike [checkRootAvailable] it does not close a
     * live root shell (the airplane watcher and the restart watchdog may be using it right now):
     * a shell that is alive and root simply confirms [RootState.Granted]. Only when there is no
     * shell yet, or the cached one is not root, a fresh one is opened, which is what picks up a
     * grant given in the root manager since the last check (and shows its prompt the first time).
     * Does nothing while another check is already running. Call it off the main thread.
     */
    suspend fun refreshRootState(): Boolean {
        if (checksRunning.get() > 0) return _rootAvailable.value == true
        val cached = Shell.getCachedShell()
        if (cached != null && cached.isAlive && cached.isRoot) {
            publishRootResult(true)
            return true
        }
        return checkRootAvailable()
    }

    suspend fun bindService(): IRootService = connectionMutex.withLock {
        service?.let { return it }

        if (Shell.isAppGrantedRoot() == false) {
            throw IOException("permission denied")
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val svc = IRootService.Stub.asInterface(binder)
                        service = svc
                        connection = this
                        _serviceConnected.value = true
                        continuation.resume(svc)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                        connection = null
                        _serviceConnected.value = false
                    }
                }

                val intent = Intent(Application.application, RootServer::class.java)
                val task = RootService.bindOrTask(
                    intent,
                    ContextCompat.getMainExecutor(Application.application),
                    conn,
                )

                if (task == null) {
                    // Already connected, onServiceConnected will fire
                } else {
                    Shell.EXECUTOR.execute {
                        try {
                            val shell = Shell.getShell()
                            if (shell.isRoot) {
                                shell.execTask(task)
                            } else {
                                continuation.resumeWithException(
                                    IOException("permission denied"),
                                )
                            }
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    RootService.unbind(conn)
                }
            }
        }
    }

    fun unbindService() {
        connection?.let {
            RootService.unbind(it)
            connection = null
            service = null
            _serviceConnected.value = false
        }
    }

    suspend fun getInstalledPackages(flags: Int): List<PackageInfo> {
        val userId = android.os.Process.myUserHandle().hashCode()
        val svc = bindService()
        return try {
            val slice = svc.getInstalledPackages(flags, userId)

            @Suppress("UNCHECKED_CAST")
            val list = slice.list as List<PackageInfo>
            list
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun registerNeighborTableCallback(callback: INeighborTableCallback) {
        val svc = bindService()
        try {
            svc.registerNeighborTableCallback(callback)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun lookupSFTPServer(): String {
        val svc = bindService()
        try {
            return svc.lookupSFTPServer()
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun openShellSession(
        user: String,
        command: String?,
        env: Array<String>,
        term: String?,
        rows: Int,
        cols: Int,
    ): IRootShellSession {
        val svc = bindService()
        try {
            return svc.openShellSession(user, command, env, term, rows, cols)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun openBridge(
        bridgeName: String,
        mtu: Int,
        inet4Port: String,
        inet6Port: String,
        ruleIndex: Int,
        routeTable: Int,
    ): IBridgeSession {
        val svc = bindService()
        try {
            return svc.openBridge(bridgeName, mtu, inet4Port, inet6Port, ruleIndex, routeTable)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun unregisterNeighborTableCallback(callback: INeighborTableCallback) {
        try {
            service?.unregisterNeighborTableCallback(callback)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    private fun RemoteException.rethrowAsRuntime(): RuntimeException = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rethrowFromSystemServer()
    } else {
        RuntimeException(this)
    }
}
