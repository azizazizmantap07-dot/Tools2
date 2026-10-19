package com.flashing.tool.usb

/**
 * Prevents Flasher (BROM auto-connect / handshake) and Force Fastboot from
 * fighting over the same USB device at the same time.
 */
object UsbProcessCoordinator {
    enum class Owner {
        NONE,
        FLASHER_BROM,
        FORCE_FASTBOOT,
        FASTBOOT_TERMINAL,
    }

    @Volatile
    var owner: Owner = Owner.NONE
        private set

    @Synchronized
    fun tryAcquire(who: Owner): Boolean {
        if (owner == Owner.NONE || owner == who) {
            owner = who
            return true
        }
        return false
    }

    @Synchronized
    fun release(who: Owner) {
        if (owner == who) owner = Owner.NONE
    }

    @Synchronized
    fun forceRelease() {
        owner = Owner.NONE
    }

    fun describeOwner(): String = when (owner) {
        Owner.NONE -> "tidak ada"
        Owner.FLASHER_BROM -> "Flasher (BROM/DA)"
        Owner.FORCE_FASTBOOT -> "Force Fastboot"
        Owner.FASTBOOT_TERMINAL -> "Fastboot Terminal"
    }
}
