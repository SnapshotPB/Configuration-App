package com.snapshotpb.configuration.bluetooth.emulator

import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.CharacteristicNotification
import dev.bluefalcon.core.ConnectionPriority
import dev.bluefalcon.core.ConnectionStateUpdate
import dev.bluefalcon.core.ServiceDiscoveryPhase
import dev.bluefalcon.core.ServiceDiscoveryUpdate
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A [BlueFalconEngine] for the WebAssembly model of the board in the 3D viewer. The viewer holds the
 * web app in a same-origin iframe and puts the bridge `window.snapshotBoard` on its own window. This
 * engine talks to that bridge in place of Web Bluetooth.
 *
 * The engine copies BlueFalcon 3.7.10 `JsEngine`, faults included, so that the app runs as it runs on
 * the web. [discoverServices] fills only the services, and [discoverCharacteristics] fills the
 * characteristics of one service. The write type is ignored. [changeMTU], [openL2capChannel], the
 * descriptor calls and the bond calls throw. The manager state is always Ready.
 *
 * The engine also does what Chrome does below `JsEngine`. A page can use only the services that the
 * scan filters name (`optionalServices`). A write is a write with response when the characteristic
 * has the write property. A subscription asks for notifications when the characteristic has the
 * notify property, and else for indications. A read gets the whole value, and it also goes to an
 * active notification listener. A read, a write and a notification set [BluetoothCharacteristic.value].
 *
 * These parts differ from `JsEngine`, because the model has no chooser and no page lifetime:
 * - A scan adds the board when the model advertises. Until [stopScanning], each 'advertising' event
 *   that turns on adds it again. [isScanning] stays true until [stopScanning].
 * - A 'disconnected' event of the bridge sends a Disconnected update on [connectionStateUpdates].
 *   `JsEngine` has no listener for `gattserverdisconnected`.
 * - Chrome closes the link of a page that goes away, but the model stays in the viewer. The engine
 *   therefore closes a link that it finds open when it starts.
 */
