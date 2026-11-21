package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.emulator.EmulatorEngine
import com.snapshotpb.configuration.bluetooth.emulator.FakeOtsBridgeBoard
import com.snapshotpb.configuration.bluetooth.log.BluetoothLog
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogEvent
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSnapshot
import com.snapshotpb.configuration.bluetooth.log.BoardLogState
import com.snapshotpb.configuration.bluetooth.ots.decodeDeviceConfig
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import dev.bluefalcon.core.BlueFalcon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private class BoardLogRecorder : BluetoothLog {
        val boardLogStates: MutableList<Pair<Int, BoardLogState>> = mutableListOf()
        override val snapshot: StateFlow<BluetoothLogSnapshot> = MutableStateFlow(BluetoothLogSnapshot())
        override val recordsBoardLines: Boolean = true
        override fun startSession(session: Int) = Unit
        override fun sessionBoard(session: Int, boardId: String, firmwareVersion: String?) = Unit
        override fun boardChunk(session: Int, bytes: ByteArray) = Unit
        override fun boardLogState(session: Int, state: BoardLogState) {
            boardLogStates += session to state
        }
        override fun app(session: Int, event: BluetoothLogEvent, detail: String?) = Unit
        override fun endSession(session: Int) = Unit
        override fun clear() = Unit
    }

    private class Fixture(
        val manager: BluetoothManager,
        val stateManager: BluetoothStateManager,
        val board: FakeOtsBridgeBoard,
        val log: BoardLogRecorder,
        val reads: List<DeviceReadResult>,
        val writes: List<DeviceWriteResult>,
    )

    private fun TestScope.newFixture(
        smpService: Boolean = false,
        eyeService: Boolean = false,
    ): Fixture {
        val board = FakeOtsBridgeBoard(smpService = smpService, eyeService = eyeService).apply {
            setContent("device", DEVICE_WITH_ID)
            setContent("device_config", BOOT_TEXT_HI)
            // The engine refuses a write with no profiles, so the board holds one profile.
            setContent("profiles", ONE_PROFILE)
        }
        val stateManager = BluetoothStateManager()
        val readBus = DeviceReadResultBus()
        val writeBus = DeviceWriteResultBus()
        val log = BoardLogRecorder()
        val reads = mutableListOf<DeviceReadResult>()
        val writes = mutableListOf<DeviceWriteResult>()
        backgroundScope.launch { readBus.results.collect { reads += it } }
        backgroundScope.launch { writeBus.results.collect { writes += it } }
        val manager = BluetoothManager(
            blueFalcon = BlueFalcon(EmulatorEngine(board.bridge, backgroundScope)),
            bluetoothStateManager = stateManager,
            bluetoothLog = log,
            deviceReadResultBus = readBus,
            deviceWriteResultBus = writeBus,
            coroutineScope = backgroundScope,
        )
        runCurrent()
        return Fixture(manager, stateManager, board, log, reads, writes)
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
    fun connect_toTheViewerBoard_recordsNoUpdateService() = runTest(UnconfinedTestDispatcher()) {
        // The model of the board in the viewer has no SMP service.
        val f = newFixture()

        scanAndConnect(f)

        assertEquals(BoardUpdateService(UpdateServiceStatus.ABSENT), f.stateManager.updateServices.value[BOARD_ID])
    }

    @Test
    fun connect_toTheViewerBoard_recordsAbsentBoardLogState() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        scanAndConnect(f)

        assertTrue(f.stateManager.connectionProgress.value.isCompleted)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(listOf(1 to BoardLogState.ABSENT), f.log.boardLogStates)
    }

    @Test
    fun connect_toABoardWithTheUpdateService_recordsAnUnsupportedPlatform() = runTest(UnconfinedTestDispatcher()) {
        // The scan filters put the SMP service in the allowlist, so the page gets the service. The
        // engine, as JsEngine, has no write without response with a result, so the first SMP
        // request stops with SmpWriteUnsupported, and the connect still succeeds.
        val f = newFixture(smpService = true)

        scanAndConnect(f)

        assertTrue(f.stateManager.connectionProgress.value.isCompleted)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(
            BoardUpdateService(UpdateServiceStatus.UNSUPPORTED_PLATFORM),
            f.stateManager.updateServices.value[BOARD_ID],
        )
        assertTrue(f.board.bridge.writes.none { it.handle == FakeOtsBridgeBoard.SMP }, "no SMP byte goes to the board")
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

    @Test
    fun eyeStatus_subscribesDeliversReadingAndUnsubscribesAfterDelay() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(eyeService = true)
        scanAndConnect(f)

        val states = mutableListOf<EyeReadingState?>()
        val watcher = backgroundScope.launch {
            f.stateManager.eyeReading(BOARD_ID).collect { states += it }
        }
        runCurrent()

        assertTrue((FakeOtsBridgeBoard.EYE_STATUS to 1) in f.board.bridge.subscriptions)

        f.board.bridge.notify(FakeOtsBridgeBoard.EYE_STATUS, EYE_STATUS_ACTIVE_1000)
        runCurrent()
        assertEquals(
            EyeReadingState.Reading(EyeReading(levelRaw = 1000, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE)),
            states.last(),
        )

        watcher.cancelAndJoin()
        advanceTimeBy(EYE_UNSUBSCRIBE_DELAY_MILLIS)
        runCurrent()
        assertTrue((FakeOtsBridgeBoard.EYE_STATUS to 0) in f.board.bridge.subscriptions)
    }

    @Test
    fun connect_withoutEyeService_recordsUnsupportedAndDoesNotSubscribe() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(eyeService = false)

        scanAndConnect(f)

        assertTrue(f.stateManager.connectionProgress.value.isCompleted)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)

        val states = mutableListOf<EyeReadingState?>()
        val watcher = backgroundScope.launch {
            f.stateManager.eyeReading(BOARD_ID).collect { states += it }
        }
        runCurrent()

        assertEquals(EyeReadingState.Unsupported, states.last())
        assertTrue(f.board.bridge.subscriptions.none { (handle, _) -> handle == FakeOtsBridgeBoard.EYE_STATUS })
        watcher.cancelAndJoin()
    }

    @Test
    fun eyeStatus_whenSubscribeFails_recordsFailedAndKeepsConnection() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(eyeService = true)
        scanAndConnect(f)
        f.board.bridge.subscribeStatus = ATT_UNLIKELY_ERROR

        val states = mutableListOf<EyeReadingState?>()
        val watcher = backgroundScope.launch {
            f.stateManager.eyeReading(BOARD_ID).collect { states += it }
        }
        runCurrent()

        assertEquals(EyeReadingState.Failed, states.last())
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        watcher.cancelAndJoin()
    }

    private companion object {
        const val CONNECT_MILLIS = 20_000L
        const val WRITE_MILLIS = 20_000L
        const val EYE_UNSUBSCRIBE_DELAY_MILLIS = 2_000L
        const val ATT_UNLIKELY_ERROR = 0x0E

        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"

        /** An `EyeStatus` with `level_raw = 1000`, `full_scale_raw = 4095`, `state = ACTIVE`. */
        val EYE_STATUS_ACTIVE_1000 = "08e80710ff1f1801".hexToByteArray()

        /** A `Device` with the 16-byte `device_id` [BOARD_ID]: field 1, length 16. */
        val DEVICE_WITH_ID = byteArrayOf(0x0A, 0x10) + Uuid.parse(BOARD_ID).toByteArray()

        /** A `DeviceConfig` with the boot text "HI": field 2, length 2. */
        val BOOT_TEXT_HI = byteArrayOf(0x12, 0x02, 0x48, 0x49)

        /** A `Profiles` with one `Profile`, which has the name "P1": field 1 in field 1. */
        val ONE_PROFILE = byteArrayOf(0x0A, 0x04, 0x0A, 0x02, 0x50, 0x31)
    }
}
