package com.flashing.tool.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * Common USB VID/PID pairs for devices in Fastboot (and occasionally ADB) mode
 * when connected as a USB peripheral to an Android host via OTG.
 *
 * Google's fastboot interface is most often 0x18D1 with various product IDs.
 * Some OEMs (including MediaTek-based phones) use their own VID while still
 * speaking the standard fastboot bulk protocol.
 *
 * Detection is intentionally broad: many soft-brick / custom-ROM devices expose
 * non-standard PIDs. We fall back to "has a vendor-specific bulk IN+OUT pair"
 * when VID/PID alone is inconclusive.
 */
object FastbootUsbIds {
    const val VENDOR_GOOGLE = 0x18D1
    const val VENDOR_MEDIATEK = 0x0E8D
    const val VENDOR_XIAOMI = 0x2717
    const val VENDOR_HUAWEI = 0x12D1
    const val VENDOR_SAMSUNG = 0x04E8
    const val VENDOR_OPPO = 0x22D9
    const val VENDOR_VIVO = 0x2D95
    const val VENDOR_REALME = 0x22D9
    const val VENDOR_ONEPLUS = 0x2A70
    const val VENDOR_ASUS = 0x0B05
    const val VENDOR_LENOVO = 0x17EF
    const val VENDOR_MOTOROLA = 0x22B8
    const val VENDOR_SONY = 0x0FCE
    const val VENDOR_LG = 0x1004
    const val VENDOR_ZTE = 0x19D2
    const val VENDOR_TCL = 0x1BBB

    /** Product IDs frequently seen for fastboot / bootloader / fastbootd. */
    val FASTBOOT_PRODUCT_IDS = setOf(
        // Google / AOSP
        0x4EE0, 0x4EE1, 0x4EE2, 0x4EE3, 0x4EE4, 0x4EE5, 0x4EE6, 0x4EE7,
        0xD00D,
        // Common OEM / MTK bootloader
        0x0D02, 0x2000, 0x2001,
        // Xiaomi / Redmi / Poco
        0xFF40, 0xFF48, 0xFF10, 0xFF18,
        // Samsung download / fastboot-ish
        0x685D, 0x6860,
        // Huawei / Honor
        0x1051, 0x1052, 0x107E,
        // Oppo / Realme / OnePlus
        0x2765, 0x2766, 0x2767, 0x2769,
        // Generic
        0x0001, 0x0002,
    )

    /** Classic BROM / Preloader PIDs that must NOT be treated as fastboot. */
    private val MTK_BROM_PRELOADER_PIDS = setOf(0x0003, 0x0005, 0x2000, 0x2001)

    fun isLikelyFastboot(vendorId: Int, productId: Int): Boolean {
        if (vendorId == VENDOR_GOOGLE) return true
        if (productId in FASTBOOT_PRODUCT_IDS) {
            // Avoid treating pure MTK BROM/Preloader as fastboot when PID collides
            if (vendorId == VENDOR_MEDIATEK && productId in MTK_BROM_PRELOADER_PIDS) {
                return false
            }
            return true
        }
        // MediaTek: do NOT treat every PID as fastboot — 0x201C etc. is usually ADB/MTP.
        // Only known bootloader PIDs above; interface probe is done in isLikelyFastbootDevice.
        // Known phone OEMs — accept as candidate (caller should still open & probe)
        if (vendorId in setOf(
                VENDOR_XIAOMI, VENDOR_HUAWEI, VENDOR_SAMSUNG, VENDOR_OPPO,
                VENDOR_VIVO, VENDOR_ONEPLUS, VENDOR_ASUS, VENDOR_LENOVO,
                VENDOR_MOTOROLA, VENDOR_SONY, VENDOR_LG, VENDOR_ZTE, VENDOR_TCL,
            )
        ) {
            return true
        }
        return false
    }

    /**
     * Stronger check for real fastboot/bootloader mode.
     *
     * Important: MediaTek phones on Android home often enumerate as 0x0E8D:0x201C
     * (or similar) with composite interfaces (MTP/CDC). Those must NOT be treated
     * as fastboot — otherwise auto-connect fights ADB and permission dialogs.
     */
    fun isLikelyFastbootDevice(device: UsbDevice): Boolean {
        if (PreloaderForcer.isPreloader(device)) return false
        // Android + USB debugging
        if (hasAdbInterface(device)) return false
        // Android composite (MTP/charging/file transfer) — not bootloader
        if (looksLikeAndroidComposite(device)) return false
        if (isLikelyFastboot(device.vendorId, device.productId)) return true
        // Bulk-only vendor device with few interfaces → likely real fastboot
        if (device.interfaceCount <= 2 && hasVendorBulkPair(device)) return true
        return false
    }

    /**
     * True when the device still looks like a running Android phone (composite
     * USB config), not a pure fastboot/bootloader endpoint.
     */
    fun looksLikeAndroidComposite(device: UsbDevice): Boolean {
        var androidish = false
        for (i in 0 until device.interfaceCount) {
            when (device.getInterface(i).interfaceClass) {
                // MTP/PTP, mass storage, CDC — typical of Android OS USB modes
                UsbConstants.USB_CLASS_STILL_IMAGE,
                UsbConstants.USB_CLASS_MASS_STORAGE,
                UsbConstants.USB_CLASS_COMM,
                UsbConstants.USB_CLASS_CDC_DATA,
                UsbConstants.USB_CLASS_HID,
                UsbConstants.USB_CLASS_AUDIO -> androidish = true
            }
        }
        // 3+ interfaces is almost always a composite Android config
        if (device.interfaceCount >= 3) return true
        return androidish
    }

    /** ADB USB class: class 0xFF, subclass 0x42, protocol 0x01 */
    fun hasAdbInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66 && intf.interfaceProtocol == 1) {
                return true
            }
            // Some OEM composites only set subclass/protocol
            if (intf.interfaceSubclass == 66 && intf.interfaceProtocol == 1) {
                return true
            }
        }
        return false
    }

    fun hasVendorBulkPair(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC &&
                intf.interfaceClass != 0
            ) {
                continue
            }
            var hasIn = false
            var hasOut = false
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
            }
            if (hasIn && hasOut) return true
        }
        return false
    }

    fun describe(vendorId: Int, productId: Int): String =
        "VID=0x%04X PID=0x%04X".format(vendorId, productId)
}
