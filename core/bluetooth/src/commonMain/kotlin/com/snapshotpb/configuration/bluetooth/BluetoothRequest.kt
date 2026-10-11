package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.Device

sealed interface BluetoothRequest {
    object EnableBluetooth : BluetoothRequest

    /** Deep-link to system Location settings (Android pre-S, where BLE scanning needs Location on). */
    object EnableLocation : BluetoothRequest
    object RequestPermission : BluetoothRequest
    object StartScan : BluetoothRequest
    object StopScan : BluetoothRequest
    data class Connect(val deviceId: String) : BluetoothRequest
    data class Disconnect(val deviceId: String) : BluetoothRequest
    /**
     * Writes [device] to the board. The handler copies [requestId] into the [DeviceWriteResult],
     * so the sender can match the result to its own request. `0` means that the sender does not
     * track the result.
     */
    data class WriteDevice(
        val deviceId: String,
        val device: Device,
        val requestId: Long = 0L,
    ) : BluetoothRequest
}
