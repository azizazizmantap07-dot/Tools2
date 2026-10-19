package com.flashing.tool.protocol

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Base64
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * Minimal host-side ADB client for USB OTG — enough to trigger the USB debugging
 * authorization dialog and send `reboot:bootloader` / `reboot:fastboot`, similar to
 * Bugjaeger / platform-tools `adb reboot bootloader`.
 *
 * Not a full ADB shell implementation.
 */
class AdbClient(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val keyStoreDir: File,
    private val log: (String) -> Unit,
) : Closeable {

    @Throws(IOException::class)
    fun connectAndReboot(target: String) {
        // target: "bootloader" | "fastboot" | "recovery" | "" (normal)
        log("ADB: claim interface ${intf.id}")
        if (!connection.claimInterface(intf, true)) {
            throw IOException("Gagal claim interface ADB")
        }
        try {
            // Minimal banner — some OEM stacks reject a long features list
            log("ADB: mengirim CNXN…")
            send(
                A_CNXN, A_VERSION, MAX_PAYLOAD,
                "host::\u0000".toByteArray(Charsets.UTF_8)
            )

            /*
             * ADB auth (same as desktop adb / Bugjaeger):
             *  1. Device sends AUTH TOKEN
             *  2. Host tries SIGNATURE with stored RSA key
             *  3. If device sends TOKEN again → key not trusted → host sends RSAPUBLICKEY
             *  4. Target shows «Izinkan debugging USB» — user must tap Allow
             *  5. Device sends CNXN
             *
             * Bug sebelumnya: hanya kirim SIGNATURE; public key tidak pernah dikirim
             * → popup di HP target tidak pernah muncul.
             */
            var authorized = false
            var sentSignature = false
            var sentPublicKey = false
            val pair = loadOrCreateKeyPair()
            val deadline = System.currentTimeMillis() + 90_000

            while (System.currentTimeMillis() < deadline && !authorized) {
                val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1_000)
                val msg = readMessage(timeoutMs = remaining)
                when (msg.command) {
                    A_CNXN -> {
                        log("ADB: CNXN dari device (${msg.data.toString(Charsets.UTF_8).trim()})")
                        authorized = true
                    }
                    A_AUTH -> {
                        when (msg.arg0) {
                            ADB_AUTH_TOKEN -> {
                                if (!sentSignature) {
                                    log("ADB: AUTH TOKEN — kirim SIGNATURE")
                                    try {
                                        val sig = signToken(pair.private, msg.data)
                                        send(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                                        sentSignature = true
                                    } catch (e: Exception) {
                                        log("ADB: signature gagal (${e.message}) — kirim public key")
                                        sendPublicKey(pair)
                                        sentPublicKey = true
                                        sentSignature = true
                                    }
                                } else if (!sentPublicKey) {
                                    // Signature ditolak perangkat → kunci belum dipercaya
                                    log(
                                        "ADB: SIGNATURE ditolak — mengirim public key.\n" +
                                            "→ Di HP *target* harus muncul popup «Izinkan debugging USB».\n" +
                                            "→ Centang «Selalu izinkan dari komputer ini» lalu OK."
                                    )
                                    sendPublicKey(pair)
                                    sentPublicKey = true
                                } else {
                                    log(
                                        "ADB: masih AUTH TOKEN setelah public key — " +
                                            "tunggu / izinkan popup di HP target…"
                                    )
                                    // Small pause so user can tap Allow; then read again
                                    try {
                                        Thread.sleep(500)
                                    } catch (_: InterruptedException) {
                                    }
                                }
                            }
                            ADB_AUTH_RSAPUBLICKEY -> {
                                log("ADB: device meminta public key")
                                sendPublicKey(pair)
                                sentPublicKey = true
                            }
                            else -> log("ADB: AUTH tipe ${msg.arg0}")
                        }
                    }
                    A_STLS -> throw IOException("ADB STLS: ${msg.data.toString(Charsets.UTF_8)}")
                    else -> log("ADB: pesan tak terduga cmd=0x${msg.command.toString(16)}")
                }
            }

            if (!authorized) {
                throw IOException(
                    "Otorisasi ADB gagal (tidak ada CNXN dalam 90 dtk). " +
                        "Pastikan popup «Izinkan debugging USB» di HP target diizinkan. " +
                        "Jika popup tidak muncul: cabut/colok USB, set mode File transfer, " +
                        "nyalakan Debugging USB (dan setelan keamanan MTK jika ada)."
                )
            }

            val service = if (target.isBlank()) "reboot:" else "reboot:$target"
            log("ADB: OPEN $service")
            send(A_OPEN, 1, 0, "$service\u0000".toByteArray(Charsets.UTF_8))

            try {
                val resp = readMessage(timeoutMs = 5_000)
                when (resp.command) {
                    A_OKAY -> log("ADB: OKAY — perangkat menjalankan reboot")
                    A_CLSE -> log("ADB: CLSE — saluran ditutup (normal saat reboot)")
                    A_WRTE -> {
                        log("ADB: WRTE ${resp.data.toString(Charsets.UTF_8)}")
                        send(A_OKAY, resp.arg1, resp.arg0, ByteArray(0))
                    }
                    else -> log("ADB: respons 0x${resp.command.toString(16)}")
                }
            } catch (e: Exception) {
                log("ADB: koneksi terputus saat reboot (sering normal): ${e.message}")
            }
        } finally {
            try {
                connection.releaseInterface(intf)
            } catch (_: Exception) {
            }
        }
    }

    private fun sendPublicKey(pair: KeyPair) {
        val pub = encodeAdbPublicKey(pair.public as RSAPublicKey, "flashingtool@android")
        send(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pub)
        log("ADB: public key terkirim (${pub.size} bytes) — cek popup di HP target sekarang")
    }

    override fun close() {
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }

    private data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray,
    )

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(data.sumOf { it.toInt() and 0xFF })
        header.putInt(command xor MAGIC_XOR)
        val hdr = header.array()
        writeFully(hdr)
        if (data.isNotEmpty()) writeFully(data)
    }

    private fun readMessage(timeoutMs: Int): AdbMessage {
        val hdr = ByteArray(24)
        readFully(hdr, timeoutMs)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        val check = bb.int
        val magic = bb.int
        if (magic != (command xor MAGIC_XOR)) {
            throw IOException("ADB header magic mismatch")
        }
        if (len < 0 || len > MAX_PAYLOAD * 2) {
            throw IOException("ADB data length invalid: $len")
        }
        val data = if (len > 0) {
            val d = ByteArray(len)
            readFully(d, timeoutMs)
            val sum = d.sumOf { it.toInt() and 0xFF }
            if (sum != check) {
                // Some stacks are lax; only warn via log
                log("ADB: data checksum mismatch (lanjut)")
            }
            d
        } else {
            ByteArray(0)
        }
        return AdbMessage(command, arg0, arg1, data)
    }

    private fun writeFully(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = connection.bulkTransfer(epOut, data, off, data.size - off, 5_000)
            if (n <= 0) throw IOException("ADB bulk OUT gagal ($n)")
            off += n
        }
    }

    private fun readFully(buf: ByteArray, timeoutMs: Int) {
        var off = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (off < buf.size) {
            val left = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val n = connection.bulkTransfer(epIn, buf, off, buf.size - off, left)
            if (n < 0) throw IOException("ADB bulk IN timeout/gagal")
            if (n == 0) continue
            off += n
        }
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        val privFile = File(keyStoreDir, "adbkey")
        val x509File = File(keyStoreDir, "adbkey.x509")
        val pubFile = File(keyStoreDir, "adbkey.pub")
        keyStoreDir.mkdirs()
        if (privFile.isFile && x509File.isFile) {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
            val pub = kf.generatePublic(java.security.spec.X509EncodedKeySpec(x509File.readBytes()))
            return KeyPair(pub, priv)
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        privFile.writeBytes(pair.private.encoded)
        x509File.writeBytes(pair.public.encoded)
        pubFile.writeBytes(encodeAdbPublicKey(pair.public as RSAPublicKey, "flashingtool@android"))
        log("ADB: kunci RSA baru dibuat (untuk popup debugging USB)")
        return pair
    }

    private fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        // ADB expects PKCS#1-like padded signature of the token
        val padded = pkcs1Pad(token, 256)
        return cipher.doFinal(padded)
    }

    private fun pkcs1Pad(data: ByteArray, blockSize: Int): ByteArray {
        if (data.size > blockSize - 11) throw IOException("token terlalu panjang")
        val out = ByteArray(blockSize)
        out[0] = 0x00
        out[1] = 0x01
        val padLen = blockSize - data.size - 3
        for (i in 0 until padLen) out[2 + i] = 0xFF.toByte()
        out[2 + padLen] = 0x00
        System.arraycopy(data, 0, out, 3 + padLen, data.size)
        return out
    }

    companion object {
        private const val MAGIC_XOR = 0xffffffff.toInt()
        private const val A_VERSION = 0x01000001
        private const val MAX_PAYLOAD = 1024 * 1024
        private const val A_CNXN = 0x4e584e43 // CNXN
        private const val A_AUTH = 0x48545541 // AUTH
        private const val A_OPEN = 0x4e45504f // OPEN
        private const val A_OKAY = 0x59414b4f // OKAY
        private const val A_CLSE = 0x45534c43 // CLSE
        private const val A_WRTE = 0x45545257 // WRTE
        private const val A_STLS = 0x534c5453 // STLS
        private const val ADB_AUTH_TOKEN = 1
        private const val ADB_AUTH_SIGNATURE = 2
        private const val ADB_AUTH_RSAPUBLICKEY = 3

        /**
         * USB ADB interface: standard is class 0xFF / subclass 0x42 / protocol 0x01.
         * Some OEM composite devices put ADB on a secondary interface or omit the
         * class byte; we still accept bulk IN+OUT when subclass/protocol match.
         */
        fun findAdbInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            // Pass 1: strict ADB signature
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66 && intf.interfaceProtocol == 1) {
                    endpointsOf(intf)?.let { return Triple(intf, it.first, it.second) }
                }
            }
            // Pass 2: subclass/protocol only (some composite firmwares)
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceSubclass == 66 && intf.interfaceProtocol == 1) {
                    endpointsOf(intf)?.let { return Triple(intf, it.first, it.second) }
                }
            }
            return null
        }

        private fun endpointsOf(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inn: UsbEndpoint? = null
            var out: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inn = ep
                else out = ep
            }
            return if (inn != null && out != null) Pair(inn, out) else null
        }

        fun isAdbDevice(device: UsbDevice): Boolean = findAdbInterface(device) != null

        /**
         * Encode RSA public key in the ADB host format (struct RSAPublicKey + user@host).
         */
        fun encodeAdbPublicKey(publicKey: RSAPublicKey, userHost: String): ByteArray {
            val modulus = publicKey.modulus
            val exponent = publicKey.publicExponent.toInt()
            val modBytes = modulus.toByteArray().let { raw ->
                // Strip sign byte if present
                if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            }
            // Expect 256 bytes for 2048-bit
            val n = ByteArray(256)
            System.arraycopy(modBytes, 0, n, 256 - modBytes.size, modBytes.size)

            val words = 2048 / 32
            val buf = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(words)
            // n0inv = -1 / n[0] mod 2^32
            val n0 = ByteBuffer.wrap(n, 252, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
            val n0inv = invMod2_32(n0)
            buf.putInt(n0inv.toInt())
            // modulus little-endian words
            for (i in 0 until words) {
                val offset = 256 - (i + 1) * 4
                val w = ((n[offset].toInt() and 0xff) shl 24) or
                    ((n[offset + 1].toInt() and 0xff) shl 16) or
                    ((n[offset + 2].toInt() and 0xff) shl 8) or
                    (n[offset + 3].toInt() and 0xff)
                buf.putInt(w)
            }
            // rr = R^2 mod n  (R=2^2048) — compute simply via BigInteger
            val rr = java.math.BigInteger.ONE.shiftLeft(4096).mod(modulus)
            val rrBytes = rr.toByteArray().let { raw ->
                if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            }
            val rrArr = ByteArray(256)
            System.arraycopy(rrBytes, 0, rrArr, 256 - rrBytes.size, rrBytes.size)
            for (i in 0 until words) {
                val offset = 256 - (i + 1) * 4
                val w = ((rrArr[offset].toInt() and 0xff) shl 24) or
                    ((rrArr[offset + 1].toInt() and 0xff) shl 16) or
                    ((rrArr[offset + 2].toInt() and 0xff) shl 8) or
                    (rrArr[offset + 3].toInt() and 0xff)
                buf.putInt(w)
            }
            buf.putInt(exponent)
            val keyBytes = buf.array()
            val b64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            return (b64 + " " + userHost + "\u0000").toByteArray(Charsets.UTF_8)
        }

        private fun invMod2_32(n0: Long): Long {
            // modular inverse of n0 mod 2^32, then negate
            var x = n0
            // Newton-Raphson inverse for 2^32
            x = x * (2 - n0 * x)
            x = x * (2 - n0 * x)
            x = x * (2 - n0 * x)
            x = x * (2 - n0 * x)
            x = x * (2 - n0 * x)
            return (-x) and 0xffffffffL
        }

        @Throws(IOException::class)
        fun open(
            usb: UsbManager,
            device: UsbDevice,
            keyStoreDir: File,
            log: (String) -> Unit,
        ): AdbClient {
            val found = findAdbInterface(device)
                ?: throw IOException("Perangkat tidak menampilkan interface ADB (aktifkan USB debugging)")
            val conn = usb.openDevice(device)
                ?: throw IOException("Gagal openDevice — beri izin USB")
            return AdbClient(conn, found.first, found.second, found.third, keyStoreDir, log)
        }
    }
}
