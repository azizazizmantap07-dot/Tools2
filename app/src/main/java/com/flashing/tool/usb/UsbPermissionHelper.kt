package com.flashing.tool.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps the async USB_PERMISSION broadcast into a callback.
 *
 * Android 12+ requires FLAG_MUTABLE or FLAG_IMMUTABLE on every PendingIntent.
 * UsbManager must be able to fill EXTRA_PERMISSION_GRANTED → need MUTABLE.
 *
 * Android 14+ (targetSdk 34) forbids FLAG_MUTABLE with an *implicit* Intent
 * unless FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT is also set. Even with setPackage(),
 * some OEM builds still treat action-only Intents as implicit for this check.
 *
 * Fix used here:
 *  1. setPackage(packageName)  — make Intent as explicit as possible
 *  2. On API 34+: FLAG_MUTABLE | FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
 *  3. Use applicationContext so Activity destroy does not break the callback
 *
 * Ref: https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents
 */
class UsbPermissionHelper(context: Context) {

    private val appContext: Context = context.applicationContext
    private val usbManager: UsbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Requests permission for [device]. Calls [onResult] on the main thread
     * once the user responds (or immediately if already granted).
     * Never throws to the caller.
     */
    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        try {
            if (hasPermission(device)) {
                onResult(true)
                return
            }

            val action = "${appContext.packageName}.USB_PERMISSION"
            val requestCode = nextRequestCode.getAndIncrement() and 0xFFFF

            val intent = Intent(action).apply {
                setPackage(appContext.packageName)
            }

            val flags = permissionPendingIntentFlags()

            val permissionIntent = try {
                PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
            } catch (e: Exception) {
                // Last resort: IMMUTABLE (some OEMs still deliver the extra)
                try {
                    PendingIntent.getBroadcast(
                        appContext,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                } catch (_: Exception) {
                    onResult(false)
                    return
                }
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, broadcast: Intent) {
                    if (broadcast.action != action) return
                    try {
                        appContext.unregisterReceiver(this)
                    } catch (_: Exception) {
                    }
                    val granted =
                        broadcast.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    try {
                        onResult(granted)
                    } catch (_: Exception) {
                    }
                }
            }

            val filter = IntentFilter(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    appContext.registerReceiver(receiver, filter)
                }
            } catch (_: Exception) {
                onResult(false)
                return
            }

            try {
                usbManager.requestPermission(device, permissionIntent)
            } catch (_: Exception) {
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (_: Exception) {
                }
                onResult(false)
            }
        } catch (_: Exception) {
            try {
                onResult(false)
            } catch (_: Exception) {
            }
        }
    }

    fun listAllDevices(): List<UsbDevice> =
        usbManager.deviceList.values.toList()

    /**
     * Prefers a classic BROM/Preloader PID; falls back to any MediaTek vendor device.
     */
    fun findMtkDevice(): UsbDevice? {
        val all = usbManager.deviceList.values
        all.firstOrNull { dev ->
            MtkUsbIds.isMediaTek(dev.vendorId) && MtkUsbIds.isKnownMtkProduct(dev.productId)
        }?.let { return it }
        return all.firstOrNull { MtkUsbIds.isMediaTek(it.vendorId) }
    }

    companion object {
        private val nextRequestCode = AtomicInteger(1000)

        /**
         * Flags for UsbManager.requestPermission PendingIntent.
         * Must be MUTABLE so the system can attach EXTRA_PERMISSION_GRANTED.
         * On API 34+ also set ALLOW_UNSAFE_IMPLICIT_INTENT as a belt-and-suspenders
         * fix for OEM builds that still treat setPackage()-only intents as implicit.
         */
        fun permissionPendingIntentFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            if (Build.VERSION.SDK_INT >= 34) {
                // PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT = 1 shl 24
                flags = flags or (1 shl 24)
            }
            return flags
        }
    }
}
