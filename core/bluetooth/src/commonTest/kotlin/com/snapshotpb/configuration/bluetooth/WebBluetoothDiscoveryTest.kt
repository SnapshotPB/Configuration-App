package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OtsCharacteristicMissing
import com.snapshotpb.configuration.bluetooth.ots.OtsClient
import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers an engine that supplies the services and the characteristics in two steps. BlueFalcon's
 * `JsEngine` fills only the service list in `discoverServices`, and it gets the characteristics of a
 * service only in `discoverCharacteristics`. `MacosJvmEngine` does the same. The shared
 * [DefaultBluetoothSeams] now asks for the OTS characteristics in `onServicesDiscovered`, so step 3
 * of the connect passes. Service discovery alone still gives an OTS service with no characteristics.
 *
 * The fake engine here has the discovery of `JsEngine` and sends no discovery event, so the seam
 * also stops its wait at the time limit. [FakeOtsBoard] answers the OTS session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebBluetoothDiscoveryTest {

    /** A service as `JsEngine` makes it: it has no characteristics until `discoverCharacteristics`. */
    private class WebService(override val uuid: Uuid) : BluetoothService {
        override val name: String? = null
        override var characteristics: List<BluetoothCharacteristic> = emptyList()
    }

    /** A peripheral as `JsEngine` makes it: it has no services until `discoverServices`. */
    private class WebPeripheral(override val uuid: String, override val name: String?) : BluetoothPeripheral {
        override val rssi: Float? = null
        override val mtuSize: Int? = null
        override var services: List<BluetoothService> = emptyList()
        override val characteristics: List<BluetoothCharacteristic>
            get() = services.flatMap { it.characteristics }
    }

    private class Fixture(
        val blueFalcon: BlueFalcon,
        val falconEngine: FakeBlueFalconEngine,
        val stateManager: BluetoothStateManager,
        val seams: DefaultBluetoothSeams,
        val peripheral: WebPeripheral,
    )

    /**
     * Gives the fake engine the discovery of `JsEngine`: `discoverServices` puts new services with
     * no characteristics on the peripheral, and `discoverCharacteristics` fills one service.
     */
    private fun TestScope.newFixture(): Fixture {
        // A `Device` with a 16-byte device_id (field 1, length 16): the read needs the board id.
        val board = FakeOtsBoard().apply { setContent("device", byteArrayOf(0x0A, 0x10) + BOARD_ID_BYTES) }
        val remote = board.peripheral.services.single()
        val falconEngine = FakeBlueFalconEngine(backgroundScope).apply {
            emitManagerState(BluetoothManagerState.Ready)
            // The link monitor polls this; Connected keeps the link up for the whole test.
            connectionStateResult = BluetoothPeripheralState.Connected
            onDiscoverServices = { peripheral ->
                (peripheral as WebPeripheral).services = listOf(WebService(remote.uuid))
            }
            onDiscoverCharacteristics = { _, service ->
                (service as WebService).characteristics = remote.characteristics
            }
        }
        board.install(falconEngine)
        val blueFalcon = BlueFalcon(falconEngine)
        val stateManager = BluetoothStateManager().apply { update(BluetoothState.Ready) }
        val seams = DefaultBluetoothSeams(blueFalcon, stateManager)
        val peripheral = WebPeripheral(uuid = PERIPHERAL_ID, name = "Snapshot PB")
        seams.addDevice(peripheral)
        return Fixture(blueFalcon, falconEngine, stateManager, seams, peripheral)
    }

    @Test
    fun connect_withWebBluetoothDiscovery_completesTheOtsStep() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val readBus = DeviceReadResultBus()
        val reads = mutableListOf<DeviceReadResult>()
        backgroundScope.launch { readBus.results.collect { reads += it } }
        val engine = BluetoothConnectionEngine(
            blueFalcon = f.blueFalcon,
            bluetoothStateManager = f.stateManager,
            bleDebugLog = BleDebugLog(),
            deviceReadResultBus = readBus,
            deviceWriteResultBus = DeviceWriteResultBus(),
            seams = f.seams,
            coroutineScope = backgroundScope,
        )

        engine.connect(DEVICE_ID)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()

        val progress = f.stateManager.connectionProgress.value
        assertEquals(StepStatus.COMPLETED, progress.step2, "the service discovery succeeds")
        assertEquals(StepStatus.COMPLETED, progress.step3, "the OTS step succeeds")
        assertEquals(1, reads.size)
        assertTrue(reads.single().device.id.toString() in f.stateManager.connectedDevices.value)
        // The seam asked one time for the characteristics of the OTS service.
        assertEquals(1, f.falconEngine.discoverCharacteristicsCalls.size)
        assertTrue(f.peripheral.services.single().characteristics.isNotEmpty())
        assertTrue(f.falconEngine.disconnectCalls.isEmpty())
    }

    @Test
    fun otsDiscover_afterTheSharedServiceDiscovery_throwsOtsCharacteristicMissing() = runTest {
        val f = newFixture()

        assertTrue(f.seams.awaitServicesDiscovered(f.peripheral))

        val error = assertFailsWith<OtsCharacteristicMissing> {
            OtsClient.discover(f.blueFalcon, f.peripheral) {}
        }
        // The first characteristic that the client needs is OACP.
        assertEquals("OTS characteristic ${OtsUuids.Oacp} not found", error.message)
    }

    @Test
    fun otsDiscover_afterOnServicesDiscovered_readsTheBoard() = runTest {
        val f = newFixture()
        assertTrue(f.seams.awaitServicesDiscovered(f.peripheral))

        // The shared seam asks for the characteristics after the service discovery.
        f.seams.onServicesDiscovered(f.peripheral)

        val client = assertNotNull(OtsClient.discover(f.blueFalcon, f.peripheral) {})
        assertEquals(listOf("device", "device_config", "profiles"), client.listObjects().map { it.name })
    }

    private companion object {
        const val PERIPHERAL_ID = "web-bluetooth-board"
        val DEVICE_ID = PERIPHERAL_ID.toDeviceUuid().toString()
        const val CONNECT_MILLIS = 20_000L
        val BOARD_ID_BYTES = ByteArray(16) { index -> (index + 1).toByte() }
    }
}
