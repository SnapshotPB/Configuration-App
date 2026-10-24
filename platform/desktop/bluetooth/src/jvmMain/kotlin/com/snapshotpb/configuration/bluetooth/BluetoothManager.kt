package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OtsObject
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.FirmwareImage
import dev.bluefalcon.core.BlueFalcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop (JVM/BlueFalcon) manager. The connect/teardown/OTS pipeline lives in
 * [BluetoothConnectionEngine]; desktop needs none of the platform seams, so it composes the engine
 * with the stock [DefaultBluetoothSeams] (a scan filtered on the board service, no permission or MTU
 * step) and forwards its public API to it.
 */
class BluetoothManager(
    blueFalcon: BlueFalcon,
    bluetoothStateManager: BluetoothStateManager,
    bleDebugLog: BleDebugLog,
    deviceReadResultBus: DeviceReadResultBus,
    deviceWriteResultBus: DeviceWriteResultBus,
    coroutineScope: CoroutineScope = BluetoothConnectionEngine.defaultScope()
) {
    private val engine = BluetoothConnectionEngine(
        blueFalcon = blueFalcon,
        bluetoothStateManager = bluetoothStateManager,
        bleDebugLog = bleDebugLog,
        deviceReadResultBus = deviceReadResultBus,
        deviceWriteResultBus = deviceWriteResultBus,
        seams = DefaultBluetoothSeams(blueFalcon, bluetoothStateManager),
        coroutineScope = coroutineScope
    )

    val deviceState: StateFlow<BluetoothDeviceState> get() = engine.deviceState

    fun connect(deviceId: String) = engine.connect(deviceId)

    suspend fun disconnect(deviceId: String) = engine.disconnect(deviceId)

    suspend fun startScanning() = engine.startScan()

    suspend fun stopScanning() = engine.stopScan()

    suspend fun listOtsObjects(deviceId: String): List<OtsObject> = engine.listOtsObjects(deviceId)

    suspend fun writeDeviceConfiguration(deviceId: String, domainDevice: Device, requestId: Long = 0L) =
        engine.writeDeviceConfiguration(deviceId, domainDevice, requestId)

    fun updateFirmware(deviceId: String, image: FirmwareImage, requestId: Long = 0L) =
        engine.updateFirmware(deviceId, image, requestId)

    fun confirmFirmware(deviceId: String, requestId: Long = 0L) = engine.confirmFirmware(deviceId, requestId)
}
