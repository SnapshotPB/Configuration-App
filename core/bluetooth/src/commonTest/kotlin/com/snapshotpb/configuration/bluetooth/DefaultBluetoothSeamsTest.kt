package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.ServiceDiscoveryPhase
import dev.bluefalcon.core.ServiceDiscoveryUpdate
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the shared [DefaultBluetoothSeams] behavior (the desktop seam and the base for the others). */
class DefaultBluetoothSeamsTest {

    private fun TestScope.newSeams(): Pair<DefaultBluetoothSeams, BluetoothStateManager> {
        val stateManager = BluetoothStateManager()
        val seams = DefaultBluetoothSeams(BlueFalcon(FakeBlueFalconEngine(backgroundScope)), stateManager)
        return seams to stateManager
    }

    @Test
    fun onManagerState_readyFromDisabled_becomesReady() = runTest {
        val (seams, stateManager) = newSeams()
        stateManager.update(BluetoothState.Disabled)

        seams.onManagerState(BluetoothManagerState.Ready)

        assertEquals(BluetoothState.Ready, stateManager.bluetoothState.value)
    }

    @Test
    fun onManagerState_readyWhileScanning_isUnchanged() = runTest {
        val (seams, stateManager) = newSeams()
        stateManager.update(BluetoothState.Scanning)

        seams.onManagerState(BluetoothManagerState.Ready)

        assertEquals(BluetoothState.Scanning, stateManager.bluetoothState.value)
    }

    @Test
    fun onManagerState_notReady_becomesDisabled() = runTest {
        val (seams, stateManager) = newSeams()
        stateManager.update(BluetoothState.Ready)

        seams.onManagerState(BluetoothManagerState.NotReady)

        assertEquals(BluetoothState.Disabled, stateManager.bluetoothState.value)
    }

    @Test
    fun addDevice_addsThenUpdatesCachedName() = runTest {
        val (seams, _) = newSeams()
        val deviceId = "00:11:22:33:44:55".toDeviceUuid().toString()

        seams.addDevice(FakeBluetoothPeripheral(uuid = "00:11:22:33:44:55", name = null))
        assertTrue(seams.deviceState.value.devices.containsKey(deviceId))

        seams.addDevice(FakeBluetoothPeripheral(uuid = "00:11:22:33:44:55", name = "Board"))
        assertEquals("Board", seams.deviceState.value.devices[deviceId]?.name)
    }

    @Test
    fun formatDebugBytes_rendersHex() = runTest {
        val (seams, _) = newSeams()
        val event = BleDebugEvent(
            timestampMillis = 0L,
            deviceId = "dev",
            characteristicUuid = "char",
            bytes = byteArrayOf(0x0A, 0xFF.toByte())
        )

        assertEquals("0a ff", seams.formatDebugBytes(event))
    }

    @Test
    fun awaitServicesDiscovered_trueWhenServicesPresentOnAnOpenLink() = runTest {
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
            .apply { connectionStateResult = BluetoothPeripheralState.Connected }
        val seams = DefaultBluetoothSeams(BlueFalcon(falconEngine), BluetoothStateManager())
        val peripheral = FakeBluetoothPeripheral(uuid = "p1", services = listOf(FakeBluetoothService()))

        assertTrue(seams.awaitServicesDiscovered(peripheral))
    }

    @Test
    fun awaitServicesDiscovered_ignoresServicesFromAnEarlierLink() = runTest {
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
            .apply { connectionStateResult = BluetoothPeripheralState.Connecting }
        val seams = DefaultBluetoothSeams(BlueFalcon(falconEngine), BluetoothStateManager())
        val peripheral = FakeBluetoothPeripheral(uuid = "p1", services = listOf(FakeBluetoothService()))

        // The link never opens, so the old services do not give a result of true.
        assertFalse(seams.awaitServicesDiscovered(peripheral))
    }

    @Test
    fun awaitServicesDiscovered_falseWhenNoServicesAppear() = runTest {
        val (seams, _) = newSeams()
        val peripheral = FakeBluetoothPeripheral(uuid = "p1") // services stay empty

        // The loop waits for the link, uses all its retries in virtual time, then reports no services.
        assertFalse(seams.awaitServicesDiscovered(peripheral))
    }

    @Test
    fun awaitServicesDiscovered_waitsForTheLinkBeforeItDiscovers() = runTest {
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
        val discovery = CoreBluetoothLikeDiscovery(falconEngine)
        val seams = DefaultBluetoothSeams(BlueFalcon(falconEngine), BluetoothStateManager())
        val peripheral = MutablePeripheral()
        // As MacosJvmEngine: connect() returned, and the link opens later.
        falconEngine.connectionStateResult = BluetoothPeripheralState.Connecting
        launch {
            delay(1_000)
            falconEngine.connectionStateResult = BluetoothPeripheralState.Connected
        }

        assertTrue(seams.awaitServicesDiscovered(peripheral))
        // One request, and it came after the link opened.
        assertEquals(1, discovery.calls)
        assertEquals(1, discovery.accepted)
    }

