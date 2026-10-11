package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OtsObject
import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import com.snapshotpb.configuration.model.Device
import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * iOS (CoreBluetooth/BlueFalcon) seam. Layers CoreBluetooth's power-on/permission lifecycle onto the
 * shared pipeline:
 *
 *  - [awaitReadyToConnect] waits for the radio to power on before a connect (iOS silently drops
 *    scan()/connect() issued before `.poweredOn`).
 *  - [onManagerState] maps a "not ready" to either [BluetoothState.PermissionDenied] or
 *    [BluetoothState.Disabled] via [notReadyState], rather than always "disabled".
 */
private class IosBluetoothSeams(
    blueFalcon: BlueFalcon,
    bluetoothStateManager: BluetoothStateManager
) : DefaultBluetoothSeams(blueFalcon, bluetoothStateManager) {

    override fun onManagerState(state: BluetoothManagerState) {
        when (state) {
            BluetoothManagerState.Ready ->
                if (bluetoothStateManager.bluetoothState.value == BluetoothState.Disabled ||
                    bluetoothStateManager.bluetoothState.value == BluetoothState.NeedsPermission ||
                    bluetoothStateManager.bluetoothState.value == BluetoothState.PermissionDenied
                ) {
                    bluetoothStateManager.update(BluetoothState.Ready)
                }
            BluetoothManagerState.NotReady ->
                bluetoothStateManager.update(notReadyState())
        }
    }

    /**
     * The first connect after construction can run while CoreBluetooth is still powering on or the
     * permission prompt is unanswered (scanning gates on Ready, connect doesn't). iOS silently drops
     * scan()/connect() issued before `.poweredOn`, so wait for the radio first; if it never comes up
     * (Bluetooth off or permission denied) abort to the not-ready state rather than letting the
     * connect time out into a misleading Ready.
     */
    override suspend fun awaitReadyToConnect(deviceId: String): Boolean {
        if (awaitRadioReady()) return true
        log.d { "CoreBluetooth not ready (off or permission denied); aborting connect to $deviceId" }
        bluetoothStateManager.updateProgress(ConnectionProgress(step1 = StepStatus.FAILED))
        bluetoothStateManager.update(notReadyState())
        return false
    }

    /**
     * iOS: BlueFalcon's `connect()` can return before CoreBluetooth's `didConnect`, and CoreBluetooth
     * silently ignores a `discoverServices()` issued on a not-yet-connected peripheral. The base seam
     * issues exactly one `discoverServices()` then polls, so on iOS that single call races the link and
     * services never populate — step 2 (service discovery) fails. Wait for the link to come up, then
     * (re)issue discovery each tick until services appear.
     */
    override suspend fun awaitServicesDiscovered(peripheral: BluetoothPeripheral): Boolean {
        var retries = 0
        while (peripheral.services.isEmpty() && retries < SERVICE_DISCOVERY_RETRIES) {
            if (blueFalcon.connectionState(peripheral) == BluetoothPeripheralState.Connected) {
                runCatching { blueFalcon.discoverServices(peripheral) }
                    .onFailure { error ->
                        log.d { "discoverServices failed for ${peripheral.uuid}: ${error.message}" }
                    }
            }
            delay(SERVICE_DISCOVERY_POLL_INTERVAL_MILLIS)
            retries++
        }
        val ok = peripheral.services.isNotEmpty()
        log.d {
            "iOS service discovery for ${peripheral.uuid}: " +
                if (ok) "${peripheral.services.size} service(s)" else "timed out"
        }
        return ok
    }

    /**
     * iOS/CoreBluetooth — unlike the Android BlueFalcon engine — does not auto-discover a service's
     * characteristics after service discovery; each service needs an explicit
     * `discoverCharacteristics()`. Without this every service's `characteristics` list stays empty, so
     * `OtsClient`'s `requireCharacteristic()` throws and the connect aborts before any OTS exchange (the
     * board logs a connect, then no GATT activity, then a client-initiated disconnect). The engine calls
     * this immediately before it builds the OTS client, so discover characteristics for every service
     * here and wait until the OTS service's are populated.
     */
    override suspend fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
        peripheral.services.forEach { service ->
            runCatching { blueFalcon.discoverCharacteristics(peripheral, service, emptyList()) }
                .onFailure { error ->
                    log.d { "discoverCharacteristics failed for ${service.uuid}: ${error.message}" }
                }
        }
        var retries = 0
        while (
            peripheral.services.none { it.uuid == OtsUuids.Service && it.characteristics.isNotEmpty() } &&
            retries < CHARACTERISTIC_DISCOVERY_RETRIES
        ) {
            delay(CHARACTERISTIC_DISCOVERY_POLL_INTERVAL_MILLIS)
            retries++
        }
        log.d { "Characteristic discovery settled for ${peripheral.uuid}" }
    }

    /**
     * Waits until CoreBluetooth reports the subscription (`CBCharacteristic.isNotifying`, which
     * BlueFalcon's Apple engine passes through), for [SUBSCRIBE_SETTLE_MILLIS] at most. A timeout
     * continues, as the old fixed wait did: the control-point write that follows fails on its own
     * timeout if the subscription never came.
     */
    override suspend fun awaitSubscribed(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {
        val subscribed = withTimeoutOrNull(SUBSCRIBE_SETTLE_MILLIS) {
            while (!characteristic.isNotifying) delay(SUBSCRIBE_POLL_INTERVAL_MILLIS)
            true
        } == true
        if (!subscribed) log.d { "No subscription confirmed for ${characteristic.uuid}; continuing" }
    }

    /**
     * Maps a CoreBluetooth "not ready" to either [BluetoothState.PermissionDenied] (the user declined
     * or is restricted — recoverable only via system settings) or [BluetoothState.Disabled] (the radio
     * is simply off). Without the split both surface as "Bluetooth disabled", which mislabels a denial
     * and offers no recovery.
     */
    private fun notReadyState(): BluetoothState =
        when (currentBluetoothAuthorization()) {
            BluetoothAuthorization.DENIED -> BluetoothState.PermissionDenied
            else -> BluetoothState.Disabled
        }

    /**
     * Suspends until CoreBluetooth reports it is powered on, returning false if that doesn't happen
     * within [RADIO_READY_TIMEOUT_MILLIS] — e.g. Bluetooth is off or the user denied permission.
     * Watches BlueFalcon's [BlueFalcon.managerState] directly rather than [BluetoothStateManager]
     * because the latter is masked by [BluetoothState.Connecting] during a connect attempt.
     */
    private suspend fun awaitRadioReady(): Boolean =
        withTimeoutOrNull(RADIO_READY_TIMEOUT_MILLIS) {
            blueFalcon.managerState.first { it == BluetoothManagerState.Ready }
            true
        } ?: false

    companion object {
        private const val RADIO_READY_TIMEOUT_MILLIS = 10_000L
        private const val SERVICE_DISCOVERY_RETRIES = 20
        private const val SERVICE_DISCOVERY_POLL_INTERVAL_MILLIS = 500L
        private const val CHARACTERISTIC_DISCOVERY_RETRIES = 20
        private const val CHARACTERISTIC_DISCOVERY_POLL_INTERVAL_MILLIS = 250L
        private const val SUBSCRIBE_SETTLE_MILLIS = 1_000L
        private const val SUBSCRIBE_POLL_INTERVAL_MILLIS = 10L
        private val log = Arbor.tag("BluetoothManager")
    }
}

/**
 * iOS manager. Composes the shared [BluetoothConnectionEngine] with [IosBluetoothSeams] and forwards
 * its public API, plus the iOS-only [activate] entry point.
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
        seams = IosBluetoothSeams(blueFalcon, bluetoothStateManager),
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

    /**
     * Brings CoreBluetooth online. The side effect that matters has already happened by the time this
     * runs: constructing this manager (and its engine) started BlueFalcon's CBCentralManager, and
     * creating that central manager is what makes iOS show the system Bluetooth permission prompt
     * (when authorization is not-yet-determined). The iOS entry point calls this only in response to
     * the user opting in, or at launch when Bluetooth is already authorized — never speculatively — so
     * the prompt stays tied to a user action. The engine's `managerState` collector then settles
     * [BluetoothState] (Ready / Disabled / PermissionDenied) once CoreBluetooth reports its state.
     * Idempotent.
     */
    fun activate() {
        log.d { "Bluetooth activation requested; CoreBluetooth is now initializing." }
    }

    companion object {
        private val log = Arbor.tag("BluetoothManager")
    }
}
