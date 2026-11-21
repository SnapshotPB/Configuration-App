package com.snapshotpb.configuration.bluetooth

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheralState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import snapshotpb.autococker.v1.AutocockerConfig
import snapshotpb.autococker.v1.AutocockerFireMode
import snapshotpb.v1.BoardModel
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.Profile
import snapshotpb.v1.Profiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Engine tests for Bluetooth log sessions across connect, disconnect, failures, and link loss. */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionEngineBoardLogTest {

    private class Fixture(
        val engine: BluetoothConnectionEngine,
        val stateManager: BluetoothStateManager,
        val falconEngine: FakeBlueFalconEngine,
        val board: FakeOtsBoard,
        val seams: FakePlatformBluetoothSeams,
        val log: RecordingBluetoothLog,
    )

    private fun newBoard(
        peripheralUuid: String,
        boardDevice: snapshotpb.v1.Device,
    ): FakeOtsBoard = FakeOtsBoard(peripheralUuid = peripheralUuid).apply {
        setContent("device", ProtoBuf.encodeToByteArray(snapshotpb.v1.Device.serializer(), boardDevice))
        setContent("device_config", ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), BOARD_CONFIG))
        setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), BOARD_PROFILES))
    }

    private fun TestScope.newFixture(): Fixture {
        val falconEngine = FakeBlueFalconEngine(backgroundScope).apply {
            emitManagerState(BluetoothManagerState.Ready)
            connectionStateResult = BluetoothPeripheralState.Connected
        }
        val board = newBoard(peripheralUuid = "board-1", boardDevice = BOARD_DEVICE).apply {
            install(falconEngine)
        }
        val seams = FakePlatformBluetoothSeams().apply {
            setDevices(mapOf(DEVICE_ID to board.peripheral))
        }
        val stateManager = BluetoothStateManager().apply { update(BluetoothState.Ready) }
        val log = RecordingBluetoothLog()
        val engine = BluetoothConnectionEngine(
            blueFalcon = BlueFalcon(falconEngine),
            bluetoothStateManager = stateManager,
            bleDebugLog = BleDebugLog(),
            bluetoothLog = log,
            deviceReadResultBus = DeviceReadResultBus(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            seams = seams,
            coroutineScope = backgroundScope,
        )
        return Fixture(engine, stateManager, falconEngine, board, seams, log)
    }

    private fun TestScope.connectComplete(f: Fixture, deviceId: String = DEVICE_ID) {
        f.engine.connect(deviceId)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()
    }

    @Test
    fun connect_startsSessionBeforeServiceDiscovery() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.seams.awaitServicesGate = CompletableDeferred()

        f.engine.connect(DEVICE_ID)
        runCurrent()

        assertEquals(listOf("startSession(1)"), f.log.calls)
    }

    @Test
    fun connect_recordsBoardHeaderAndDisconnectEndsSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        connectComplete(f)

        assertEquals(
            listOf("startSession(1)", "sessionBoard(1, $BOARD_ID, null)"),
            f.log.calls,
        )

        f.engine.disconnect(DEVICE_ID)
        runCurrent()

        assertEquals(
            listOf("startSession(1)", "sessionBoard(1, $BOARD_ID, null)", "endSession(1)"),
            f.log.calls,
        )
    }

    @Test
    fun connect_whenInitialReadFails_endsSessionWithoutBoardHeader() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.board.failReadOf = "profiles"

        connectComplete(f)

        assertEquals("startSession(1)", f.log.calls.first())
        assertEquals("endSession(1)", f.log.calls.last())
        assertTrue(f.log.calls.none { it.startsWith("sessionBoard") })
    }

    @Test
    fun connect_whenServicesNotDiscovered_startsAndEndsSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.seams.awaitServicesResult = false

        connectComplete(f)

        assertEquals(listOf("startSession(1)", "endSession(1)"), f.log.calls)
    }

    @Test
    fun connect_whenPeripheralNeverFound_startsAndEndsSessionAfterScanTimeout() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture()
            f.seams.setDevices(emptyMap())

            f.engine.connect(DEVICE_ID)
            advanceTimeBy(SCAN_TIMEOUT_MILLIS)
            runCurrent()

            assertEquals(listOf("startSession(1)", "endSession(1)"), f.log.calls)
        }

    @Test
    fun connect_whenRefused_startsNoSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.seams.awaitReadyResult = false

        f.engine.connect(DEVICE_ID)
        runCurrent()

        assertTrue(f.log.calls.isEmpty())
    }

    @Test
    fun connect_twiceWithDisconnectBetween_usesSession1ThenSession2() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        connectComplete(f)
        f.engine.disconnect(DEVICE_ID)
        runCurrent()
        connectComplete(f)

        assertEquals(
            listOf(
                "startSession(1)",
                "sessionBoard(1, $BOARD_ID, null)",
                "endSession(1)",
                "startSession(2)",
                "sessionBoard(2, $BOARD_ID, null)",
            ),
            f.log.calls,
        )
    }

    @Test
    fun connect_whenCancelledDuringServicesWait_endsSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.seams.awaitServicesGate = CompletableDeferred()

        f.engine.connect(DEVICE_ID)
        runCurrent()
        assertEquals(listOf("startSession(1)"), f.log.calls)

        f.engine.disconnect(DEVICE_ID)
        runCurrent()

        assertTrue("endSession(1)" in f.log.calls)
        assertEquals("endSession(1)", f.log.calls.last())
    }

    @Test
    fun connect_liveLinkReread_keepsSameSessionWithoutEndSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        connectComplete(f)
        connectComplete(f)

        assertEquals(1, f.log.calls.count { it.startsWith("startSession") })
        assertTrue(f.log.calls.none { it.startsWith("endSession") })
    }

    @Test
    fun linkLoss_endsSessionAfterMonitorInterval() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connectComplete(f)

        f.falconEngine.connectionStateResult = BluetoothPeripheralState.Disconnected
        advanceTimeBy(MONITOR_WAIT_MILLIS)
        runCurrent()

        assertEquals(
            listOf("startSession(1)", "sessionBoard(1, $BOARD_ID, null)", "endSession(1)"),
            f.log.calls,
        )
    }

    @Test
    fun connect_secondBoardWhileFirstConnected_startsSession2AndEndsSession1() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture()
            connectComplete(f)

            val secondBoard = newBoard(peripheralUuid = "board-2", boardDevice = SECOND_BOARD_DEVICE).apply {
                install(f.falconEngine)
            }
            f.seams.setDevices(
                mapOf(
                    DEVICE_ID to f.board.peripheral,
                    SECOND_DEVICE_ID to secondBoard.peripheral,
                ),
            )

            connectComplete(f, SECOND_DEVICE_ID)

            assertEquals(
                listOf(
                    "startSession(1)",
                    "sessionBoard(1, $BOARD_ID, null)",
                    "startSession(2)",
                    "endSession(1)",
                    "sessionBoard(2, $SECOND_BOARD_ID, null)",
                ),
                f.log.calls,
            )
        }

    private companion object {
        const val DEVICE_ID = "5350425f-4f54-4000-8000-000000000001"
        const val SECOND_DEVICE_ID = "5350425f-4f54-4000-8000-000000000002"
        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val SECOND_BOARD_ID = "11223344-5566-7788-99aa-bbccddeeff00"

        const val CONNECT_MILLIS = 20_000L
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val MONITOR_WAIT_MILLIS = 3_000L

        val BOARD_DEVICE = snapshotpb.v1.Device(
            deviceId = Uuid.parse(BOARD_ID).toByteArray(),
            model = BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 4u,
        )

        val SECOND_BOARD_DEVICE = snapshotpb.v1.Device(
            deviceId = Uuid.parse(SECOND_BOARD_ID).toByteArray(),
            model = BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 4u,
        )

        val BOARD_CONFIG = DeviceConfig(activeProfile = 1u, bootText = "HELLO")

        val BOARD_PROFILES = Profiles(
            profiles = listOf(
                Profile(
                    name = "P0",
                    boardAutoOff = 11u,
                    autococker = AutocockerConfig(fireMode = AutocockerFireMode.AUTOCOCKER_FIRE_MODE_SEMI),
                ),
                Profile(
                    name = "P1",
                    boardAutoOff = 22u,
                    autococker = AutocockerConfig(fireMode = AutocockerFireMode.AUTOCOCKER_FIRE_MODE_FULL_AUTO),
                ),
            ),
        )
    }
}