    @Test
    fun awaitServicesDiscovered_doesNotDiscoverWhileTheLinkIsDown() = runTest {
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
        val discovery = CoreBluetoothLikeDiscovery(falconEngine)
        val seams = DefaultBluetoothSeams(BlueFalcon(falconEngine), BluetoothStateManager())
        falconEngine.connectionStateResult = BluetoothPeripheralState.Connecting

        assertFalse(seams.awaitServicesDiscovered(MutablePeripheral()))
        assertEquals(0, discovery.calls)
    }

    @Test
    fun onServicesDiscovered_waitsForTheEventAfterTheLastCharacteristic() = runTest {
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
        val seams = DefaultBluetoothSeams(BlueFalcon(falconEngine), BluetoothStateManager())
        val service = MutableService(OtsUuids.Service)
        val peripheral = MutablePeripheral().apply { services = listOf(service) }
        // As MacosJvmEngine: the characteristics arrive one at a time, and the event comes last.
        falconEngine.onDiscoverCharacteristics = { discovered, _ ->
            service.characteristics = listOf(FakeOtsCharacteristic(OtsUuids.Oacp))
            launch {
                delay(500)
                service.characteristics += FakeOtsCharacteristic(OtsUuids.Olcp)
                falconEngine.serviceDiscoveryEvents.emit(
                    ServiceDiscoveryUpdate(discovered, ServiceDiscoveryPhase.CharacteristicsDiscovered, service)
                )
            }
        }

        seams.onServicesDiscovered(peripheral)

        assertEquals(listOf(OtsUuids.Oacp, OtsUuids.Olcp), service.characteristics.map { it.uuid })
        assertEquals(1, falconEngine.discoverCharacteristicsCalls.size)
    }

    @Test
    fun onServicesDiscovered_doesNothingWhenTheEngineSuppliedTheCharacteristics() = runTest {
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
        val seams = DefaultBluetoothSeams(BlueFalcon(falconEngine), BluetoothStateManager())
        val service = MutableService(OtsUuids.Service).apply {
            characteristics = listOf(FakeOtsCharacteristic(OtsUuids.Oacp))
        }

        seams.onServicesDiscovered(MutablePeripheral().apply { services = listOf(service) })

        assertTrue(falconEngine.discoverCharacteristicsCalls.isEmpty())
    }

    @Test
    fun onServicesDiscovered_stopsAtTheTimeLimitWhenNoEventComes() = runTest {
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
        val seams = DefaultBluetoothSeams(BlueFalcon(falconEngine), BluetoothStateManager())
        val service = MutableService(OtsUuids.Service)

        seams.onServicesDiscovered(MutablePeripheral().apply { services = listOf(service) })

        assertEquals(1, falconEngine.discoverCharacteristicsCalls.size)
        assertTrue(service.characteristics.isEmpty())
    }

    @Test
    fun awaitReadyToConnect_trueWhenTheRadioIsReady() = runTest {
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
            .apply { emitManagerState(BluetoothManagerState.Ready) }
        val seams = DefaultBluetoothSeams(BlueFalcon(falconEngine), BluetoothStateManager())

        assertTrue(seams.awaitReadyToConnect("dev"))
    }

    @Test
    fun awaitReadyToConnect_refusesAndFailsStep1WhenTheRadioIsOff() = runTest {
        // FakeBlueFalconEngine starts NotReady: the radio is off.
        val (seams, stateManager) = newSeams()
        stateManager.update(BluetoothState.Connecting("dev"))

        assertFalse(seams.awaitReadyToConnect("dev"))

        // L14: the card shows a failure, and the state follows the radio instead of Ready.
        assertEquals(StepStatus.FAILED, stateManager.connectionProgress.value.step1)
        assertEquals(BluetoothState.Disabled, stateManager.bluetoothState.value)
    }

    /** A service whose characteristics a test fills. */
    private class MutableService(override val uuid: Uuid) : BluetoothService {
        override val name: String? = null
        override var characteristics: List<BluetoothCharacteristic> = emptyList()
    }

    /** A peripheral whose services the fake engine fills on discovery. */
    private class MutablePeripheral : BluetoothPeripheral {
        override val uuid: String = "p1"
        override val name: String? = null
        override val rssi: Float? = null
        override val mtuSize: Int? = null
        override var services: List<BluetoothService> = emptyList()
        override val characteristics: List<BluetoothCharacteristic> = emptyList()
    }

    /**
     * Gives the fake engine the discovery of CoreBluetooth: a request while the link is not open is
     * lost. [calls] counts every request. [accepted] counts the requests on an open link, which fill
     * the services.
     */
    private class CoreBluetoothLikeDiscovery(val engine: FakeBlueFalconEngine) {
        var calls = 0
            private set
        var accepted = 0
            private set

        init {
            engine.onDiscoverServices = { peripheral ->
                calls++
                if (engine.connectionStateResult == BluetoothPeripheralState.Connected) {
                    accepted++
                    (peripheral as MutablePeripheral).services = listOf(FakeBluetoothService())
                }
            }
        }
    }
}