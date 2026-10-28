package io.nekohasekai.sfa.vendor

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.RootClient
import kotlinx.coroutines.flow.StateFlow

object PackageQueryManager {

    val strategy: PackageQueryStrategy
        get() = if (BuildConfig.FLAVOR == "play") {
            PackageQueryStrategy.Root
        } else {
            PackageQueryStrategy.Direct
        }

    val requiresRoot: Boolean
        get() = strategy is PackageQueryStrategy.Root

    val rootAvailable: StateFlow<Boolean?> get() = RootClient.rootAvailable
    val rootServiceConnected: StateFlow<Boolean> get() = RootClient.serviceConnected

    suspend fun checkRootAvailable(): Boolean = RootClient.checkRootAvailable()

    suspend fun getInstalledPackages(flags: Int, retryFlags: Int): List<PackageInfo> = when (strategy) {
        is PackageQueryStrategy.Root -> RootClient.getInstalledPackages(flags)
        is PackageQueryStrategy.Direct -> getPackagesViaPackageManager(flags, retryFlags)
    }

    private fun getPackagesViaPackageManager(flags: Int, retryFlags: Int): List<PackageInfo> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Application.packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            Application.packageManager.getInstalledPackages(flags)
        }
    } catch (_: RuntimeException) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Application.packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(retryFlags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            Application.packageManager.getInstalledPackages(retryFlags)
        }
    }
}