class EmulatorEngine internal constructor(
    private val bridge: BoardBridge,
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : BlueFalconEngine {

    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()

    private val _managerState = MutableStateFlow(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications

    private val _connectionStateUpdates = MutableSharedFlow<ConnectionStateUpdate>(extraBufferCapacity = 64)
    override val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> = _connectionStateUpdates

    private val _serviceDiscoveryUpdates = MutableSharedFlow<ServiceDiscoveryUpdate>(extraBufferCapacity = 64)
    override val serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate> = _serviceDiscoveryUpdates

    override var isScanning: Boolean = false
        private set

    private val peripheralMap = mutableMapOf<String, EmulatorPeripheral>()

    /** The services that the scan filters named. Chrome gives a page access to these only. */
    private val allowedServices = mutableSetOf<Uuid>()

    /** The peripheral of the open link, or null when no link is open. */
    private var linkedPeripheral: EmulatorPeripheral? = null

    /** The number of the open link. A service from an earlier link is no longer valid. */
    private var link = 0

    /** The characteristics of the open link by value handle. */
    private val remoteCharacteristics = mutableMapOf<Int, RemoteCharacteristic>()

    init {
        bridge.addEventListener(::onBoardEvent)
        // A connected board does not advertise, so a link that an earlier page left open would hide
        // the board from this page.
        if (bridge.isConnected()) {
            scope.launch { runCatching { bridge.disconnect() } }
        }
    }

    /**
     * `JsEngine` opens the Web Bluetooth chooser and adds the device that the user picks. The model
     * has one board and no chooser, so the scan adds the board when the model advertises. The filter
     * UUIDs become the services that the page can use, as `JsEngine` gives them to the chooser as
     * `optionalServices`.
     */
    override suspend fun scan(filters: List<ServiceFilter>) {
        allowedServices += filters.map { it.uuid }
        isScanning = true
        addAdvertisingBoard()
    }

    override suspend fun stopScanning() {
        isScanning = false
    }

    override fun clearPeripherals() {
        _peripherals.value = emptySet()
        peripheralMap.clear()
    }

    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val emulatorPeripheral = peripheral.requireEmulator()

        // `JsEngine` returns at once, with no update, for a device that is connected.
        if (bridge.isConnected()) {
            if (linkedPeripheral == null) openLink(emulatorPeripheral)
            return
        }

        val status = bridge.connect(REQUESTED_MTU)
        if (status != 0) throw EmulatorGattException("NetworkError: Connection failed (errno $status).")
        openLink(emulatorPeripheral)
        _connectionStateUpdates.tryEmit(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))
    }

    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        peripheral.requireEmulator()

        // Close the link first. The 'disconnected' event of the bridge then finds no link and sends
        // no second update.
        closeLink()
        bridge.disconnect()
        _connectionStateUpdates.tryEmit(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected))
    }

    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        if (peripheral !is EmulatorPeripheral) return BluetoothPeripheralState.Unknown
        return if (bridge.isConnected() && linkedPeripheral?.uuid == peripheral.uuid) {
            BluetoothPeripheralState.Connected
        } else {
            BluetoothPeripheralState.Disconnected
        }
    }

    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? = peripheralMap[identifier]

    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        // Web Bluetooth has no connection priority.
    }

    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        val emulatorPeripheral = peripheral.requireEmulator()
        if (linkedPeripheral?.uuid != emulatorPeripheral.uuid) {
            throw EmulatorGattException("NetworkError: GATT Server is disconnected. Cannot retrieve services.")
        }

        val allowed = bridge.gatt().filter { Uuid.parse(it.uuid) in allowedServices }
        val services = if (serviceUUIDs.isEmpty()) {
            allowed.ifEmpty { throw EmulatorGattException("NotFoundError: No Services found in device.") }
        } else {
            // `JsEngine` reads a service that is not there as no service, and lets other errors go.
            serviceUUIDs.flatMap { uuid ->
                if (uuid !in allowedServices) {
                    throw EmulatorGattException("SecurityError: Origin is not allowed to access the service.")
                }
                allowed.filter { Uuid.parse(it.uuid) == uuid }
            }
        }
        emulatorPeripheral.updateServices(services.map { EmulatorService(it, link) })
        _serviceDiscoveryUpdates.tryEmit(
            ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.ServicesDiscovered)
        )
    }

    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val emulatorService = service as? EmulatorService
            ?: throw IllegalArgumentException("Service must be an EmulatorService")
        requireLive(emulatorService)

        val all = emulatorService.board.characteristics
        val found = if (characteristicUUIDs.isEmpty()) {
            all.ifEmpty { throw EmulatorGattException("NotFoundError: No Characteristics found in service.") }
        } else {
            characteristicUUIDs.flatMap { uuid -> all.filter { Uuid.parse(it.uuid) == uuid } }
        }
        emulatorService.updateCharacteristics(
            found.map { board ->
                val remote = remoteCharacteristics.getOrPut(board.handle) { RemoteCharacteristic(board) }
                EmulatorCharacteristic(remote, emulatorService)
            }
        )
        _serviceDiscoveryUpdates.tryEmit(
            ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.CharacteristicsDiscovered, service)
        )
    }

    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ): ByteArray? {
        val emulatorCharacteristic = characteristic.requireEmulator()
        requireLive(emulatorCharacteristic.service)
        val remote = emulatorCharacteristic.remote
        if (remote.properties and GattProperty.READ == 0) {
            throw EmulatorGattException("NotSupportedError: GATT operation not permitted.")
        }

        val value = readWholeValue(remote.handle)
        remote.value = value
        // Chrome fires 'characteristicvaluechanged' for a read too, so an active listener gets the value.
        deliver(remote, value)
        return value.copyOf()
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristic(peripheral, characteristic, value.encodeToByteArray(), writeType)
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        val emulatorCharacteristic = characteristic.requireEmulator()
        requireLive(emulatorCharacteristic.service)
        val remote = emulatorCharacteristic.remote
        if (value.size > MAX_ATTRIBUTE_LENGTH) {
            throw EmulatorGattException("InvalidModificationError: Value can't exceed 512 bytes.")
        }

        // `JsEngine` calls `writeValue()`, which ignores the write type. Chrome then writes with
        // response when the characteristic has the write property.
        val withResponse = remote.properties and GattProperty.WRITE != 0
        if (!withResponse && remote.properties and GattProperty.WRITE_WITHOUT_RESPONSE == 0) {
            throw EmulatorGattException("NotSupportedError: GATT operation not permitted.")
        }
        val bytes = value.copyOf()
        requireSuccess(bridge.write(remote.handle, bytes, withResponse))
        remote.value = bytes
    }

    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        val emulatorCharacteristic = characteristic.requireEmulator()
        val remote = emulatorCharacteristic.remote
        if (notify) {
            requireLive(emulatorCharacteristic.service)
            val mode = when {
                remote.properties and GattProperty.NOTIFY != 0 -> CCC_NOTIFY
                remote.properties and GattProperty.INDICATE != 0 -> CCC_INDICATE
                else -> throw EmulatorGattException("NotSupportedError: GATT operation not permitted.")
            }
            // `JsEngine` adds its listener before it enables the notifications, and removes it when
            // the enable fails.
            remote.listeners[emulatorCharacteristic] = peripheral
            try {
                requireSuccess(bridge.subscribe(remote.handle, mode))
            } catch (error: Throwable) {
                remote.listeners.remove(emulatorCharacteristic)
                throw error
            }
        } else {
            // `JsEngine` removes its listener also when the disable fails.
            try {
                requireLive(emulatorCharacteristic.service)
                requireSuccess(bridge.subscribe(remote.handle, CCC_OFF))
            } finally {
                remote.listeners.remove(emulatorCharacteristic)
            }
        }
    }

    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // Web Bluetooth uses the same call for notifications and indications.
        notifyCharacteristic(peripheral, characteristic, indicate)
    }

    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        throw UnsupportedOperationException("readDescriptor is not fully supported in Web Bluetooth API")
    }

    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        throw UnsupportedOperationException("writeDescriptor is not fully supported in Web Bluetooth API")
    }

    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        throw UnsupportedOperationException("changeMTU is not supported in Web Bluetooth API")
    }

    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean = false

    override suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean
    ): BluetoothSocket {
        throw UnsupportedOperationException("openL2capChannel is not supported in Web Bluetooth API")
    }

    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        throw UnsupportedOperationException("Bonding is not supported in Web Bluetooth API")
    }

    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        throw UnsupportedOperationException("Bonding is not supported in Web Bluetooth API")
    }

    private fun onBoardEvent(event: BoardEvent) {
        when (event) {
            is BoardEvent.Value -> {
                val remote = remoteCharacteristics[event.handle] ?: return
                // Chrome gives a notification only to a characteristic with started notifications.
                if (remote.listeners.isEmpty()) return
                remote.value = event.value.copyOf()
                deliver(remote, event.value)
            }
            is BoardEvent.Disconnected -> {
                val peripheral = linkedPeripheral ?: return
                closeLink()
                _connectionStateUpdates.tryEmit(
                    ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected)
                )
            }
            is BoardEvent.Advertising -> if (event.on && isScanning) addAdvertisingBoard()
        }
    }

    /** Adds the board to [peripherals] when the model advertises. */
    private fun addAdvertisingBoard() {
        val advertisement = bridge.scan() ?: return
        val peripheral = peripheralMap.getOrPut(advertisement.id) { EmulatorPeripheral(advertisement.id) }
        peripheral.name = advertisement.name
        _peripherals.value = _peripherals.value + peripheral
    }

    private fun openLink(peripheral: EmulatorPeripheral) {
        link++
        linkedPeripheral = peripheral
        remoteCharacteristics.clear()
    }

    private fun closeLink() {
        linkedPeripheral = null
        remoteCharacteristics.clear()
    }

    private fun requireLive(service: EmulatorService) {
        if (linkedPeripheral == null) {
            throw EmulatorGattException("NetworkError: GATT Server is disconnected. Cannot perform GATT operations.")
        }
        if (service.link != link) throw EmulatorGattException("InvalidStateError: GATT Service no longer exists.")
    }

    /**
     * Reads the whole value, as the Bluetooth stack under Chrome does: a Read, and then a Read Blob
     * while each part fills the largest ATT payload. The board caps the ATT MTU at [BOARD_MTU].
     */
    private suspend fun readWholeValue(handle: Int): ByteArray {
        var value = ByteArray(0)
        while (true) {
            val result = bridge.read(handle, value.size)
            val endOfValue = result.status == ATT_INVALID_OFFSET || result.status == ATT_ATTRIBUTE_NOT_LONG
            if (value.isNotEmpty() && endOfValue) return value
            requireSuccess(result.status)
            value += result.value
            if (result.value.size < BOARD_MTU - 1 || value.size >= MAX_ATTRIBUTE_LENGTH) return value
        }
    }

    /** Gives [value] to each listener of [remote], as the listener of `JsEngine` does. */
    private fun deliver(remote: RemoteCharacteristic, value: ByteArray) {
        remote.listeners.forEach { (characteristic, peripheral) ->
            characteristic.emitNotification(value)
            _characteristicNotifications.tryEmit(
                CharacteristicNotification(peripheral = peripheral, characteristic = characteristic, value = value)
            )
        }
    }

    private fun requireSuccess(status: Int) {
        if (status != 0) {
            throw EmulatorGattException("NetworkError: GATT operation failed (ATT error 0x${status.toString(16)}).")
        }
    }

    companion object {
        /** The ATT MTU that Chrome asks for. The model reduces it to [BOARD_MTU]. */
        private const val REQUESTED_MTU = 517

        /** The ATT MTU cap of the board. A read response holds at most `BOARD_MTU - 1` bytes. */
        private const val BOARD_MTU = 65

        /** The longest attribute value that ATT permits. */
        private const val MAX_ATTRIBUTE_LENGTH = 512

        private const val ATT_INVALID_OFFSET = 0x07
        private const val ATT_ATTRIBUTE_NOT_LONG = 0x0B

        private const val CCC_OFF = 0
        private const val CCC_NOTIFY = 1
        private const val CCC_INDICATE = 2

        /** True when this page runs in an iframe of the viewer, and the viewer has a board bridge. */
        fun isAvailable(): Boolean = viewerBoard() != null

        /** An engine on the board bridge of the viewer, or null when this page is not in the viewer. */
        fun fromViewerOrNull(): EmulatorEngine? = viewerBoard()?.let { EmulatorEngine(JsBoardBridge(it)) }
    }
}

