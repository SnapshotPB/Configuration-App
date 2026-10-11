package com.snapshotpb.configuration.bluetooth

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.LocationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.location.LocationManagerCompat
import com.snapshotpb.configuration.bluetooth.ots.OtsObject
import com.snapshotpb.configuration.model.AdvertisementData
import com.snapshotpb.configuration.model.Device
import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.engine.android.AndroidBluetoothPeripheral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android seam: the platform's heavier requirements layered onto the shared pipeline.
 *
 *  - Runtime Bluetooth permission gating ([permissionGranted] / [needsPermissions] feeding
 *    [updateBluetoothState]).
 *  - The scan: ONE native [ScanCallback], filtered on the board service UUID 0xFFE2 (#128). It reads
 *    the service data of the board: 0xFFE0 (board type and firmware version) into
 *    [BluetoothDeviceState.advertisementData], and 0xFFE1 (the board `device_id`) into
 *    [BluetoothStateManager.boardIds], so a known board matches before the connect. It hands each
 *    board to BlueFalcon through [BlueFalcon.retrievePeripheral]. BlueFalcon 3.7.14 keeps no
 *    service data of a scan result, so its own scan cannot replace this one, and a second scan
 *    beside it only doubled the scan starts that Android limits to 5 in 30 s.
 *  - ATT MTU negotiation after service discovery ([onServicesDiscovered]); poll-only service
 *    discovery (the Android engine auto-discovers on connect).
 */
private class AndroidBluetoothSeams(
    blueFalcon: BlueFalcon,
    bluetoothStateManager: BluetoothStateManager
) : DefaultBluetoothSeams(blueFalcon, bluetoothStateManager) {
    // Written by a single logical source each (permission/location on the main thread via lifecycle
    // callbacks; manager state from the BlueFalcon collector on Dispatchers.Default) but read by
    // [updateBluetoothState] from both threads, so each is @Volatile for cross-thread visibility.
    @Volatile
    private var hasPermission: Boolean = false

    @Volatile
    private var locationEnabled: Boolean = true

    @Volatile
    private var lastManagerState: BluetoothManagerState = BluetoothManagerState.NotReady

    private var nativeScanCallback: ScanCallback? = null

    /**
     * Guards [nativeScanCallback] and the scanner start/stop, which are reachable concurrently from
     * the bus thread (UI scan) and the connect job (scan-to-locate on the engine's coroutine scope).
     */
    private val nativeScanLock = Any()

    /**
     * Serializes the read-derive-publish in [updateBluetoothState], which runs concurrently from the
     * main thread (permission/location callbacks) and the manager-state collector on
     * [Dispatchers.Default]. Without it the compound read of the current [BluetoothState] and the
     * subsequent [BluetoothStateManager.update] can interleave and latch a stale gating state.
     */
    private val stateDerivationLock = Any()

    fun permissionGranted() {
        hasPermission = true
        updateBluetoothState()
    }

    fun needsPermissions() {
        hasPermission = false
        updateBluetoothState()
    }

    fun locationServicesEnabled() {
        locationEnabled = true
        updateBluetoothState()
    }

    fun locationServicesDisabled() {
        locationEnabled = false
        updateBluetoothState()
    }

    override fun onManagerState(state: BluetoothManagerState) {
        lastManagerState = state
        updateBluetoothState()
    }

    private fun updateBluetoothState(): Unit = synchronized(stateDerivationLock) {
        if (!hasPermission) {
            bluetoothStateManager.update(BluetoothState.NeedsPermission)
            return
        }

        when (lastManagerState) {
            BluetoothManagerState.Ready -> {
                val current = bluetoothStateManager.bluetoothState.value
                // Re-derive only from idle gating states; never clobber an active Scanning/Connecting.
                // Location services (pre-S) are a scan-time gate layered on top of permission + radio.
                if (current == BluetoothState.Disabled ||
                    current == BluetoothState.NeedsPermission ||
                    current == BluetoothState.LocationDisabled ||
                    current == BluetoothState.Ready
                ) {
                    bluetoothStateManager.update(
                        if (locationEnabled) BluetoothState.Ready else BluetoothState.LocationDisabled
                    )
                }
            }
            BluetoothManagerState.NotReady -> {
                bluetoothStateManager.update(BluetoothState.Disabled)
            }
        }
    }

    /**
     * Add-only (never overwrites a peripheral once seen) and preserves [BluetoothDeviceState.advertisementData],
     * which is populated independently by the native scan in [handleScanResult]. The default seam
     * updates the cached name and would reset the advertisement map, which Android must not do. The
     * scan calls this, and so does the engine for each peripheral that BlueFalcon reports.
     */
    override fun addDevice(peripheral: BluetoothPeripheral) {
        val deviceId = peripheral.uuid.toDeviceUuid().toString()
        _deviceState.update { deviceState ->
            if (!deviceState.devices.keys.contains(deviceId)) {
                val devicePair = deviceId to peripheral

                log.d { "Adding device $deviceId (peripheral uuid=${peripheral.uuid})" }
                BluetoothDeviceState(
                    devices = deviceState.devices + devicePair,
                    advertisementData = deviceState.advertisementData
                )
            } else deviceState
        }
    }

    override suspend fun scanForDevices() {
        startNativeScan()
    }

    override suspend fun stopScanForDevices() {
        stopNativeScan()
    }

    private fun startNativeScan(): Unit = synchronized(nativeScanLock) {
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return

        stopNativeScan()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                log.e { "Native scan failed: $errorCode" }
            }
        }

        nativeScanCallback = callback
        try {
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // The only scan on Android. The filter is the service UUID in the primary advertisement,
            // and an active scan also delivers the scan response with the 0xFFE0 service data.
            scanner.startScan(listOf(boardScanFilter()), settings, callback)
        } catch (e: SecurityException) {
            log.e(e) { "Missing permissions for native scan" }
        }
    }

    private fun stopNativeScan(): Unit = synchronized(nativeScanLock) {
        val callback = nativeScanCallback ?: return
        nativeScanCallback = null
        val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(callback)
        } catch (e: SecurityException) {
            log.e(e) { "Missing permissions to stop native scan" }
        }
    }

    private fun boardScanFilter(): android.bluetooth.le.ScanFilter =
        android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(SnapshotAdvertisement.FilterService.toParcelUuid())
            .build()

    private fun handleScanResult(result: ScanResult?) {
        val scanRecord = result?.scanRecord ?: return
        val deviceAddress = result.device?.address ?: return
        val deviceId = deviceAddress.toDeviceUuid().toString()

        // BlueFalcon connects a peripheral that its own scan did not find: its connect registers
        // the peripheral first. The key is the one that the engine gives the same peripheral.
        if (deviceId !in _deviceState.value.devices) {
            blueFalcon.retrievePeripheral(deviceAddress)?.let(::addDevice)
        }
        // A peripheral made from an address has no signal strength and no advertised service
        // UUIDs. The engine uses the signal to pick the closest board (see
        // BluetoothConnectionEngine.findPeripheral), and SnapshotAdvertisement.isSnapshotBoard
        // checks the UUIDs, so copy both from the scan record.
        (_deviceState.value.devices[deviceId] as? AndroidBluetoothPeripheral)?.let { peripheral ->
            peripheral.rssi = result.rssi.toFloat()
            scanRecord.serviceUuids?.let { uuids ->
                peripheral.advertisedServiceUUIDs = uuids.map { kotlin.uuid.Uuid.parse(it.uuid.toString()) }
            }
        }

        scanRecord.getServiceData(SnapshotAdvertisement.IdentityService.toParcelUuid())
            ?.let(SnapshotAdvertisement::boardIdOf)
            ?.let { boardId -> bluetoothStateManager.recordBoardId(deviceId, boardId) }

        val parsed = scanRecord.getServiceData(SnapshotAdvertisement.BoardInfoService.toParcelUuid())
            ?.let(AdvertisementData::parse)
            ?: return
        if (_deviceState.value.advertisementData[deviceId] == parsed) return
        log.d { "Parsed advertisement data for device $deviceId: $parsed" }
        _deviceState.update { state ->
            state.copy(advertisementData = state.advertisementData + (deviceId to parsed))
        }
    }

    private fun kotlin.uuid.Uuid.toParcelUuid(): android.os.ParcelUuid =
        android.os.ParcelUuid.fromString(toString())

    override suspend fun awaitServicesDiscovered(peripheral: BluetoothPeripheral): Boolean =
        withTimeoutOrNull(CONNECTION_READY_TIMEOUT_MILLIS) {
            while (peripheral.services.isEmpty()) delay(CONNECTION_POLL_INTERVAL_MILLIS)
            true
        } == true

    override suspend fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
        negotiateMaxMtu(peripheral)
    }

    /**
     * Default ATT MTU is 23 (20 payload bytes), which truncates notifications. Request the
     * BLE 4.2+ maximum and wait for [BluetoothPeripheral.mtuSize] to be populated by the
     * `onMtuChanged` callback before returning — Android only allows one GATT op in flight,
     * so subscribing to notifications while this exchange is pending silently drops the
     * descriptor writes that enable them.
     */
    private suspend fun negotiateMaxMtu(peripheral: BluetoothPeripheral) {
        runCatching { blueFalcon.changeMTU(peripheral, MAX_ATT_MTU) }
            .onFailure { error ->
                // A cancelled connect must stop here, not go on into the OTS setup.
                if (error is CancellationException) throw error
                log.d { "Failed to request MTU $MAX_ATT_MTU: ${error.message}" }
                return
            }
        val negotiated = withTimeoutOrNull(MTU_NEGOTIATION_TIMEOUT_MILLIS) {
            while (peripheral.mtuSize == null) delay(MTU_POLL_INTERVAL_MILLIS)
            peripheral.mtuSize
        }
        log.d { "MTU negotiation result: $negotiated" }
    }

    /**
     * No wait. BlueFalcon's Android engine puts the CCC descriptor write in its per-connection GATT
     * queue, and the queue sends the next operation only after `onDescriptorWrite`. So a control-point
     * write that follows cannot overtake the subscription.
     */
    override suspend fun awaitSubscribed(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {}

    override fun formatDebugBytes(event: BleDebugEvent): String =
        event.bytes.toString(Charsets.US_ASCII)

    companion object {
        private const val MAX_ATT_MTU = 517
        private const val MTU_NEGOTIATION_TIMEOUT_MILLIS = 2_000L
        private const val MTU_POLL_INTERVAL_MILLIS = 25L
        private const val CONNECTION_READY_TIMEOUT_MILLIS = 10_000L
        private const val CONNECTION_POLL_INTERVAL_MILLIS = 50L
        private val log = Arbor.tag("BluetoothManager")
    }
}

