package com.flashing.tool.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * Force-fastboot for MediaTek soft-brick / bootloop devices whose Preloader is
 * still alive (technique from FastbootForge).
 *
 * While Preloader is in its short USB serial window (VID 0x0E8D, PID 0x0003 /
 * 0x2000 / 0x2001), sending the ASCII string "FASTBOOT" repeatedly asks the chip
 * to boot into the fastboot (bootloader) interface instead of continuing the
 * normal boot chain.
 *
 * Primary path: CDC-ACM @ 115200 8N1 (classic Preloader serial).
 * Fallback: raw bulk OUT on the first vendor bulk interface (some OEM builds).
 */
object PreloaderForcer {
    val PRELOADER_PIDS = setOf(0x0003, 0x0005, 0x2000, 0x2001)

    private val COMMAND = "FASTBOOT".toByteArray(Charsets.US_ASCII)

    fun isPreloader(device: UsbDevice): Boolean =
        device.vendorId == MtkUsbIds.VENDOR_ID_MEDIATEK &&
            device.productId in PRELOADER_PIDS

    /**
     * One burst: try CDC-ACM first, then bulk OUT. Returns true if any write path
     * succeeded.
     */
    fun sendFastboot(
        usb: UsbManager,
        device: UsbDevice,
        attempt: Int,
        verbose: Boolean,
        log: (String) -> Unit,
    ): Boolean {
        val conn = usb.openDevice(device)
        if (conn == null) {
            if (verbose) log("[force #$attempt] gagal openDevice (izin?)")
            return false
        }

        // --- Path 1: CDC-ACM serial ---
        try {
            val port = CdcAcmSerialDriver(device).ports.firstOrNull()
            if (port != null) {
                port.open(conn)
                try {
                    port.setParameters(
                        115200,
                        8,
                        UsbSerialPort.STOPBITS_1,
                        UsbSerialPort.PARITY_NONE,
                    )
                    port.write(COMMAND, 200)
                    val buf = ByteArray(64)
                    val n = try {
                        port.read(buf, 80)
                    } catch (_: Exception) {
                        0
                    }
                    if (verbose) {
                        val reply = if (n > 0) {
                            val ascii = String(buf, 0, n, Charsets.US_ASCII)
                                .trim { it <= ' ' || it == '\u0000' }
                            val hex = buf.take(n).joinToString(" ") { "%02X".format(it) }
                            " → reply=\"$ascii\" hex=[$hex]"
                        } else {
                            " → (tidak ada balasan serial)"
                        }
                        log("[burst $attempt] FASTBOOT via CDC-ACM$reply")
                    }
                    return true
                } finally {
                    try {
                        port.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            if (verbose) log("[force #$attempt] CDC-ACM: ${e.javaClass.simpleName}: ${e.message}")
        }

        // --- Path 2: raw bulk OUT (some Preloader builds expose bulk only) ---
        try {
            var claimedIface: android.hardware.usb.UsbInterface? = null
            var epOut: android.hardware.usb.UsbEndpoint? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        if (conn.claimInterface(iface, true)) {
                            claimedIface = iface
                            epOut = ep
                            break
                        }
                    }
                }
                if (epOut != null) break
            }
            if (epOut != null && claimedIface != null) {
                try {
                    val written = conn.bulkTransfer(epOut, COMMAND, COMMAND.size, 200)
                    if (written > 0) {
                        if (verbose) {
                            log("[burst $attempt] FASTBOOT via bulk OUT ($written bytes)")
                        }
                        return true
                    }
                    if (verbose) log("[burst $attempt] bulk OUT returned $written")
                } finally {
                    try {
                        conn.releaseInterface(claimedIface)
                    } catch (_: Exception) {
                    }
                }
            } else if (verbose) {
                log("[force #$attempt] tidak ada endpoint bulk OUT / CDC-ACM")
            }
        } catch (e: Exception) {
            if (verbose) log("[force #$attempt] bulk: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                conn.close()
            } catch (_: Exception) {
            }
        }
        return false
    }
}
