package com.snapshotpb.configuration.bluetooth.emulator

import com.snapshotpb.configuration.bluetooth.emulator.FakeBoardBridge.Companion.BOARD_ID
import com.snapshotpb.configuration.bluetooth.emulator.FakeBoardBridge.Companion.BOARD_NAME
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.CharacteristicNotification
import dev.bluefalcon.core.ConnectionStateUpdate
import dev.bluefalcon.core.DisconnectReason
import dev.bluefalcon.core.PeripheralConnectionState
import dev.bluefalcon.core.ServiceDiscoveryPhase
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Unit tests of [EmulatorEngine] against [FakeBoardBridge]: the behavior of `JsEngine` on the bridge. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmulatorEngineTest {

    private class Fixture(
        val bridge: FakeBoardBridge,
        val engine: EmulatorEngine,
        val peripheral: BluetoothPeripheral,
    )

    /** An engine with the board found by a scan that names [SERVICE] only. */
    private suspend fun TestScope.scannedFixture(): Fixture {
        val bridge = FakeBoardBridge(services = GATT)
        val engine = EmulatorEngine(bridge, backgroundScope)
        engine.scan(listOf(ServiceFilter(Uuid.parse(SERVICE))))
        return Fixture(bridge, engine, engine.peripherals.value.single())
    }

    /** [scannedFixture], connected. */
    private suspend fun TestScope.connectedFixture(): Fixture =
        scannedFixture().also { it.engine.connect(it.peripheral) }

    /** [connectedFixture], with the services and the characteristics of [SERVICE] discovered. */
    private suspend fun TestScope.discoveredFixture(): Fixture =
        connectedFixture().also { f ->
            f.engine.discoverServices(f.peripheral, emptyList())
            f.engine.discoverCharacteristics(f.peripheral, f.peripheral.services.single(), emptyList())
        }

    private fun Fixture.characteristic(handle: Int): BluetoothCharacteristic =
        peripheral.characteristics.first { (it as EmulatorCharacteristic).remote.handle == handle }

    /** Collects [flow] from now on, with no wait for a dispatch. */
    private fun <T> TestScope.collected(flow: Flow<T>): List<T> {
        val items = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { items += it } }
        return items
    }

    @Test
    fun managerState_isReady() = runTest {
        val engine = EmulatorEngine(FakeBoardBridge(), backgroundScope)

        assertEquals(BluetoothManagerState.Ready, engine.managerState.value)
    }

    @Test
    fun scan_whileTheModelAdvertises_addsTheBoard() = runTest {
        val f = scannedFixture()

        assertEquals(BOARD_ID, f.peripheral.uuid)
        assertEquals(BOARD_NAME, f.peripheral.name)
        // As JsBluetoothPeripheral: no RSSI, no MTU.
        assertNull(f.peripheral.rssi)
        assertNull(f.peripheral.mtuSize)
        assertTrue(f.engine.isScanning)
        assertSame(f.peripheral, f.engine.retrievePeripheral(BOARD_ID))
    }

    @Test
    fun scan_whileTheModelDoesNotAdvertise_addsTheBoardOnTheAdvertisingEvent() = runTest {
        val bridge = FakeBoardBridge(advertisement = null)
        val engine = EmulatorEngine(bridge, backgroundScope)

        engine.scan(emptyList())
        assertTrue(engine.peripherals.value.isEmpty())

        bridge.advertisement = BoardAdvertisement(BOARD_ID, BOARD_NAME)
        bridge.emit(BoardEvent.Advertising(on = true))

        assertEquals(BOARD_ID, engine.peripherals.value.single().uuid)
    }

    @Test
    fun advertisingEvent_afterStopScanning_addsNothing() = runTest {
        val bridge = FakeBoardBridge(advertisement = null)
        val engine = EmulatorEngine(bridge, backgroundScope)
        engine.scan(emptyList())
        engine.stopScanning()

        bridge.advertisement = BoardAdvertisement(BOARD_ID, BOARD_NAME)
        bridge.emit(BoardEvent.Advertising(on = true))

        assertFalse(engine.isScanning)
        assertTrue(engine.peripherals.value.isEmpty())
    }

    @Test
    fun start_withALinkThatAnEarlierPageLeftOpen_closesTheLink() = runTest {
        val bridge = FakeBoardBridge(connected = true)

        EmulatorEngine(bridge, backgroundScope)
        runCurrent()

        assertEquals(1, bridge.disconnectCalls)
        assertFalse(bridge.connected)
    }

    @Test
    fun connect_asksForTheMtuOfChromeAndSendsConnected() = runTest {
        val f = scannedFixture()
        val updates = collected(f.engine.connectionStateUpdates)

        f.engine.connect(f.peripheral)

        assertEquals(listOf(517), f.bridge.connectCalls)
        assertEquals(listOf(BluetoothPeripheralState.Connected), updates.map { it.state })
        assertEquals(BluetoothPeripheralState.Connected, f.engine.connectionState(f.peripheral))
    }

    @Test
    fun connect_toABoardThatIsConnected_returnsWithNoUpdate() = runTest {
        val f = connectedFixture()
        val updates = collected(f.engine.connectionStateUpdates)

        f.engine.connect(f.peripheral)

        assertEquals(1, f.bridge.connectCalls.size)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun connect_thatTheModelRefuses_failsTheBlueFalconConnect() = runTest {
        val f = scannedFixture()
        f.bridge.connectStatus = -111
        val blueFalcon = BlueFalcon(f.engine)

        blueFalcon.connect(f.peripheral)

        val state = assertIs<PeripheralConnectionState.Disconnected>(blueFalcon.peripheralState(f.peripheral))
        assertIs<DisconnectReason.ConnectFailed>(state.reason)
        assertEquals(BluetoothPeripheralState.Disconnected, f.engine.connectionState(f.peripheral))
    }

    @Test
    fun discoverServices_fillsOnlyTheServicesThatTheScanFiltersName() = runTest {
        val f = connectedFixture()
        val updates = collected(f.engine.serviceDiscoveryUpdates)

        f.engine.discoverServices(f.peripheral, emptyList())

        val service = f.peripheral.services.single()
        assertEquals(Uuid.parse(SERVICE), service.uuid)
        // As JsEngine: the characteristics come only from discoverCharacteristics.
        assertTrue(service.characteristics.isEmpty())
        assertEquals(listOf(ServiceDiscoveryPhase.ServicesDiscovered), updates.map { it.phase })
        assertSame(f.peripheral, updates.single().peripheral)
    }

    @Test
    fun discoverServices_forAServiceThatTheScanFiltersDoNotName_throws() = runTest {
        val f = connectedFixture()

        val error = assertFailsWith<EmulatorGattException> {
            f.engine.discoverServices(f.peripheral, listOf(Uuid.parse(HIDDEN_SERVICE)))
        }
        assertTrue(error.message!!.startsWith("SecurityError"))
    }

    @Test
    fun discoverServices_beforeConnect_throws() = runTest {
        val f = scannedFixture()

        assertFailsWith<EmulatorGattException> { f.engine.discoverServices(f.peripheral, emptyList()) }
    }

    @Test
    fun discoverCharacteristics_fillsTheCharacteristicsOfTheService() = runTest {
        val f = connectedFixture()
        f.engine.discoverServices(f.peripheral, emptyList())
        val service = f.peripheral.services.single()
        val updates = collected(f.engine.serviceDiscoveryUpdates)

        f.engine.discoverCharacteristics(f.peripheral, service, emptyList())

        assertEquals(GATT.first().characteristics.map { Uuid.parse(it.uuid) }, service.characteristics.map { it.uuid })
        assertEquals(service.characteristics, f.peripheral.characteristics)
        // As JsBluetoothCharacteristic: never notifying, no descriptors.
        assertTrue(service.characteristics.none { it.isNotifying || it.descriptors.isNotEmpty() })
        val update = updates.single()
        assertEquals(ServiceDiscoveryPhase.CharacteristicsDiscovered, update.phase)
        assertSame(service, update.service)
    }

    @Test
    fun readCharacteristic_returnsTheWholeValueInPartsOfTheMtu() = runTest {
        val f = discoveredFixture()
        val long = ByteArray(100) { it.toByte() }
        f.bridge.values[READ] = long
        val characteristic = f.characteristic(READ)

        val value = f.engine.readCharacteristic(f.peripheral, characteristic)

        assertContentEquals(long, value)
        assertEquals(listOf(READ to 0, READ to 64), f.bridge.reads)
        assertContentEquals(long, characteristic.value)
    }

    @Test
    fun readCharacteristic_withAnAttError_throws() = runTest {
        val f = discoveredFixture()
        f.bridge.readStatus = ATT_INSUFFICIENT_AUTHENTICATION

        assertFailsWith<EmulatorGattException> { f.engine.readCharacteristic(f.peripheral, f.characteristic(READ)) }
    }

    @Test
    fun readCharacteristic_withNoReadProperty_throwsAndReadsNothing() = runTest {
        val f = discoveredFixture()

        assertFailsWith<EmulatorGattException> { f.engine.readCharacteristic(f.peripheral, f.characteristic(WRITE)) }
        assertTrue(f.bridge.reads.isEmpty())
    }

    @Test
    fun writeCharacteristic_withTheWriteProperty_writesWithResponseForEachWriteType() = runTest {
        val f = discoveredFixture()
        val characteristic = f.characteristic(WRITE)

        f.engine.writeCharacteristic(f.peripheral, characteristic, byteArrayOf(1, 2), writeType = null)
        // 1 is WRITE_TYPE_NO_RESPONSE on Android. JsEngine ignores it.
        f.engine.writeCharacteristic(f.peripheral, characteristic, byteArrayOf(3), writeType = 1)

        assertEquals(listOf(true, true), f.bridge.writes.map { it.withResponse })
        assertContentEquals(byteArrayOf(1, 2), f.bridge.writes.first().value)
        assertContentEquals(byteArrayOf(3), characteristic.value)
    }

    @Test
    fun writeCharacteristic_withOnlyWriteWithoutResponse_writesWithoutResponse() = runTest {
        val f = discoveredFixture()

        val characteristic = f.characteristic(WRITE_WITHOUT_RESPONSE)

        f.engine.writeCharacteristic(f.peripheral, characteristic, byteArrayOf(1), writeType = null)

        assertFalse(f.bridge.writes.single().withResponse)
    }

    @Test
    fun writeCharacteristic_thatTheBoardRefuses_throws() = runTest {
        val f = discoveredFixture()
        f.bridge.writeStatus = ATT_INVALID_ATTRIBUTE_VALUE_LENGTH

        assertFailsWith<EmulatorGattException> {
            f.engine.writeCharacteristic(f.peripheral, f.characteristic(WRITE), ByteArray(63), writeType = null)
        }
    }

    @Test
    fun notifyCharacteristic_deliversEachNotificationUntilItStops() = runTest {
        val f = discoveredFixture()
        val characteristic = f.characteristic(NOTIFY)
        val engineNotifications = collected(f.engine.characteristicNotifications)
        val characteristicNotifications = collected(characteristic.notifications)

        f.engine.notifyCharacteristic(f.peripheral, characteristic, notify = true)
        f.bridge.notify(NOTIFY, byteArrayOf(7))
        f.engine.notifyCharacteristic(f.peripheral, characteristic, notify = false)
        f.bridge.notify(NOTIFY, byteArrayOf(8))

        assertEquals(listOf(NOTIFY to 1, NOTIFY to 0), f.bridge.subscriptions)
        val notification: CharacteristicNotification = engineNotifications.single()
        assertSame(f.peripheral, notification.peripheral)
        assertSame(characteristic, notification.characteristic)
        assertContentEquals(byteArrayOf(7), notification.value)
        assertContentEquals(byteArrayOf(7), characteristicNotifications.single())
        assertContentEquals(byteArrayOf(7), characteristic.value)
    }

    @Test
    fun indicateCharacteristic_onACharacteristicWithNoNotifyProperty_asksForIndications() = runTest {
        val f = discoveredFixture()

        f.engine.indicateCharacteristic(f.peripheral, f.characteristic(INDICATE), indicate = true)

        assertEquals(listOf(INDICATE to 2), f.bridge.subscriptions)
    }

    @Test
    fun notifyCharacteristic_thatTheBoardRefuses_throwsAndDeliversNothing() = runTest {
        val f = discoveredFixture()
        f.bridge.subscribeStatus = ATT_INSUFFICIENT_AUTHENTICATION
        val characteristic = f.characteristic(NOTIFY)
        val notifications = collected(f.engine.characteristicNotifications)

        assertFailsWith<EmulatorGattException> { f.engine.notifyCharacteristic(f.peripheral, characteristic, true) }
        f.bridge.notify(NOTIFY, byteArrayOf(7))

        assertTrue(notifications.isEmpty())
    }

    @Test
    fun notification_forACharacteristicWithNoSubscription_isDropped() = runTest {
        val f = discoveredFixture()
        val characteristic = f.characteristic(NOTIFY)
        val notifications = collected(f.engine.characteristicNotifications)

        f.bridge.notify(NOTIFY, byteArrayOf(7))

        assertTrue(notifications.isEmpty())
        assertNull(characteristic.value)
    }

    @Test
    fun readCharacteristic_withAnActiveListener_alsoDeliversTheValue() = runTest {
        val f = discoveredFixture()
        val characteristic = f.characteristic(NOTIFY)
        f.bridge.values[NOTIFY] = byteArrayOf(5)
        f.engine.notifyCharacteristic(f.peripheral, characteristic, notify = true)
        val notifications = collected(f.engine.characteristicNotifications)

        f.engine.readCharacteristic(f.peripheral, characteristic)

        // Chrome fires 'characteristicvaluechanged' for a read.
        assertContentEquals(byteArrayOf(5), notifications.single().value)
    }

    @Test
    fun disconnectedEvent_sendsDisconnectedAndClosesTheLink() = runTest {
        val f = discoveredFixture()
        val updates = collected(f.engine.connectionStateUpdates)

        f.bridge.connected = false
        f.bridge.emit(BoardEvent.Disconnected(reason = CONNECTION_TIMEOUT))

        assertEquals(listOf(BluetoothPeripheralState.Disconnected), updates.map(ConnectionStateUpdate::state))
        assertEquals(BluetoothPeripheralState.Disconnected, f.engine.connectionState(f.peripheral))
        assertFailsWith<EmulatorGattException> { f.engine.readCharacteristic(f.peripheral, f.characteristic(READ)) }
    }

    @Test
    fun disconnect_sendsOneDisconnectedUpdate() = runTest {
        val f = connectedFixture()
        val updates = collected(f.engine.connectionStateUpdates)

        // The fake bridge also sends a 'disconnected' event for this.
        f.engine.disconnect(f.peripheral)

        assertEquals(1, f.bridge.disconnectCalls)
        assertEquals(listOf(BluetoothPeripheralState.Disconnected), updates.map(ConnectionStateUpdate::state))
    }

    @Test
    fun characteristic_fromAnEarlierLink_isNoLongerValid() = runTest {
        val f = discoveredFixture()
        val oldService = f.peripheral.services.single()
        val oldCharacteristic = f.characteristic(READ)

        f.engine.disconnect(f.peripheral)
        f.engine.connect(f.peripheral)

        // As JsEngine, the peripheral keeps its services until the next discoverServices.
        assertSame(oldService, f.peripheral.services.single())
        val error = assertFailsWith<EmulatorGattException> {
            f.engine.readCharacteristic(f.peripheral, oldCharacteristic)
        }
        assertTrue(error.message!!.startsWith("InvalidStateError"))
    }

    @Test
    fun unsupportedCalls_throwAsInJsEngine() = runTest {
        val f = connectedFixture()

        assertFailsWith<UnsupportedOperationException> { f.engine.changeMTU(f.peripheral, 247) }
        assertFailsWith<UnsupportedOperationException> { f.engine.openL2capChannel(f.peripheral, 0x25, false) }
        assertFailsWith<UnsupportedOperationException> { f.engine.createBond(f.peripheral) }
        assertFalse(f.engine.refreshGattCache(f.peripheral))
    }

    private companion object {
        const val SERVICE = "00001825-0000-1000-8000-00805f9b34fb"
        const val HIDDEN_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"

        const val READ = 0x03
        const val WRITE = 0x05
        const val WRITE_WITHOUT_RESPONSE = 0x07
        const val NOTIFY = 0x09
        const val INDICATE = 0x0C

        const val ATT_INSUFFICIENT_AUTHENTICATION = 0x05
        const val ATT_INVALID_ATTRIBUTE_VALUE_LENGTH = 0x0D
        const val CONNECTION_TIMEOUT = 0x08

        val GATT = listOf(
            BoardService(
                uuid = SERVICE,
                characteristics = listOf(
                    BoardCharacteristic("00002abe-0000-1000-8000-00805f9b34fb", READ, GattProperty.READ, null),
                    BoardCharacteristic(
                        "00002ac7-0000-1000-8000-00805f9b34fb",
                        WRITE,
                        GattProperty.WRITE or GattProperty.WRITE_WITHOUT_RESPONSE,
                        null,
                    ),
                    BoardCharacteristic(
                        "00000002-5350-4221-0000-5350425f4f54",
                        WRITE_WITHOUT_RESPONSE,
                        GattProperty.WRITE_WITHOUT_RESPONSE,
                        null,
                    ),
                    BoardCharacteristic(
                        "00002ac8-0000-1000-8000-00805f9b34fb",
                        NOTIFY,
                        GattProperty.READ or GattProperty.NOTIFY or GattProperty.INDICATE,
                        NOTIFY + 1,
                    ),
                    BoardCharacteristic(
                        "00002ac5-0000-1000-8000-00805f9b34fb",
                        INDICATE,
                        GattProperty.WRITE or GattProperty.INDICATE,
                        INDICATE + 1,
                    ),
                ),
            ),
            BoardService(
                uuid = HIDDEN_SERVICE,
                characteristics = listOf(
                    BoardCharacteristic("00002a29-0000-1000-8000-00805f9b34fb", 0x20, GattProperty.READ, null),
                ),
            ),
        )
    }
}
