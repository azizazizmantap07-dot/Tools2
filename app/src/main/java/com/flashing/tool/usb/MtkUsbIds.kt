package com.flashing.tool.usb

/**
 * Known MediaTek USB VID/PID pairs for BROM and Preloader modes.
 *
 * VID 0x0E8D = MediaTek Inc.
 *
 * Important: some OEM builds use non-standard PIDs. Detection therefore
 * accepts *any* device with the MediaTek vendor ID, not only the classic
 * BROM/Preloader product IDs.
 */
object MtkUsbIds {
    const val VENDOR_ID_MEDIATEK = 0x0E8D

    // Classic product IDs — used only for friendly mode description.
    val BROM_PRODUCT_IDS = setOf(
        0x0003, // BROM standard (most modern MTK)
        0x2000, // BROM legacy (older chipsets)
        0x0005, // some Dimensity / newer BROM variants
        0x0002, // rare older BROM
    )

    val PRELOADER_PRODUCT_IDS = setOf(
        0x2001, // Preloader / early DA handoff (most common)
        0x2000, // occasionally appears as Preloader on certain builds
    )

    /** True for any device whose vendor is MediaTek (any product ID). */
    fun isMediaTek(vendorId: Int): Boolean = vendorId == VENDOR_ID_MEDIATEK

    fun isKnownMtkProduct(productId: Int): Boolean =
        productId in BROM_PRODUCT_IDS || productId in PRELOADER_PRODUCT_IDS

    fun describe(productId: Int): String = when {
        productId == 0x0003 || productId == 0x0005 || productId == 0x0002 ->
            "BROM mode (0x${productId.toString(16)})"
        productId == 0x2001 ->
            "Preloader mode (0x${productId.toString(16)})"
        productId == 0x2000 ->
            "BROM/Preloader legacy (0x${productId.toString(16)})"
        productId in BROM_PRODUCT_IDS ->
            "BROM mode (0x${productId.toString(16)})"
        productId in PRELOADER_PRODUCT_IDS ->
            "Preloader mode (0x${productId.toString(16)})"
        else ->
            "MediaTek (PID 0x${productId.toString(16)} — tidak standar)"
    }
}
