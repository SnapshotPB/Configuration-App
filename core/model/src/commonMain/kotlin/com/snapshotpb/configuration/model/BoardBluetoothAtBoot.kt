package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * The state of the Bluetooth radio when the board turns on (`DeviceConfig.bluetooth_at_boot`).
 *
 * `BLUETOOTH_AT_BOOT_OFF` is [OFF], and `BLUETOOTH_AT_BOOT_RESTORE` is [RESTORE].
 *
 * The board records the last state that the user selected. The app cannot read or write that
 * state. Lockout always turns Bluetooth off.
 */
@Serializable
enum class BoardBluetoothAtBoot {
    OFF,
    RESTORE,
    ;

    /** True when the board starts the radio in the last state that the user selected. */
    val remembersState: Boolean get() = this == RESTORE

    companion object {
        /** The setting of a switch that is on ([RESTORE]) or off ([OFF]). */
        fun of(remembersState: Boolean): BoardBluetoothAtBoot = if (remembersState) RESTORE else OFF
    }
}
