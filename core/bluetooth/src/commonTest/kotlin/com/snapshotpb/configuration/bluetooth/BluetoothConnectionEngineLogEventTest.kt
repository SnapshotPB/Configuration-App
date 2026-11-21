package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.log.BluetoothLog
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogImpl
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Engine tests for connect, disconnect, and link-loss events in the Bluetooth log. */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionEngineLogEventTest {

    private class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(scheduler.currentTime)
    }

    private class Fixture<L : BluetoothLog>(
        val engine: BluetoothConnectionEngine,
        val stateManager: BluetoothStateManager,
        val falconEngine: FakeBlueFalconEngine,
        val board: FakeOtsBoard,
        val seams: FakePlatformBluetoothSeams,
        val log: L,
    )

    private fun newBoard(
        peripheralUuid: String,
        boardDevice: snapshotpb.v1.Device,
        extraServices: List<BluetoothService> = emptyList(),
    ): FakeOtsBoard = FakeOtsBoard(
        peripheralUuid = peripheralUuid,
        extraServices = extraServices,
    ).apply {
        setContent("device", ProtoBuf.encodeToByteArray(snapshotpb.v1.Device.serializer(), boardDevice))
        setContent("device_config", ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), BOARD_CONFIG))
        setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), BOARD_PROFILES))
    }

    private fun <L : BluetoothLog> TestScope.newFixture(
        log: L,
        extraServices: List<BluetoothService> = emptyList(),
    ): Fixture<L> {
        val falconEngine = FakeBlueFalconEngine(backgroundScope).apply {
            emitManagerState(BluetoothManagerState.Ready)
            connectionStateResult = BluetoothPeripheralState.Connected
        }
        val board = newBoard(
            peripheralUuid = "board-1",
            boardDevice = BOARD_DEVICE,
            extraServices = extraServices,
        ).apply {
            install(falconEngine)
        }
        val seams = FakePlatformBluetoothSeams().apply {
            setDevices(mapOf(DEVICE_ID to board.peripheral))
        }
        val stateManager = BluetoothStateManager().apply { update(BluetoothState.Ready) }
        val engine = BluetoothConnectionEngine(
            blueFalcon = BlueFalcon(falconEngine),
            bluetoothStateManager = stateManager,
            bluetoothLog = log,
            deviceReadResultBus = DeviceReadResultBus(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            seams = seams,
            coroutineScope = backgroundScope,
        )
        return Fixture(engine, stateManager, falconEngine, board, seams, log)
    }

    private fun TestScope.connectComplete(f: Fixture<*>, deviceId: String = DEVICE_ID) {
        f.engine.connect(deviceId)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()
    }

    private fun assertOrder(calls: List<String>, vararg expected: String) {
        var previousIndex = -1
        for (item in expected) {
            val index = calls.indexOf(item)
            assertTrue(index > previousIndex, "Expected '$item' after index $previousIndex in $calls")
            previousIndex = index
        }
    }

    @Test
    fun connect_success_logsEventsInOrderOnce() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())

        connectComplete(f)

        val expected = listOf(
            "app(0, CONNECT_REQUESTED)",
            "startSession(1)",
            "app(1, CONNECT_ATTEMPT, 1/3)",
            "app(1, SERVICES_FOUND)",
            "app(1, OTS_READY)",
        )
        assertOrder(f.log.calls, *expected.toTypedArray())
        for (item in expected) {
            assertEquals(1, f.log.calls.count { it == item }, "Expected one '$item' in ${f.log.calls}")
        }
    }

    @Test
    fun connect_twoFailedAttemptsThenThirdSucceeds_logsThreeAttempts() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        f.seams.awaitServicesResults.addAll(listOf(false, false, true))

        connectComplete(f)

        val attempts = f.log.calls.filter { "CONNECT_ATTEMPT" in it }
        assertEquals(
            listOf(
                "app(1, CONNECT_ATTEMPT, 1/3)",
                "app(1, CONNECT_ATTEMPT, 2/3)",
                "app(1, CONNECT_ATTEMPT, 3/3)",
            ),
            attempts,
        )
    }

    @Test
    fun connect_refused_logsBluetoothNotReadyWithNoSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        f.seams.awaitReadyResult = false

        f.engine.connect(DEVICE_ID)
        runCurrent()

        assertTrue("app(0, BLUETOOTH_NOT_READY)" in f.log.calls)
        assertTrue(f.log.calls.none { it.startsWith("startSession") })
        assertTrue(f.log.calls.none { it.startsWith("endSession") })
        assertTrue(f.log.calls.none { "CONNECT_FAILED" in it })
    }

    @Test
    fun connect_servicesNotDiscovered_logsServicesNotFoundThenStep2BeforeEndSession() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture(RecordingBluetoothLog())
            f.seams.awaitServicesResult = false

            connectComplete(f)

            assertOrder(
                f.log.calls,
                "app(1, SERVICES_NOT_FOUND)",
                "app(1, CONNECT_FAILED, step2)",
                "endSession(1)",
            )
        }

    @Test
    fun connect_peripheralNeverFound_logsNotFoundBeforeEndSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        f.seams.setDevices(emptyMap())

        f.engine.connect(DEVICE_ID)
        advanceTimeBy(SCAN_TIMEOUT_MILLIS)
        runCurrent()

        assertOrder(
            f.log.calls,
            "app(1, CONNECT_FAILED, not_found)",
            "endSession(1)",
        )
    }

    @Test
    fun connect_noOtsService_logsStep3BeforeEndSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        val plainPeripheral = FakeBluetoothPeripheral(
            uuid = "no-ots",
            services = listOf(FakeBluetoothService()),
        )
        f.seams.setDevices(mapOf(DEVICE_ID to plainPeripheral))

        connectComplete(f)

        assertOrder(
            f.log.calls,
            "app(1, CONNECT_FAILED, step3)",
            "endSession(1)",
        )
    }

    @Test
    fun connect_initialReadFails_logsFailureClassNameBeforeEndSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        f.board.failReadOf = "profiles"

        connectComplete(f)

        val failureCode = f.stateManager.connectionProgress.value.failureCode
        assertEquals("OacpFailure", failureCode)
        assertOrder(
            f.log.calls,
            "app(1, CONNECT_FAILED, $failureCode)",
            "endSession(1)",
        )
    }

    @Test
    fun connect_differentBoardAnswers_logsDifferentBoard() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        f.stateManager.recordBoardId(DEVICE_ID, SECOND_BOARD_ID)

        connectComplete(f, SECOND_BOARD_ID)

        assertOrder(
            f.log.calls,
            "app(1, CONNECT_FAILED, DifferentBoard)",
            "endSession(1)",
        )
    }

    @Test
    fun connect_cancelled_logsConnectCancelledBeforeEndSessionAndNoConnectFailed() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture(RecordingBluetoothLog())
            f.seams.awaitServicesGate = CompletableDeferred()

            f.engine.connect(DEVICE_ID)
            runCurrent()

            f.engine.disconnect(DEVICE_ID)
            runCurrent()

            assertOrder(
                f.log.calls,
                "app(1, CONNECT_CANCELLED)",
                "endSession(1)",
            )
            assertTrue(f.log.calls.none { "CONNECT_FAILED" in it })
        }

    @Test
    fun connect_liveReread_logsLiveRereadAndNoSecondAttempt() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        connectComplete(f)
        val firstConnectCount = f.log.calls.size

        connectComplete(f)

        val secondConnectCalls = f.log.calls.drop(firstConnectCount)
        assertTrue("app(1, LIVE_REREAD)" in secondConnectCalls)
        assertTrue(secondConnectCalls.none { "CONNECT_ATTEMPT" in it })
    }

    @Test
    fun disconnect_logsDisconnectRequestedBeforeEndSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        connectComplete(f)

        f.engine.disconnect(DEVICE_ID)
        runCurrent()

        assertOrder(
            f.log.calls,
            "app(0, DISCONNECT_REQUESTED)",
            "endSession(1)",
        )
    }

    @Test
    fun linkLoss_logsLinkLostBeforeEndSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(RecordingBluetoothLog())
        connectComplete(f)

        f.falconEngine.connectionStateResult = BluetoothPeripheralState.Disconnected
        advanceTimeBy(MONITOR_WAIT_MILLIS)
        runCurrent()

        assertOrder(
            f.log.calls,
            "app(1, LINK_LOST)",
            "endSession(1)",
        )
    }

    @Test
    fun connect_initialReadFailsWithMessage_omitsMessageBoardIdAndScanKey() = runTest(UnconfinedTestDispatcher()) {
        val log = BluetoothLogImpl(backgroundScope, TestClock(testScheduler))
        val f = newFixture(log)
        val failingPeripheral = object : BluetoothPeripheral by f.board.peripheral {
            override val name: String? get() = throw IllegalStateException("MARKER-1234")
        }
        f.seams.setDevices(mapOf(DEVICE_ID to failingPeripheral))

        connectComplete(f)
        advanceTimeBy(PUBLISH_WAIT_MILLIS)
        runCurrent()

        val texts = log.snapshot.value.entries.map { it.text }
        assertTrue("connect.failed IllegalStateException" in texts, "Expected failure entry in $texts")
        assertFalse(texts.any { "MARKER" in it || BOARD_ID in it || DEVICE_ID in it })
    }

    private companion object {
        const val DEVICE_ID = "5350425f-4f54-4000-8000-000000000001"
        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val SECOND_BOARD_ID = "11223344-5566-7788-99aa-bbccddeeff00"

        const val CONNECT_MILLIS = 20_000L
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val MONITOR_WAIT_MILLIS = 3_000L
        const val PUBLISH_WAIT_MILLIS = 300L

        val BOARD_DEVICE = snapshotpb.v1.Device(
            deviceId = Uuid.parse(BOARD_ID).toByteArray(),
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
