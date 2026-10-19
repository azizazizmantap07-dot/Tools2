package com.flashing.tool.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Raw USB bulk-transfer transport to a MediaTek device in BROM / Preloader / DA mode.
 *
 * This is the Android equivalent of what libusb does on desktop tools: claim the
 * device's bulk interface and move bytes in and out over bulkTransfer(). The MTK
 * USB protocol itself (handshake bytes, command framing) lives on top of this in
 * [com.flashing.tool.protocol], not here — this class only knows about moving
 * raw bytes over USB.
 *
 * IMPORTANT — things you must verify on your actual device before trusting this:
 *  - The interface/endpoint indices below (interface 0, first bulk IN/OUT pair)
 *    are the common case for MTK BROM devices, but are not guaranteed for every
 *    chipset. If claimInterface() or the endpoint lookup fails, dump
 *    device.interfaceCount / getInterface(i).endpointCount and inspect manually —
 *    log it and adjust.
 *  - Some devices expose more than one interface; you may need to iterate and
 *    pick the one with class 0xFF (vendor-specific) rather than assuming index 0.
 */
class UsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) : AutoCloseable {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    /**
     * Excess bytes from bulkTransfer when the host stack returns a full packet
     * larger than the caller requested. Without this, BROM multi-byte reads
     * desync and System.arraycopy throws (src.length > remaining).
     */
    private var leftover: ByteArray = ByteArray(0)

    /** Re-queue bytes so the next read/readExact returns them first. */
    fun pushBack(data: ByteArray) {
        if (data.isEmpty()) return
        leftover = data + leftover
    }

    val isOpen: Boolean
        get() = connection != null

    /**
     * Opens the device and claims its bulk transfer interface.
     * Requires that USB permission has already been granted (see UsbPermissionHelper).
     */
    @Throws(IOException::class)
    fun open() {
        val conn = usbManager.openDevice(device)
            ?: throw IOException(
                "openDevice() returned null — permission not granted or device detached " +
                    "(VID=0x%04X PID=0x%04X)".format(device.vendorId, device.productId)
            )

        val iface = findBulkInterface(device)
            ?: throw IOException(
                "No vendor-specific bulk interface found " +
                    "(interfaceCount=${device.interfaceCount})"
            )

        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw IOException(
                "claimInterface() failed for interface ${iface.id} — " +
                    "may be held by another process"
            )
        }

        val (epIn, epOut) = findBulkEndpoints(iface)
            ?: run {
                conn.releaseInterface(iface)
                conn.close()
                throw IOException(
                    "No bulk IN/OUT endpoint pair on interface ${iface.id} " +
                        "(endpointCount=${iface.endpointCount})"
                )
            }