/** A failed GATT operation. The message starts with the name of the error that Chrome gives. */
internal class EmulatorGattException(message: String) : Exception(message)

/** The board as a [BluetoothPeripheral]. As `JsBluetoothPeripheral`, it has no RSSI and no MTU. */
internal class EmulatorPeripheral(override val uuid: String) : BluetoothPeripheral {
    override var name: String? = null
        internal set

    override val rssi: Float? = null

    override val mtuSize: Int? = null

    private val _services = MutableStateFlow<List<EmulatorService>>(emptyList())

    override val services: List<BluetoothService>
        get() = _services.value

    override val characteristics: List<BluetoothCharacteristic>
        get() = services.flatMap { it.characteristics }

    internal fun updateServices(services: List<EmulatorService>) {
        _services.value = services
    }
}

/** A service of the link with the number [link]. It has no characteristics until they are discovered. */
internal class EmulatorService(internal val board: BoardService, internal val link: Int) : BluetoothService {
    override val uuid: Uuid = Uuid.parse(board.uuid)

    override val name: String?
        get() = board.uuid

    private val _characteristics = MutableStateFlow<List<EmulatorCharacteristic>>(emptyList())

    override val characteristics: List<BluetoothCharacteristic>
        get() = _characteristics.value

    internal fun updateCharacteristics(characteristics: List<EmulatorCharacteristic>) {
        _characteristics.value = characteristics
    }
}

