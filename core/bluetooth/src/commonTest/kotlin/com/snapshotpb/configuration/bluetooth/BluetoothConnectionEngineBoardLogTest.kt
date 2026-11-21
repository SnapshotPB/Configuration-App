package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.log.BoardLogUuids
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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
import kotlin.uuid.Uuid

/** Engine tests for Bluetooth log sessions and NUS TX board log subscription. */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionEngineBoardLogTest {

    private class Fixture(
        val engine: BluetoothConnectionEngine,
        val stateManager: BluetoothStateManager,
        val falconEngine: FakeBlueFalconEngine,
        val board: FakeOtsBoard,
        val seams: FakePlatformBluetoothSeams,
        val log: RecordingBluetoothLog,
        val reads: List<DeviceReadResult>,
    )

    private fun newBoard(
        peripheralUuid: String,
        boardDevice: snapshotpb.v1.Device,
        extraServices: List<BluetoothService> = emptyList(),
    ): FakeOtsBoard = FakeOtsBoard(peripheralUuid = peripheralUuid, extraServices = extraServices).apply {
        setContent("device", ProtoBuf.encodeToByteArray(snapshotpb.v1.Device.serializer(), boardDevice))
        setContent("device_config", ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), BOARD_CONFIG))
        setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), BOARD_PROFILES))
    }

    private fun TestScope.newFixture(
        extraServices: List<BluetoothService> = emptyList(),
        log: RecordingBluetoothLog = RecordingBluetoothLog(),
    ): Fixture {
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
        val readBus = DeviceReadResultBus()
        val reads = mutableListOf<DeviceReadResult>()
        backgroundScope.launch { readBus.results.collect { reads += it } }
        val engine = BluetoothConnectionEngine(
            blueFalcon = BlueFalcon(falconEngine),
            bluetoothStateManager = stateManager,
            bleDebugLog = BleDebugLog(),
            bluetoothLog = log,
            deviceReadResultBus = readBus,
            deviceWriteResultBus = DeviceWriteResultBus(),
            seams = seams,
            coroutineScope = backgroundScope,
        )
        return Fixture(engine, stateManager, falconEngine, board, seams, log, reads)
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
            listOf("startSession(1)", "boardLogState(1, ABSENT)", "sessionBoard(1, $BOARD_ID, null)"),
            f.log.calls,
        )

        f.engine.disconnect(DEVICE_ID)
        runCurrent()

        assertEquals(
            listOf(
                "startSession(1)",
                "boardLogState(1, ABSENT)",
                "sessionBoard(1, $BOARD_ID, null)",
                "endSession(1)",
            ),
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
                "boardLogState(1, ABSENT)",
                "sessionBoard(1, $BOARD_ID, null)",
                "endSession(1)",
                "startSession(2)",
                "boardLogState(2, ABSENT)",
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
            listOf(
                "startSession(1)",
                "boardLogState(1, ABSENT)",
                "sessionBoard(1, $BOARD_ID, null)",
                "endSession(1)",
            ),
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
                    "boardLogState(1, ABSENT)",
                    "sessionBoard(1, $BOARD_ID, null)",
                    "startSession(2)",
                    "endSession(1)",
                    "boardLogState(2, ABSENT)",
                    "sessionBoard(2, $SECOND_BOARD_ID, null)",
                ),
                f.log.calls,
            )
        }

    @Test
    fun subscribeBoardLog_runsAfterOnServicesDiscoveredAndBeforeOtsInit() = runTest(UnconfinedTestDispatcher()) {
        val nus = FakeBoardLogService()
        val f = newFixture(extraServices = listOf(nus.service))
        var discoveredCountAtFirstNotify = -1
        f.falconEngine.onNotifyCharacteristic = { _, _ ->
            if (discoveredCountAtFirstNotify == -1) {
                discoveredCountAtFirstNotify = f.seams.onServicesDiscoveredCalls.size
            }
        }

        connectComplete(f)

        assertEquals(BoardLogUuids.Tx, f.falconEngine.notifyCalls.first().first.uuid)
        assertEquals(1, discoveredCountAtFirstNotify)
    }

    @Test
    fun subscribeBoardLog_usesUuidWhenIsNotifyingFalseAndRecordsRequested() = runTest(UnconfinedTestDispatcher()) {
        val nus = FakeBoardLogService(isNotifying = false)
        val f = newFixture(extraServices = listOf(nus.service))

        connectComplete(f)

        assertTrue("boardLogState(1, REQUESTED)" in f.log.calls)
    }

    @Test
    fun subscribeBoardLog_deliversBoardChunkWithSessionNumber() = runTest(UnconfinedTestDispatcher()) {
        val nus = FakeBoardLogService()
        val f = newFixture(extraServices = listOf(nus.service))
        connectComplete(f)

        nus.send("[00:00:01.000,000] app: hello\r\n")
        runCurrent()

        assertEquals(listOf(1 to "[00:00:01.000,000] app: hello\r\n"), f.log.chunks.map { it.first to it.second.decodeToString() })
    }

    @Test
    fun subscribeBoardLog_whenInitialReadFails_keepsChunkAndEndsSession() = runTest(UnconfinedTestDispatcher()) {
        val nus = FakeBoardLogService()
        val f = newFixture(extraServices = listOf(nus.service))
        f.board.failReadOf = "profiles"
        f.falconEngine.onNotifyCharacteristic = { characteristic, _ ->
            if (characteristic.uuid == BoardLogUuids.Tx) {
                nus.send("[00:00:01.000,000] ble: log enabled\r\n")
            }
        }

        connectComplete(f)

        assertEquals(
            listOf(1 to "[00:00:01.000,000] ble: log enabled\r\n"),
            f.log.chunks.map { it.first to it.second.decodeToString() },
        )
        assertEquals("endSession(1)", f.log.calls.last())
    }

    @Test
    fun subscribeBoardLog_whenServiceAbsent_recordsAbsentAndConnectSucceeds() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        connectComplete(f)

        assertTrue("boardLogState(1, ABSENT)" in f.log.calls)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
    }

    @Test
    fun subscribeBoardLog_whenServiceHiddenByPermission_recordsNotVisible() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.seams.servicesFollowPermission = true

        connectComplete(f)

        assertTrue("boardLogState(1, NOT_VISIBLE)" in f.log.calls)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
    }

    @Test
    fun subscribeBoardLog_whenTxMissing_recordsFailedAndConnectSucceeds() = runTest(UnconfinedTestDispatcher()) {
        val nus = FakeBoardLogService(withTx = false)
        val f = newFixture(extraServices = listOf(nus.service))

        connectComplete(f)

        assertTrue("boardLogState(1, FAILED)" in f.log.calls)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
    }

    @Test
    fun subscribeBoardLog_whenNotifyThrows_recordsFailedCancelsCollectorAndConnectSucceeds() =
        runTest(UnconfinedTestDispatcher()) {
            val nus = FakeBoardLogService()
            val f = newFixture(extraServices = listOf(nus.service))
            f.falconEngine.onNotifyCharacteristic = { characteristic, _ ->
                if (characteristic.uuid == BoardLogUuids.Tx) {
                    throw IllegalStateException("notify failed")
                }
            }

            connectComplete(f)
            nus.send("late chunk\r\n")
            runCurrent()

            assertTrue("boardLogState(1, FAILED)" in f.log.calls)
            assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
            assertTrue(f.log.chunks.isEmpty())
            assertEquals(0, nus.tx.emitter.subscriptionCount.value)
        }

    @Test
    fun subscribeBoardLog_whenNotifyNeverReturns_timesOutToFailedAndConnectSucceeds() =
        runTest(UnconfinedTestDispatcher()) {
            val nus = FakeBoardLogService()
            val f = newFixture(extraServices = listOf(nus.service))
            f.falconEngine.onNotifyCharacteristic = { characteristic, _ ->
                if (characteristic.uuid == BoardLogUuids.Tx) {
                    awaitCancellation()
                }
            }

            f.engine.connect(DEVICE_ID)
            advanceTimeBy(2_999L)
            runCurrent()
            assertFalse("boardLogState(1, FAILED)" in f.log.calls)

            advanceTimeBy(1L)
            runCurrent()
            assertTrue("boardLogState(1, FAILED)" in f.log.calls)

            advanceTimeBy(CONNECT_MILLIS)
            runCurrent()
            assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
            assertEquals(0, nus.tx.emitter.subscriptionCount.value)
        }

    @Test
    fun subscribeBoardLog_whenRecordsBoardLinesFalse_skipsSeamAndSubscribe() = runTest(UnconfinedTestDispatcher()) {
        val nus = FakeBoardLogService()
        val f = newFixture(
            extraServices = listOf(nus.service),
            log = RecordingBluetoothLog(recordsBoardLines = false),
        )

        connectComplete(f)

        assertTrue(f.falconEngine.notifyCalls.none { it.first.uuid == BoardLogUuids.Tx })
        assertFalse(BoardLogUuids.Service in f.seams.serviceWithCharacteristicsCalls)
        assertTrue(f.log.calls.none { it.startsWith("boardLogState") })
    }

    @Test
    fun subscribeBoardLog_whenConnectCancelled_cancelsCollectorAndDropsLaterChunks() =
        runTest(UnconfinedTestDispatcher()) {
            val nus = FakeBoardLogService()
            val f = newFixture(extraServices = listOf(nus.service))
            f.board.gatedReadObject = "profiles"
            f.board.readGate = CompletableDeferred()

            f.engine.connect(DEVICE_ID)
            advanceTimeBy(PARK_MILLIS)
            runCurrent()
            assertEquals(1, nus.tx.emitter.subscriptionCount.value)

            f.engine.disconnect(DEVICE_ID)
            runCurrent()
            nus.send("after cancel\r\n")
            runCurrent()

            assertTrue("endSession(1)" in f.log.calls)
            assertEquals(0, nus.tx.emitter.subscriptionCount.value)
            assertTrue(f.log.chunks.isEmpty())
        }

    @Test
    fun subscribeBoardLog_secondConnectAfterDisconnect_tagsChunksWithEachSession() =
        runTest(UnconfinedTestDispatcher()) {
            val nus = FakeBoardLogService()
            val f = newFixture(extraServices = listOf(nus.service))

            connectComplete(f)
            nus.send("session one\r\n")
            runCurrent()

            f.engine.disconnect(DEVICE_ID)
            runCurrent()

            connectComplete(f)
            nus.send("session two\r\n")
            runCurrent()

            assertEquals(
                listOf(1 to "session one\r\n", 2 to "session two\r\n"),
                f.log.chunks.map { it.first to it.second.decodeToString() },
            )
        }

    @Test
    fun subscribeBoardLog_liveLinkReread_subscribesOnceOnly() = runTest(UnconfinedTestDispatcher()) {
        val nus = FakeBoardLogService()
        val f = newFixture(extraServices = listOf(nus.service))

        connectComplete(f)
        connectComplete(f)

        assertEquals(1, f.falconEngine.notifyCalls.count { it.first.uuid == BoardLogUuids.Tx })
    }

    @Test
    fun subscribeBoardLog_whenTxIsNotifying_debugObserverSkipsSecondSubscribe() =
        runTest(UnconfinedTestDispatcher()) {
            val nus = FakeBoardLogService(isNotifying = true)
            val f = newFixture(extraServices = listOf(nus.service))

            connectComplete(f)

            assertEquals(1, f.falconEngine.notifyCalls.count { it.first.uuid == BoardLogUuids.Tx })
        }

    @Test
    fun subscribeBoardLog_throughputDuringInitialRead_deliversAll200ChunksAndReadsDevice() =
        runTest(UnconfinedTestDispatcher()) {
            val nus = FakeBoardLogService()
            val f = newFixture(extraServices = listOf(nus.service))
            f.board.gatedReadObject = "profiles"
            f.board.readGate = CompletableDeferred()

            f.engine.connect(DEVICE_ID)
            advanceTimeBy(PARK_MILLIS)
            runCurrent()

            for (index in 1..200) {
                nus.send("[00:00:01.000,000] app: line $index\r\n")
            }
            runCurrent()

            f.board.readGate?.complete(Unit)
            advanceTimeBy(CONNECT_MILLIS)
            runCurrent()

            assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
            assertEquals(1, f.reads.size)
            val device = f.reads.single().device
            assertEquals("HELLO", device.bootText)
            assertEquals(listOf("P0", "P1"), device.knownProfiles.map { it.name })
            assertEquals(200, f.log.chunks.size)
            assertTrue(f.log.chunks.all { it.first == 1 })
        }

    private companion object {
        const val DEVICE_ID = "5350425f-4f54-4000-8000-000000000001"
        const val SECOND_DEVICE_ID = "5350425f-4f54-4000-8000-000000000002"
        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val SECOND_BOARD_ID = "11223344-5566-7788-99aa-bbccddeeff00"

        const val PARK_MILLIS = 4_000L
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
