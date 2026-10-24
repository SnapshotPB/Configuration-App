package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.FirmwareImage

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

    /**
     * Sends [image] to the update slot of the board over SMP, marks it for a test boot, and resets
     * the board. The handler returns at once: the upload runs on the engine. The engine reports the
     * steps in [BluetoothStateManager.firmwareUpload] with [requestId]. No OTS write runs during the
     * upload. A [Disconnect] stops the upload, and the board then releases its fire-control hold.
     */
    data class UpdateFirmware(
        val deviceId: String,
        val image: FirmwareImage,
        val requestId: Long = 0L,
    ) : BluetoothRequest

    /**
     * Confirms the image that runs on the board when `image list` shows it as not confirmed. The
     * firmware can confirm itself, so a confirmed image gets no command. The engine reports the
     * result in [BluetoothStateManager.firmwareUpload] with [requestId].
     */
    data class ConfirmFirmware(
        val deviceId: String,
        val requestId: Long = 0L,
    ) : BluetoothRequest
}