/**
 * One characteristic of the open link. Chrome keeps one object for each characteristic, so each
 * wrapper from a new discovery shares its [value] and its [listeners].
 */
internal class RemoteCharacteristic(board: BoardCharacteristic) {
    val uuid: String = board.uuid
    val handle: Int = board.handle
    val properties: Int = board.properties
    var value: ByteArray? = null

    /** The wrappers that started notifications, with the peripheral that each one gave. */
    val listeners: MutableMap<EmulatorCharacteristic, BluetoothPeripheral> = mutableMapOf()
}

/** A characteristic as `JsBluetoothCharacteristic` shows it: no descriptors, and never notifying. */
internal class EmulatorCharacteristic(
    internal val remote: RemoteCharacteristic,
    override val service: EmulatorService,
) : BluetoothCharacteristic {
    override val uuid: Uuid = Uuid.parse(remote.uuid)

    override val name: String?
        get() = remote.uuid

    override val value: ByteArray?
        get() = remote.value?.copyOf()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    override val notifications: SharedFlow<ByteArray>
        get() = _notifications.asSharedFlow()

    override val descriptors: List<BluetoothCharacteristicDescriptor> = emptyList()

    /** False, as in `JsEngine`: Web Bluetooth does not show the subscription. */
    override val isNotifying: Boolean = false

    internal fun emitNotification(value: ByteArray) {
        _notifications.tryEmit(value.copyOf())
    }
}

private fun BluetoothPeripheral.requireEmulator(): EmulatorPeripheral =
    this as? EmulatorPeripheral ?: throw IllegalArgumentException("Peripheral must be an EmulatorPeripheral")

private fun BluetoothCharacteristic.requireEmulator(): EmulatorCharacteristic =
    this as? EmulatorCharacteristic
        ?: throw IllegalArgumentException("Characteristic must be an EmulatorCharacteristic")
