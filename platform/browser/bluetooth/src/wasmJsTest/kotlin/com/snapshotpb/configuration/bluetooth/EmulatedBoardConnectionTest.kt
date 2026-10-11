package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.emulator.EmulatorEngine
import com.snapshotpb.configuration.bluetooth.emulator.FakeOtsBridgeBoard
import com.snapshotpb.configuration.bluetooth.ots.decodeDeviceConfig
import dev.bluefalcon.core.BlueFalcon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Runs the browser [BluetoothManager] (its seam, the shared connection engine and the OTS client)
 * on [EmulatorEngine] and an OTS board behind a fake bridge. This is the path of the web app in the
 * 3D viewer, and it is also the path of the web app on Web Bluetooth, because the engine copies the
 * discovery of `JsEngine`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmulatedBoardConnectionTest {

    private class Fixture(
        val manager: BluetoothManager,
        val stateManager: BluetoothStateManager,
        val board: FakeOtsBridgeBoard,
        val reads: List<DeviceReadResult>,
        val writes: List<DeviceWriteResult>,
    )

    private fun TestScope.newFixture(): Fixture {
        val board = FakeOtsBridgeBoard().apply {
            setContent("device", DEVICE_WITH_ID)
            setContent("device_config", BOOT_TEXT_HI)
            // The engine refuses a write with no profiles, so the board holds one profile.
            setContent("profiles", ONE_PROFILE)
        }
        val stateManager = BluetoothStateManager()
        val readBus = DeviceReadResultBus()
        val writeBus = DeviceWriteResultBus()
        val reads = mutableListOf<DeviceReadResult>()
        val writes = mutableListOf<DeviceWriteResult>()
        backgroundScope.launch { readBus.results.collect { reads += it } }
        backgroundScope.launch { writeBus.results.collect { writes += it } }
        val manager = BluetoothManager(
            blueFalcon = BlueFalcon(EmulatorEngine(board.bridge, backgroundScope)),
            bluetoothStateManager = stateManager,
            bleDebugLog = BleDebugLog(),
            deviceReadResultBus = readBus,
            deviceWriteResultBus = writeBus,
            coroutineScope = backgroundScope,
        )
        runCurrent()
        return Fixture(manager, stateManager, board, reads, writes)
    }

    /** Scans, starts the connect, and lets it run to its end. Returns the device ID. */
    private suspend fun TestScope.scanAndConnect(f: Fixture): String {
        f.manager.startScanning()
        val deviceId = f.manager.deviceState.value.devices.keys.single()
        f.manager.connect(deviceId)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()
        return deviceId
    }

    @Test
    fun connect_readsTheBoardInFourSteps() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        val deviceId = scanAndConnect(f)

        val progress = f.stateManager.connectionProgress.value
        assertTrue(progress.isCompleted, "connection progress: $progress")
        // The board device_id is the identity (#127); the scan key stays the key of the session.
        val read = f.reads.single()
        assertEquals(BOARD_ID, read.device.id.toString())
        assertEquals(deviceId, read.scanKey)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals("HI", read.device.bootText)
    }

    @Test
    fun writeDeviceConfiguration_storesTheBootTextOnTheBoard() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val deviceId = scanAndConnect(f)
        val device = f.reads.single().device

        launch { f.manager.writeDeviceConfiguration(deviceId, device.copy(bootText = "HELLO"), requestId = 7L) }
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        val result = f.writes.single()
        assertNull(result.error)
        assertEquals(7L, result.requestId)
        assertEquals("HELLO", result.device.bootText)
        assertEquals(listOf("device_config", "profiles"), f.board.writtenObjects)
        assertEquals("HELLO", decodeDeviceConfig(f.board.content("device_config")).bootText)
    }

    private companion object {
        const val CONNECT_MILLIS = 20_000L
        const val WRITE_MILLIS = 20_000L

        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"

        /** A `Device` with the 16-byte `device_id` [BOARD_ID]: field 1, length 16. */
        val DEVICE_WITH_ID = byteArrayOf(0x0A, 0x10) + Uuid.parse(BOARD_ID).toByteArray()

        /** A `DeviceConfig` with the boot text "HI": field 2, length 2. */
        val BOOT_TEXT_HI = byteArrayOf(0x12, 0x02, 0x48, 0x49)

        /** A `Profiles` with one `Profile`, which has the name "P1": field 1 in field 1. */
        val ONE_PROFILE = byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x50, 0x31)
    }
}
