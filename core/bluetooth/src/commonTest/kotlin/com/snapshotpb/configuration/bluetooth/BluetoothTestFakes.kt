package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.AdvertisementData
import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.CharacteristicNotification
import dev.bluefalcon.core.CharacteristicWriteCapability
import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.ConnectionPriority
import dev.bluefalcon.core.ServiceDiscoveryUpdate
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal [BlueFalconEngine] for driving a real `BlueFalcon` in tests. The engine interface is large
 * but the [BluetoothConnectionEngine] only touches a handful of members; everything else is a no-op
 * or a sensible default. [peripherals] / [managerState] are exposed as mutable flows so tests can
 * emit discovery / radio-state changes, and connect/disconnect calls are recorded.
 */
internal class FakeBlueFalconEngine(
    override val scope: CoroutineScope
) : BlueFalconEngine {
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> get() = _peripherals

    private val _managerState = MutableStateFlow(BluetoothManagerState.NotReady)
    override val managerState: StateFlow<BluetoothManagerState> get() = _managerState

    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = MutableSharedFlow()

    /** Discovery events. A test emits here to answer as an engine that reports discovery. */
    val serviceDiscoveryEvents = MutableSharedFlow<ServiceDiscoveryUpdate>(extraBufferCapacity = 16)
    override val serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate> get() = serviceDiscoveryEvents
    override val isScanning: Boolean = false

    val connectCalls: MutableList<BluetoothPeripheral> = mutableListOf()
    val disconnectCalls: MutableList<BluetoothPeripheral> = mutableListOf()
    var connectionStateResult: BluetoothPeripheralState = BluetoothPeripheralState.Disconnected

    /** Receives every byte write; [FakeOtsBoard.install] sets it to answer as an OTS server. */
    var onWriteCharacteristic: (suspend (BluetoothCharacteristic, ByteArray) -> Unit)? = null

    /** Receives every read request; [FakeOtsBoard.install] sets it to supply the value. */
    var onReadCharacteristic: (suspend (BluetoothCharacteristic) -> Unit)? = null

    /** Receives every `discoverServices` call. A test sets it to fill the services of the peripheral. */
    var onDiscoverServices: (suspend (BluetoothPeripheral) -> Unit)? = null

    /** Services given to `discoverCharacteristics`, in order. */
    val discoverCharacteristicsCalls: MutableList<BluetoothService> = mutableListOf()

    /** Receives every `discoverCharacteristics` call. A test sets it to fill the characteristics of the service. */
    var onDiscoverCharacteristics: (suspend (BluetoothPeripheral, BluetoothService) -> Unit)? = null

    fun emitPeripherals(peripherals: Set<BluetoothPeripheral>) { _peripherals.value = peripherals }
    fun emitManagerState(state: BluetoothManagerState) { _managerState.value = state }

    /** The filters of each [scan] call. */
    val scanFilters: MutableList<List<ServiceFilter>> = mutableListOf()

    override suspend fun scan(filters: List<ServiceFilter>) { scanFilters += filters }
    override suspend fun stopScanning() {}
    override fun clearPeripherals() {}
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        connectCalls += peripheral
    }
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        disconnectCalls += peripheral
    }
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState = connectionStateResult
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? = null
    /** Connection priorities requested, in order. */
    val connectionPriorities: MutableList<ConnectionPriority> = mutableListOf()
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        connectionPriorities += priority
    }
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        onDiscoverServices?.invoke(peripheral)
    }
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        discoverCharacteristicsCalls += service
        onDiscoverCharacteristics?.invoke(peripheral, service)
    }
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
    ): ByteArray? {
        onReadCharacteristic?.invoke(characteristic)
        return characteristic.value
    }
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {}
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        onWriteCharacteristic?.invoke(characteristic, value)
    }

    /**
     * Answers each typed write (BlueFalcon 3.7). Null keeps the default of the engine interface,
     * `Unsupported`, as `JsEngine` has it.
     */
    var onTypedWrite: (suspend (BluetoothCharacteristic, ByteArray, CharacteristicWriteType) -> CharacteristicWriteResult)? = null

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult =
        onTypedWrite?.invoke(characteristic, value, writeType) ?: CharacteristicWriteResult.Unsupported

    /** The write state of each peripheral and write type. A test sets it to model backpressure. */
    val writeCapabilities = MutableStateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>>(emptyMap())
    override val characteristicWriteCapabilities: StateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>>
        get() = writeCapabilities

    /**
     * The value of `maximumWriteValueLength`. When null, the limit comes from [writeCapabilities],
     * as in the engine interface; with no entry there, it is null as for an engine that reports no
     * limit.
     */
    var maximumWriteLength: Int? = null
    override fun maximumWriteValueLength(peripheral: BluetoothPeripheral, writeType: CharacteristicWriteType): Int? =
        maximumWriteLength ?: writeCapabilities.value[CharacteristicWriteKey(peripheral.uuid, writeType)]?.maximumLength

    /** Each `notifyCharacteristic` call: the characteristic and the requested state. */
    val notifyCalls: MutableList<Pair<BluetoothCharacteristic, Boolean>> = mutableListOf()

    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        notifyCalls += characteristic to notify
    }
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {}
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {}
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {}
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {}
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean = false
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int, secure: Boolean): BluetoothSocket =
        error("openL2capChannel is not supported in tests")
    override suspend fun createBond(peripheral: BluetoothPeripheral) {}
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {}
}

