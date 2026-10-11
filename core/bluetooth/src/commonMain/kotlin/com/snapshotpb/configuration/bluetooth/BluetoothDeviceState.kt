package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.AdvertisementData
import dev.bluefalcon.core.BluetoothPeripheral

data class BluetoothDeviceState(
    val devices: Map<String, BluetoothPeripheral> = mapOf(),
    val advertisementData: Map<String, AdvertisementData> = mapOf()
) {
    /**
     * Scan keys of every discovered Snapshot board (see [SnapshotAdvertisement.isSnapshotBoard]).
     * Discovery does not use the advertised name (#128).
     *
     * Projected here rather than filtered at the call site so that consumers — notably the shared
     * device-browser view model in `core:common` — never have to touch [BluetoothPeripheral]. That
     * type comes from BlueFalcon, which is a `core:bluetooth` implementation detail; referencing it
     * from the UI module would mean putting a BLE library on the shared UI's compile classpath.
     */
    fun boardScanKeys(): List<String> =
        devices.entries.mapNotNull { (key, peripheral) ->
            key.takeIf { SnapshotAdvertisement.isSnapshotBoard(peripheral) }
        }
}
