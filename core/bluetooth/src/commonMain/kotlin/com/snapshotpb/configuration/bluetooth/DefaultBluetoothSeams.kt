package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.ServiceDiscoveryPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Default [PlatformBluetoothSeams] used directly by desktop and as the base for the browser, iOS and
 * Android seams. Owns [deviceState] and provides the shared behavior: a scan for the board service, a
 * name-updating [addDevice], the default manager-state mapping, service discovery that waits for the
 * link to open, OTS characteristic discovery when the engine did not do it, no MTU step, immediate
 * connect-readiness, and hex debug formatting. Subclasses override only the members that genuinely
 * differ on their platform.
 */
open class DefaultBluetoothSeams(
    protected val blueFalcon: BlueFalcon,
    protected val bluetoothStateManager: BluetoothStateManager
) : PlatformBluetoothSeams {
    protected val _deviceState: MutableStateFlow<BluetoothDeviceState> =
        MutableStateFlow(BluetoothDeviceState())
    override val deviceState: StateFlow<BluetoothDeviceState> get() = _deviceState

    /**
     * Records a [peripheral], updating its cached name when BlueFalcon later re-emits it with a
     * non-empty name. Android overrides this to be add-only and to preserve the advertisement map it
     * populates from a parallel native scan.
     */
    override fun addDevice(peripheral: BluetoothPeripheral) {
        val deviceId = peripheral.uuid.toDeviceUuid().toString()
        _deviceState.update { deviceState ->
            val existing = deviceState.devices[deviceId]
            val newName = peripheral.name
            val shouldUpdate = existing == null || (!newName.isNullOrEmpty() && existing.name != newName)
            if (shouldUpdate) {
                val devicePair = deviceId to peripheral
                log.d { "Adding/updating device $deviceId (peripheral uuid=${peripheral.uuid}, name=$newName)" }
                deviceState.copy(devices = deviceState.devices + devicePair)
            } else deviceState
        }
    }

    /**
     * Default mapping: Ready→Ready (only out of Disabled/NeedsPermission) and NotReady→Disabled.
     * Android layers in permission gating and iOS distinguishes a permission denial (see overrides).
     */
    override fun onManagerState(state: BluetoothManagerState) {
        when (state) {
            BluetoothManagerState.Ready ->
                if (bluetoothStateManager.bluetoothState.value == BluetoothState.Disabled ||
                    bluetoothStateManager.bluetoothState.value == BluetoothState.NeedsPermission
                ) {
                    bluetoothStateManager.update(BluetoothState.Ready)
                }
            BluetoothManagerState.NotReady ->
                bluetoothStateManager.update(BluetoothState.Disabled)
        }
    }

    /**
     * Scans for boards only: the filter is the Snapshot service UUID 0xFFE2 that the board
     * advertises (#128). Desktop and iOS use this. The engine gives no service data, so these
     * targets learn the board id only from the read.
     */
    override suspend fun scanForDevices() {
        blueFalcon.scan(SnapshotAdvertisement.scanFilters)
    }

    override suspend fun stopScanForDevices() {
        blueFalcon.stopScanning()
    }

    /**
     * Refuses a connect while the radio is not ready (L14): with Bluetooth off the attempt could
     * only time out. The refusal marks step 1 failed and puts the radio-derived state back, so the
     * card shows a failure and a later scan cannot start with no radio. iOS overrides this to wait
     * for CoreBluetooth to power on.
     */
    override suspend fun awaitReadyToConnect(deviceId: String): Boolean {
        if (blueFalcon.managerState.value == BluetoothManagerState.Ready) return true
        log.d { "Bluetooth is not ready; refusing the connect to $deviceId" }
        bluetoothStateManager.updateProgress(
            ConnectionProgress(step1 = StepStatus.FAILED, failureCode = "BluetoothNotReady")
        )
        onManagerState(BluetoothManagerState.NotReady)
        return false
    }

    /**
     * Discovers the characteristics of the OTS service when the engine did not supply them with the
     * services. `MacosJvmEngine` supplies services only, and it adds the characteristics later, one
     * at a time. So this waits for its `CharacteristicsDiscovered` event, which comes after the last
     * characteristic, and not for a non-empty list. The Windows and Linux engines supply the
     * characteristics with the services, so this does nothing for them. An engine that sends no
     * event stops the wait at the time limit, and `OtsClient` then reports a missing characteristic.
     */
    override suspend fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
        val service = peripheral.services.firstOrNull { it.uuid == OtsUuids.Service } ?: return
        if (service.characteristics.isNotEmpty()) return
        log.d { "Discovering the OTS characteristics for ${peripheral.uuid}..." }
        val discovered = coroutineScope {
            val event = async(start = CoroutineStart.UNDISPATCHED) {
                blueFalcon.serviceDiscoveryUpdates.first { update ->
                    update.phase == ServiceDiscoveryPhase.CharacteristicsDiscovered &&
                        update.peripheral.uuid == peripheral.uuid &&
                        update.service?.uuid == OtsUuids.Service
                }
            }
            runCatching { blueFalcon.discoverCharacteristics(peripheral, service) }
                .onFailure { error -> if (error is CancellationException) throw error }
            withTimeoutOrNull(CHARACTERISTIC_DISCOVERY_TIMEOUT_MILLIS) { event.await() }
                .also { event.cancel() } != null
        }
        if (!discovered) log.d { "No characteristic discovery event for ${peripheral.uuid}; continuing" }
    }

    /**
     * Waits for the link to open, then starts discovery, and polls until the services are present.
     * Android overrides this: its engine discovers by itself on connect, so it only polls.
     *
     * The discovery request must wait for [BluetoothPeripheralState.Connected]. `connect()` of
     * `MacosJvmEngine` returns before the link opens and does not discover by itself, and
     * CoreBluetooth ignores `discoverServices` on a peripheral that is not connected. A request sent
     * at once was lost, and every connect on macOS failed at step 2. The Windows engine blocks in
     * `connect()` and the Linux engine discovers by itself, so they did not show the fault.
     *
     * The result is true only when the services are present AND the link is open. Services from an
     * earlier link do not end the wait before the new link opens.
     */
    override suspend fun awaitServicesDiscovered(peripheral: BluetoothPeripheral): Boolean {
        var discoveryRequested = false
        repeat(SERVICE_DISCOVERY_RETRIES) {
            if (blueFalcon.connectionState(peripheral) == BluetoothPeripheralState.Connected) {
                if (peripheral.services.isNotEmpty()) return true
                if (!discoveryRequested) {
                    log.d { "Discovering services for ${peripheral.uuid}..." }
                    runCatching { blueFalcon.discoverServices(peripheral) }
                        .onFailure { error -> if (error is CancellationException) throw error }
                    discoveryRequested = true
                    if (peripheral.services.isNotEmpty()) return true
                }
            }
            delay(SERVICE_DISCOVERY_POLL_INTERVAL_MILLIS)
        }
        return false
    }

    /**
     * A fixed wait. The desktop engines (Windows, macOS, Linux) and Web Bluetooth report neither the
     * CCC write nor the subscription state (their `isNotifying` shows only whether the
     * characteristic supports notifications), so there is nothing to wait for. Android and iOS
     * override this.
     */
    override suspend fun awaitSubscribed(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {
        delay(SUBSCRIBE_SETTLE_MILLIS)
    }

    override fun formatDebugBytes(event: BleDebugEvent): String = event.hex

    companion object {
        // 10 s for the seam alone. The engine stops each connect attempt after 5 s
        // (CONNECT_ATTEMPT_TIMEOUT_MILLIS), which is the real limit on the connect path. The short
        // interval starts discovery soon after the link opens.
        private const val SERVICE_DISCOVERY_RETRIES = 100
        private const val SERVICE_DISCOVERY_POLL_INTERVAL_MILLIS = 100L
        private const val SUBSCRIBE_SETTLE_MILLIS = 1_000L
        private const val CHARACTERISTIC_DISCOVERY_TIMEOUT_MILLIS = 5_000L
        private val log = Arbor.tag("BluetoothManager")
    }
}