/** A [BluetoothPeripheral] with caller-supplied fields; defaults to no services/characteristics. */
internal class FakeBluetoothPeripheral(
    override val uuid: String,
    override val name: String? = null,
    override val rssi: Float? = null,
    override val mtuSize: Int? = null,
    override val services: List<BluetoothService> = emptyList(),
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
) : BluetoothPeripheral

/** A [BluetoothService] used only to make a peripheral's service list non-empty. */
internal class FakeBluetoothService(
    override val uuid: Uuid = Uuid.random(),
    override val name: String? = null,
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
) : BluetoothService

/**
 * A controllable [PlatformBluetoothSeams] that records the engine's calls and lets a test steer the
 * connect path (ready-to-connect / services-discovered outcomes, an optional gate to park a connect
 * mid-flight for cancellation tests). Owns [deviceState] like a real seam.
 */
internal class FakePlatformBluetoothSeams : PlatformBluetoothSeams {
    private val _deviceState = MutableStateFlow(BluetoothDeviceState())
    override val deviceState: StateFlow<BluetoothDeviceState> get() = _deviceState

    fun setDevices(
        devices: Map<String, BluetoothPeripheral>,
        advertisementData: Map<String, AdvertisementData> = emptyMap(),
    ) {
        _deviceState.value = BluetoothDeviceState(devices = devices, advertisementData = advertisementData)
    }

    val addDeviceCalls: MutableList<BluetoothPeripheral> = mutableListOf()
    val managerStates: MutableList<BluetoothManagerState> = mutableListOf()
    val onServicesDiscoveredCalls: MutableList<BluetoothPeripheral> = mutableListOf()
    var scanCount: Int = 0
    var stopScanCount: Int = 0
    var awaitReadyResult: Boolean = true
    var awaitReadyGate: CompletableDeferred<Unit>? = null
    var awaitServicesResult: Boolean = true

    /**
     * Optional per-attempt outcomes for [awaitServicesDiscovered]: each call pops the next value,
     * falling back to [awaitServicesResult] once drained. Lets a test drive the connect-retry loop
     * (e.g. `false, false, true` = succeeds on the third attempt).
     */
    val awaitServicesResults: ArrayDeque<Boolean> = ArrayDeque()
    var awaitServicesCalls: Int = 0

    /** When set, parks each [awaitServicesDiscovered] call until completed — lets a test cancel mid-attempt. */
    var awaitServicesGate: CompletableDeferred<Unit>? = null

    override fun addDevice(peripheral: BluetoothPeripheral) { addDeviceCalls += peripheral }
    override fun onManagerState(state: BluetoothManagerState) { managerStates += state }
    override suspend fun scanForDevices() { scanCount++ }
    override suspend fun stopScanForDevices() { stopScanCount++ }
    override suspend fun awaitReadyToConnect(deviceId: String): Boolean {
        awaitReadyGate?.await()
        return awaitReadyResult
    }
    override suspend fun onServicesDiscovered(peripheral: BluetoothPeripheral) { onServicesDiscoveredCalls += peripheral }
    override suspend fun awaitServicesDiscovered(peripheral: BluetoothPeripheral): Boolean {
        awaitServicesCalls++
        awaitServicesGate?.await()
        return if (awaitServicesResults.isNotEmpty()) awaitServicesResults.removeFirst() else awaitServicesResult
    }

    /** Characteristics passed to [awaitSubscribed], in order. The fake does not wait. */
    val subscribedCharacteristics: MutableList<BluetoothCharacteristic> = mutableListOf()
    override suspend fun awaitSubscribed(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {
        subscribedCharacteristics += characteristic
    }
    override fun formatDebugBytes(event: BleDebugEvent): String = event.hex
}