package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OtsObject
import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import com.snapshotpb.configuration.model.Device
import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.ServiceFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val log = Arbor.tag("BluetoothManager")

/**
 * The service UUIDs that each scan gives to the Web Bluetooth engine, which puts them in
 * `optionalServices`. The OTS flow uses only the GATT service [OtsUuids.Service] (0x1825). The
 * vendor UUIDs are characteristic and type values in that service. They are in the list too, so
 * the list stays correct if a later firmware moves the vendor `ObjectData` to a service of its own.
 *
 * The board filter UUID 0xFFE2 comes first, as on every other target (#128). `JsEngine` 3.7.14
 * does not filter its chooser with it: it puts the list in `optionalServices` only and asks
 * for all devices. So on the web the user picks the board in the chooser, and the name plays no
 * part.
 */
private val scanFilters: List<ServiceFilter> = SnapshotAdvertisement.scanFilters + listOf(
    ServiceFilter(OtsUuids.Service),
    ServiceFilter(OtsUuids.DeviceObjectType),
    ServiceFilter(OtsUuids.ObjectData),
)

/**
 * Web Bluetooth seam. Scans pass [scanFilters] (board filter + OTS + vendor service UUIDs), and
 * [onServicesDiscovered] discovers the characteristics of each service.
 *
 * The upstream Web Bluetooth engine turns the scan filter list into the
 * `optionalServices` allowlist; with no filters that list is empty and the post-connect
 * `getPrimaryServices(0x1825)` throws `SecurityError`. The chooser itself is not filtered by these —
 * Web Bluetooth can't match services a device doesn't advertise — they are reused only as the
 * post-connect GATT allowlist.
 */
private class BrowserBluetoothSeams(
    blueFalcon: BlueFalcon,
    bluetoothStateManager: BluetoothStateManager
) : DefaultBluetoothSeams(blueFalcon, bluetoothStateManager) {
    override suspend fun scanForDevices() {
        blueFalcon.scan(filters = scanFilters)
    }

    /**
     * The Web Bluetooth engine fills only the service list in `discoverServices`. It gets the
     * characteristics of a service only in `discoverCharacteristics`, and the shared seam does not
     * call it. Without this, the OTS service has no characteristics, `OtsClient` throws
     * `OtsCharacteristicMissing`, and step 3 fails. The iOS seam does the same. The engine awaits
     * each `getCharacteristics()` promise, so no poll follows.
     */
    override suspend fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
        peripheral.services.forEach { service ->
            runCatching { blueFalcon.discoverCharacteristics(peripheral, service) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.d { "discoverCharacteristics failed for ${service.uuid}: ${error.message}" }
                }
        }
    }
}

/**
 * Web Bluetooth-backed manager. The connect/teardown/OTS pipeline lives in
 * [BluetoothConnectionEngine]; the browser specializes the scan and the characteristic discovery (see
 * [BrowserBluetoothSeams]).
 *
 * When [restorePermittedBoards] is true, the manager gives the engine the boards that the user
 * permitted in an earlier visit, at start (see [PermittedBoards]). Set it only for `JsEngine`.
 */
class BluetoothManager(
    blueFalcon: BlueFalcon,
    bluetoothStateManager: BluetoothStateManager,
    bleDebugLog: BleDebugLog,
    deviceReadResultBus: DeviceReadResultBus,
    deviceWriteResultBus: DeviceWriteResultBus,
    restorePermittedBoards: Boolean = false,
    private val coroutineScope: CoroutineScope = BluetoothConnectionEngine.defaultScope()
) {
    private val engine = BluetoothConnectionEngine(
        blueFalcon = blueFalcon,
        bluetoothStateManager = bluetoothStateManager,
        bleDebugLog = bleDebugLog,
        deviceReadResultBus = deviceReadResultBus,
        deviceWriteResultBus = deviceWriteResultBus,
        seams = BrowserBluetoothSeams(blueFalcon, bluetoothStateManager),
        coroutineScope = coroutineScope
    )

    init {
        if (restorePermittedBoards) {
            val permittedBoards = PermittedBoards(blueFalcon, scanFilters, coroutineScope)
            coroutineScope.launch { permittedBoards.restore() }
        }
    }

    val deviceState: StateFlow<BluetoothDeviceState> get() = engine.deviceState

    fun connect(deviceId: String) = engine.connect(deviceId)

    suspend fun disconnect(deviceId: String) = engine.disconnect(deviceId)

    suspend fun startScanning() = engine.startScan()

    suspend fun stopScanning() = engine.stopScan()

    /**
     * Opens the browser device list. Call it directly from a click handler: the browser opens the
     * list only during the user activation of a gesture. The coroutine starts undispatched, so the
     * code runs up to `navigator.bluetooth.requestDevice()` inside the click handler. A dispatch or
     * the request bus first can let the activation expire. In the 3D viewer, the scan of
     * EmulatorEngine adds the viewer board and opens no list.
     */
    fun pairFromUserGesture() {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { engine.scanOnce() }.onFailure { error ->
                if (error is CancellationException) throw error
                log.e(error) { "The browser device list failed" }
            }
        }
    }

    suspend fun listOtsObjects(deviceId: String): List<OtsObject> = engine.listOtsObjects(deviceId)

    suspend fun writeDeviceConfiguration(deviceId: String, domainDevice: Device, requestId: Long = 0L) =
        engine.writeDeviceConfiguration(deviceId, domainDevice, requestId)
}