/**
 * Android manager. Composes the shared [BluetoothConnectionEngine] with [AndroidBluetoothSeams],
 * forwards the common public API to the engine, and forwards the permission-state callbacks to the
 * seam. The static permission check/request helpers stay on this companion (imported by the Android
 * and Wear `MainActivity`s).
 */
class BluetoothManager(
    blueFalcon: BlueFalcon,
    bluetoothStateManager: BluetoothStateManager,
    bleDebugLog: BleDebugLog,
    deviceReadResultBus: DeviceReadResultBus,
    deviceWriteResultBus: DeviceWriteResultBus,
    coroutineScope: CoroutineScope = BluetoothConnectionEngine.defaultScope()
) {
    private val seams = AndroidBluetoothSeams(blueFalcon, bluetoothStateManager)
    private val engine = BluetoothConnectionEngine(
        blueFalcon = blueFalcon,
        bluetoothStateManager = bluetoothStateManager,
        bleDebugLog = bleDebugLog,
        deviceReadResultBus = deviceReadResultBus,
        deviceWriteResultBus = deviceWriteResultBus,
        seams = seams,
        coroutineScope = coroutineScope
    )

    val deviceState: StateFlow<BluetoothDeviceState> get() = engine.deviceState

    fun connect(deviceId: String) = engine.connect(deviceId)

    suspend fun disconnect(deviceId: String) = engine.disconnect(deviceId)

    suspend fun stopScanning() = engine.stopScan()

    suspend fun listOtsObjects(deviceId: String): List<OtsObject> = engine.listOtsObjects(deviceId)

    suspend fun writeDeviceConfiguration(deviceId: String, domainDevice: Device, requestId: Long = 0L) =
        engine.writeDeviceConfiguration(deviceId, domainDevice, requestId)

    fun permissionGranted() = seams.permissionGranted()

    fun needsPermissions() = seams.needsPermissions()

    fun locationServicesEnabled() = seams.locationServicesEnabled()

    fun locationServicesDisabled() = seams.locationServicesDisabled()

    suspend fun startScanning(componentActivity: ComponentActivity) {
        if (!componentActivity.hasBluetoothPermission()) {
            throw RuntimeException("Permissions not granted.")
        }
        engine.startScan()
    }

    companion object {
        private val log = Arbor.tag("BluetoothManager")
        private val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                BLUETOOTH_CONNECT,
                BLUETOOTH_SCAN
            )

            // Pre-S: BLUETOOTH / BLUETOOTH_ADMIN are install-time (normal) permissions granted via the
            // manifest, so the only runtime grant gating BLE scanning is location. Without it, scans
            // silently return no results on API 24-30.
            else -> arrayOf(
                ACCESS_FINE_LOCATION
            )
        }

        fun ComponentActivity.hasBluetoothPermission(): Boolean =
            permissions.fold(true) { acc, permission ->
                val checkResult =
                    checkSelfPermission(
                        this@hasBluetoothPermission,
                        permission
                    ) == PERMISSION_GRANTED
                log.d { "$permission granted: $checkResult" }
                acc && checkResult
            }

        fun ComponentActivity.requestBluetoothPermissions(requestCode: Int) {
            requestPermissions(permissions, requestCode)
        }

        /**
         * Whether BLE scanning can return results given the system Location toggle. From API 31 (S),
         * [BLUETOOTH_SCAN] is declared with `neverForLocation`, so scanning no longer depends on
         * Location; below S the scanner silently returns nothing while Location services are off.
         */
        fun ComponentActivity.locationEnabledForScan(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
            val locationManager = getSystemService(LocationManager::class.java) ?: return true
            return LocationManagerCompat.isLocationEnabled(locationManager)
        }
    }
}
