package com.snapshotpb.configuration.common.screen.delegate

import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import kotlinx.coroutines.flow.Flow

/**
 * Gives the live eye state of a board to the profile editor.
 *
 * Each collection of [eyeReading] is an eye watcher. Collect it only while the screen is started
 * and the edited profile uses the eye.
 *
 * The key is the board id. It is the same id as the device id of the store and the key of
 * [BluetoothStateManager.connectedDevices].
 */
interface EyeReadingDelegate {
    /**
     * Gives the live eye state of [deviceId]. A collector is an eye watcher. The flow gives `null`
     * while the board is not connected.
     */
    fun eyeReading(deviceId: String): Flow<EyeReadingState?>
}

class EyeReadingDelegateImpl(
    private val bluetoothStateManager: BluetoothStateManager,
) : EyeReadingDelegate {
    override fun eyeReading(deviceId: String): Flow<EyeReadingState?> =
        bluetoothStateManager.eyeReading(deviceId)
}
