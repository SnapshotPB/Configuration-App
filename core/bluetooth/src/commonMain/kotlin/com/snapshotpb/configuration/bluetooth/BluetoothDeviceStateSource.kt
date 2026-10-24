package com.snapshotpb.configuration.bluetooth

import kotlinx.coroutines.flow.Flow

/**
 * Gives the discovered devices of the platform Bluetooth manager to shared code.
 *
 * Each platform `BluetoothManager` has its own `deviceState`, with no common supertype, so each app
 * binds this seam. The app wraps the flow with `DebugBoard.deviceState` where it has a simulated
 * board, so the advertisement of the simulated board is in the flow too.
 */
fun interface BluetoothDeviceStateSource {
    /** The discovered devices and their advertisement data, keyed by scan key. */
    fun deviceState(): Flow<BluetoothDeviceState>
}