        connection = conn
        usbInterface = iface
        endpointIn = epIn
        endpointOut = epOut
        leftover = ByteArray(0)
    }

    /**
     * Pick the interface that actually has bulk IN+OUT.
     *
     * mtkclient lesson (MT6768 / 0e8d:0003): CDC devices often put the *comm*
     * interface at index 0 (interrupt only) and the *data* bulk pair on
     * interface 1. Prefer any interface that has a bulk IN+OUT pair, then
     * prefer vendor-specific (0xFF), then CDC data (0x0A).
     */
    private fun findBulkInterface(device: UsbDevice): UsbInterface? {
        var vendorWithBulk: UsbInterface? = null
        var cdcDataWithBulk: UsbInterface? = null
        var anyWithBulk: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (findBulkEndpoints(iface) == null) continue
            when (iface.interfaceClass) {
                UsbConstants.USB_CLASS_VENDOR_SPEC ->
                    if (vendorWithBulk == null) vendorWithBulk = iface
                UsbConstants.USB_CLASS_CDC_DATA ->
                    if (cdcDataWithBulk == null) cdcDataWithBulk = iface
                else ->
                    if (anyWithBulk == null) anyWithBulk = iface
            }
        }
        return vendorWithBulk ?: cdcDataWithBulk ?: anyWithBulk
            ?: if (device.interfaceCount > 0) device.getInterface(0) else null
    }

    private fun findBulkEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
        }
        return if (epIn != null && epOut != null) Pair(epIn, epOut) else null
    }

    /**
     * Writes [data] to the OUT endpoint in max-packet-sized chunks.
     * Throws if the transfer fails or times out.
     */
    @Throws(IOException::class)
    fun write(data: ByteArray, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int {
        val conn = connection ?: throw IOException("Transport not open")
        val ep = endpointOut ?: throw IOException("No OUT endpoint")
        val maxPkt = ep.maxPacketSize.coerceAtLeast(64)
        var offset = 0
        while (offset < data.size) {
            val chunkLen = minOf(maxPkt, data.size - offset)
            // Explicit 5-arg overload avoids OEM ambiguity with buffer bounds
            val written = conn.bulkTransfer(ep, data, offset, chunkLen, timeoutMs)
            if (written < 0) {
                throw IOException(
                    "bulkTransfer(OUT) failed (returned $written) at offset=$offset chunk=$chunkLen"
                )
            }
            if (written == 0) {
                throw IOException("bulkTransfer(OUT) wrote 0 bytes at offset=$offset")
            }
            offset += written
        }
        return offset
    }

    /**
     * Reads up to [length] bytes from the IN endpoint.
     *
     * OEM USB stacks (many MediaTek host phones) may return an entire USB
     * packet even when a smaller length was requested. Excess bytes are kept
     * in [leftover] for subsequent reads so BROM framing stays in sync.
     */
    @Throws(IOException::class)
    fun read(length: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ByteArray {
        if (length <= 0) return ByteArray(0)

        // Serve from leftover first
        if (leftover.isNotEmpty()) {
            val take = minOf(length, leftover.size)
            val out = leftover.copyOfRange(0, take)
            leftover = if (take < leftover.size) leftover.copyOfRange(take, leftover.size) else ByteArray(0)
            return out
        }

        val conn = connection ?: throw IOException("Transport not open")
        val ep = endpointIn ?: throw IOException("No IN endpoint")
        val maxPkt = ep.maxPacketSize.coerceAtLeast(64)
        // Always use a maxPacket-sized buffer; request full packet so we never
        // under-size relative to what the kernel may deposit.
        val buffer = ByteArray(maxPkt)
        val readBytes = conn.bulkTransfer(ep, buffer, 0, maxPkt, timeoutMs)
        if (readBytes < 0) {
            throw IOException(
                "bulkTransfer(IN) failed (returned $readBytes) maxPkt=$maxPkt"
            )
        }
        if (readBytes == 0) return ByteArray(0)

        if (readBytes <= length) {
            return buffer.copyOf(readBytes)
        }
        // More data arrived than requested — return prefix, stash the rest
        leftover = buffer.copyOfRange(length, readBytes)
        return buffer.copyOfRange(0, length)
    }

    /**
     * Reads exactly [length] bytes, looping packet-sized bulkTransfer calls.
     * Required for multi-byte BROM replies (status uint16, chip id, checksum).
     */
    @Throws(IOException::class)
    fun readExact(length: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var offset = 0
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(100)
        while (offset < length) {
            val remaining = length - offset
            val timeLeft = (deadline - System.currentTimeMillis()).toInt()
            if (timeLeft <= 0) {
                throw IOException("readExact timeout: got $offset / $length bytes")
            }
            val chunk = read(remaining, timeoutMs = timeLeft)
            if (chunk.isEmpty()) {
                throw IOException(
                    "readExact: got 0 bytes before reaching $length (stuck at $offset)"
                )
            }
            // Defensive: never copy past remaining (should not happen after leftover fix)
            val n = minOf(chunk.size, remaining)
            System.arraycopy(chunk, 0, out, offset, n)
            offset += n
            if (chunk.size > n) {
                // Should already be in leftover; keep any unexpected excess
                leftover = chunk.copyOfRange(n, chunk.size) + leftover
            }
        }
        return out
    }

    /**
     * Drain leftover bytes from the IN endpoint (garbage from previous session
     * or Preloader noise). Critical before BROM handshake — stale bytes break
     * the 0xA0/0x0A/0x50/0x05 complement sequence.
     */
    fun drain(maxBytes: Int = 4096, timeoutMs: Int = 30) {
        leftover = ByteArray(0)
        val conn = connection ?: return
        val ep = endpointIn ?: return
        val maxPkt = ep.maxPacketSize.coerceAtLeast(64)
        val buf = ByteArray(maxPkt)
        var total = 0
        while (total < maxBytes) {
            val n = try {
                conn.bulkTransfer(ep, buf, 0, maxPkt, timeoutMs)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) break
            total += n
        }
    }

    override fun close() {
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        connection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000
    }
}
