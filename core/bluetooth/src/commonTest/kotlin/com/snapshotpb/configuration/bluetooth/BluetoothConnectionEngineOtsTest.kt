package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.OlcpOpcode
import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import com.snapshotpb.configuration.bluetooth.ots.ProfilesWire
import com.snapshotpb.configuration.bluetooth.ots.decodeDeviceConfig
import com.snapshotpb.configuration.bluetooth.ots.decodeProfilesWire
import com.snapshotpb.configuration.bluetooth.ots.toDomainDevice
import com.snapshotpb.configuration.model.AdvertisementData
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerProfile
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.ConnectionPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Engine tests that run the full OTS read and write over [FakeOtsBoard]: the connect gate on the
 * initial read (H1), the engine-owned write (M1), the OTS transaction lock, the write read-back
 * (L15) and the reuse of a live link (N6).
 *
 * Virtual time: the fake seams do not wait after a subscription, and each selection after a commit
 * waits 750 ms. The tests advance time past those waits, but never past the 5 s control-point
 * timeout while a gate parks the board.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionEngineOtsTest {

    private class Fixture(
        val engine: BluetoothConnectionEngine,
        val stateManager: BluetoothStateManager,
        val falconEngine: FakeBlueFalconEngine,
        val board: FakeOtsBoard,
        val seams: FakePlatformBluetoothSeams,
        val reads: List<DeviceReadResult>,
        val writes: List<DeviceWriteResult>,
        /** "read" and "write" in the order that the results arrived. */
        val events: List<String>,
    )

    private fun TestScope.newFixture(
        scanKey: String = DEVICE_ID,
        boardDevice: snapshotpb.v1.Device = BOARD_DEVICE,
    ): Fixture {
        val falconEngine = FakeBlueFalconEngine(backgroundScope).apply {
            emitManagerState(BluetoothManagerState.Ready)
            // The link monitor polls this; Connected keeps the link up for the whole test.
            connectionStateResult = BluetoothPeripheralState.Connected
        }
        val board = FakeOtsBoard().apply {
            setContent("device", ProtoBuf.encodeToByteArray(snapshotpb.v1.Device.serializer(), boardDevice))
            setContent("device_config", ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), BOARD_CONFIG))
            setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), BOARD_PROFILES))
            install(falconEngine)
        }
        val seams = FakePlatformBluetoothSeams().apply {
            setDevices(mapOf(scanKey to board.peripheral))
        }
        val stateManager = BluetoothStateManager().apply { update(BluetoothState.Ready) }
        val readBus = DeviceReadResultBus()
        val writeBus = DeviceWriteResultBus()
        val reads = mutableListOf<DeviceReadResult>()
        val writes = mutableListOf<DeviceWriteResult>()
        val events = mutableListOf<String>()
        backgroundScope.launch { readBus.results.collect { reads += it; events += "read" } }
        backgroundScope.launch { writeBus.results.collect { writes += it; events += "write" } }
        val engine = BluetoothConnectionEngine(
            blueFalcon = BlueFalcon(falconEngine),
            bluetoothStateManager = stateManager,
            bleDebugLog = BleDebugLog(),
            deviceReadResultBus = readBus,
            deviceWriteResultBus = writeBus,
            seams = seams,
            coroutineScope = backgroundScope,
        )
        return Fixture(engine, stateManager, falconEngine, board, seams, reads, writes, events)
    }

    private fun TestScope.connectAndSync(f: Fixture): Device {
        f.engine.connect(DEVICE_ID)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value, "the connect should succeed")
        return f.reads.last().device
    }

    @Test
    fun connect_marksTheDeviceConnectedOnlyAfterTheInitialRead() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.board.gatedReadObject = "profiles"
        f.board.readGate = CompletableDeferred()

        f.engine.connect(DEVICE_ID)
        advanceTimeBy(PARK_MILLIS)

        // The read of "profiles" is parked: OTS is up, but the board state is not known yet, so
        // Save must stay off (H1).
        assertFalse(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(StepStatus.IN_PROGRESS, f.stateManager.connectionProgress.value.step4)
        assertTrue(f.reads.isEmpty())

        f.board.readGate?.complete(Unit)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()

        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertTrue(f.stateManager.connectionProgress.value.isCompleted)
        assertEquals(1, f.reads.size)
        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
    }

    @Test
    fun connect_whenTheInitialReadFails_tearsTheLinkDownAndStaysDisconnected() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.board.failReadOf = "profiles"

        f.engine.connect(DEVICE_ID)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()

        assertFalse(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(StepStatus.FAILED, f.stateManager.connectionProgress.value.step4)
        // The progress holds only the class name: the message text goes into a test tag that iOS
        // shows as an accessibility identifier, also in a release build.
        assertEquals("OacpFailure", f.stateManager.connectionProgress.value.failureCode)
        assertTrue(f.reads.isEmpty())
        assertEquals(1, f.falconEngine.disconnectCalls.size, "a half-synced link must not stay open")

        // With no link and no board state, a write is refused and nothing reaches the board.
        f.engine.writeDeviceConfiguration(DEVICE_ID, boardDevice(), requestId = 9L)
        assertNotNull(f.writes.single().error)
        assertEquals(9L, f.writes.single().requestId, "a refused write reports its request id too")
        assertTrue(f.board.writtenObjects.isEmpty())
    }

    @Test
    fun write_duringTheInitialRead_waitsForTheReadAndKeepsBoardAutoOff() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.board.gatedReadObject = "profiles"
        f.board.readGate = CompletableDeferred()
        f.engine.connect(DEVICE_ID)
        advanceTimeBy(PARK_MILLIS)

        val write = launch { f.engine.writeDeviceConfiguration(DEVICE_ID, boardDevice()) }
        runCurrent()
        // The write waits until the initial read is done: it must not move the board's one OLCP
        // cursor mid-read, and it needs the board's Profiles to keep board_auto_off.
        assertTrue(f.board.writtenObjects.isEmpty())
        assertTrue(write.isActive)

        f.board.readGate?.complete(Unit)
        advanceTimeBy(CONNECT_MILLIS + WRITE_MILLIS)
        runCurrent()

        assertEquals(listOf("device_config", "profiles"), f.board.writtenObjects)
        assertNull(f.writes.single().error)
        // The write ran after the read, so each slot keeps the board-owned field (H1, finding #7).
        val stored = decodeProfilesWire(f.board.content("profiles"))
        assertEquals(listOf(11u, 22u), stored.profiles.map { it.boardAutoOff })
    }

    @Test
    fun write_whenTheCallerIsCancelled_stillCompletesAndReports() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)
        f.board.writeChunkGate = CompletableDeferred()

        val caller = launch { f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "ROTATE")) }
        runCurrent()
        // An activity recreation cancels the caller while the first object is mid-transfer (M1).
        caller.cancel()
        f.board.writeChunkGate?.complete(Unit)
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        assertEquals(listOf("device_config", "profiles"), f.board.writtenObjects)
        assertEquals("ROTATE", decodeDeviceConfig(f.board.content("device_config")).bootText)
        val result = f.writes.single()
        assertNull(result.error)
        assertEquals("ROTATE", result.device.bootText)
    }

    @Test
    fun listObjects_duringAWrite_waitsForTheWriteTransaction() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)
        f.board.writeChunkGate = CompletableDeferred()

        launch { f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "LOCKED")) }
        runCurrent()
        val cursorDuringWrite = f.board.selected
        val listing = async { f.engine.listOtsObjects(DEVICE_ID) }
        runCurrent()

        // The object walk would move the cursor that the parked write depends on.
        assertTrue(listing.isActive)
        assertEquals(cursorDuringWrite, f.board.selected)

        f.board.writeChunkGate?.complete(Unit)
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        assertEquals(listOf("device_config", "profiles"), f.board.writtenObjects)
        assertEquals(listOf("device", "device_config", "profiles"), listing.await().map { it.name })
    }

    @Test
    fun write_adoptsTheActiveProfileAndBootTextThatTheBoardReadsBack() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)
        // Firmware that keeps its own values: the board stores slot 2 and "BOARD" whatever is sent.
        f.board.onCommit = { name, bytes ->
            if (name == "device_config") {
                val sent = decodeDeviceConfig(bytes)
                ProtoBuf.encodeToByteArray(
                    DeviceConfig.serializer(),
                    sent.copy(activeProfile = 2u, bootText = "BOARD"),
                )
            } else {
                bytes
            }
        }

        f.engine.writeDeviceConfiguration(
            DEVICE_ID,
            synced.copy(bootText = "APP", activeProfileId = synced.knownProfiles[0].id),
            requestId = 7L,
        )
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        val result = f.writes.single()
        assertNull(result.error)
        assertEquals(7L, result.requestId, "the result carries the request id of its write")
        // The result reports what the board kept, not what the app sent (L15).
        assertEquals("BOARD", result.device.bootText)
        assertEquals(synced.knownProfiles[1].id, result.device.activeProfileId)
    }

    @Test
    fun connect_toALiveConnection_readsAgainWithoutASecondLink() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connectAndSync(f)
        assertEquals(1, f.falconEngine.connectCalls.size)

        f.engine.connect(DEVICE_ID)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()

        // N6: no second blueFalcon.connect on the live link, but a fresh read of the board.
        assertEquals(1, f.falconEngine.connectCalls.size)
        assertEquals(0, f.falconEngine.disconnectCalls.size)
        assertEquals(2, f.reads.size)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertTrue(f.stateManager.connectionProgress.value.isCompleted)
        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
    }

    @Test
    fun connect_toALiveConnection_duringAWrite_readsAfterTheWrite() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)
        f.board.writeChunkGate = CompletableDeferred()

        launch { f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "NEW")) }
        runCurrent()
        // A second connect while the write is parked reads the board again on the live link (N6).
        f.engine.connect(DEVICE_ID)
        runCurrent()
        assertEquals(1, f.reads.size, "the re-read must wait for the write")

        f.board.writeChunkGate?.complete(Unit)
        advanceTimeBy(WRITE_MILLIS + CONNECT_MILLIS)
        runCurrent()

        // The re-read ran after the whole write, so it saw the written board and the write did
        // not replace the Profiles that the re-read recorded.
        assertEquals(listOf("read", "write", "read"), f.events)
        assertEquals("NEW", f.reads.last().device.bootText)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
    }

    @Test
    fun connect_selectsEachObjectWithOneGoToAfterTheIndex() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        connectAndSync(f)

        // The index walks the list once (First, Next, Next, and a Next past the end). Each of the
        // three reads then selects its object with one Go To, not a walk from the first object.
        assertEquals(
            listOf(
                OlcpOpcode.FIRST, OlcpOpcode.NEXT, OlcpOpcode.NEXT, OlcpOpcode.NEXT,
                OlcpOpcode.GOTO, OlcpOpcode.GOTO, OlcpOpcode.GOTO,
            ),
            f.board.olcpOpcodes,
        )
    }

    @Test
    fun connect_whenTheBoardRefusesGoTo_selectsByTheWalk() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.board.supportsGoTo = false

        val device = connectAndSync(f)

        assertEquals("HELLO", device.bootText)
        // Each refused Go To drops that ID, so each object tries Go To once and then walks.
        assertEquals(3, f.board.olcpOpcodes.count { it == OlcpOpcode.GOTO })
    }

    @Test
    fun write_whenAGoToGetsNoResponseInTheCommitWindow_retriesTheGoToAndKeepsTheId() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture()
            connectAndSync(f)
            f.board.dropGoToAfterCommit = true
            val opcodesBeforeWrite = f.board.olcpOpcodes.size

            f.engine.writeDeviceConfiguration(DEVICE_ID, boardDevice().copy(bootText = "NEW"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()

            assertNull(f.writes.single().error)
            assertEquals(listOf("device_config", "profiles"), f.board.writtenObjects)
            // The Go To with no response kept the ID, so the retry is a Go To again, not a walk.
            val writeOpcodes = f.board.olcpOpcodes.drop(opcodesBeforeWrite)
            assertTrue(writeOpcodes.all { it == OlcpOpcode.GOTO }, "opcodes: $writeOpcodes")
        }

    @Test
    fun connect_waitsThroughTheSeamAfterEachSubscription() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        connectAndSync(f)

        assertEquals(
            listOf(OtsUuids.Oacp, OtsUuids.Olcp, OtsUuids.ObjectData),
            f.seams.subscribedCharacteristics.map { it.uuid },
        )
    }

    @Test
    fun connectAndWrite_useHighPriorityAndReturnToBalanced() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connectAndSync(f)
        assertEquals(
            listOf(ConnectionPriority.High, ConnectionPriority.Balanced),
            f.falconEngine.connectionPriorities,
        )

        f.engine.writeDeviceConfiguration(DEVICE_ID, boardDevice().copy(bootText = "NEW"))
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        assertEquals(
            listOf(
                ConnectionPriority.High, ConnectionPriority.Balanced,
                ConnectionPriority.High, ConnectionPriority.Balanced,
            ),
            f.falconEngine.connectionPriorities,
        )
    }

    @Test
    fun connect_attachesTheAdvertisementOfTheScanKeyToTheRead() = runTest(UnconfinedTestDispatcher()) {
        // The OTS Device object has no firmware version, so the read takes it from the scan (#129).
        val f = newFixture()
        val advertisement = AdvertisementData(boardType = "Cocker", fwVersion = "2026.29.0-dev")
        f.seams.setDevices(mapOf(DEVICE_ID to f.board.peripheral), mapOf(DEVICE_ID to advertisement))

        val device = connectAndSync(f)

        assertEquals(advertisement, device.advertisementData)
    }

    @Test
    fun connect_withTwoScanKeysOfOneBoard_givesTheSameDeviceId() = runTest(UnconfinedTestDispatcher()) {
        // Two phones (or two platforms) see one board under two different transport keys (#127).
        val phoneA = newFixture(scanKey = DEVICE_ID)
        val phoneB = newFixture(scanKey = OTHER_SCAN_KEY)

        phoneA.engine.connect(DEVICE_ID)
        phoneB.engine.connect(OTHER_SCAN_KEY)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()

        assertEquals(BOARD_ID, phoneA.reads.single().device.id.toString())
        assertEquals(BOARD_ID, phoneB.reads.single().device.id.toString())
        assertEquals(DEVICE_ID, phoneA.reads.single().scanKey)
        assertEquals(OTHER_SCAN_KEY, phoneB.reads.single().scanKey)
        assertEquals(mapOf(DEVICE_ID to BOARD_ID), phoneA.stateManager.boardIds.value)
    }

    @Test
    fun writeAndDisconnect_byTheBoardId_useTheScanKeyOfTheSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)
        assertEquals(BOARD_ID, synced.id.toString())

        f.engine.writeDeviceConfiguration(BOARD_ID, synced.copy(bootText = "BYID"), requestId = 3L)
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()
        assertNull(f.writes.single().error)
        assertEquals("BYID", decodeDeviceConfig(f.board.content("device_config")).bootText)

        f.engine.disconnect(BOARD_ID)
        assertFalse(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(1, f.falconEngine.disconnectCalls.size)
    }

    @Test
    fun connect_byABoardIdWithNoScanKey_takesAnUnreadBoardAndChecksItsId() = runTest(UnconfinedTestDispatcher()) {
        // A known board after an app restart: the store has its board id, the session has no map.
        val f = newFixture()

        f.engine.connect(BOARD_ID)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()

        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(BOARD_ID, f.reads.single().device.id.toString())
        assertEquals(mapOf(DEVICE_ID to BOARD_ID), f.stateManager.boardIds.value)
    }

    @Test
    fun connect_byABoardId_whenADifferentBoardAnswers_failsAndReportsNoRead() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val otherBoardId = "99999999-8888-4777-8666-555555555555"

        f.engine.connect(otherBoardId)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()

        // The only board near is not the one the UI asked for, so it must not take its place.
        assertTrue(f.reads.isEmpty())
        assertTrue(f.stateManager.connectedDevices.value.isEmpty())
        assertEquals(StepStatus.FAILED, f.stateManager.connectionProgress.value.step4)
        assertEquals(1, f.falconEngine.disconnectCalls.size)
        // The read still recorded which board that scan key is, so a retry skips it.
        assertEquals(mapOf(DEVICE_ID to BOARD_ID), f.stateManager.boardIds.value)
    }

    @Test
    fun write_afterABoardMenuEditOfAProfile_doesNotWriteAndReportsTheBoard() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)
        // The user edits a profile and engages the lockout in the board menu. The board sends no
        // notification for either (#123).
        f.board.setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), MENU_PROFILES))
        f.board.setContent(
            "device",
            ProtoBuf.encodeToByteArray(snapshotpb.v1.Device.serializer(), BOARD_DEVICE.copy(lockoutEnabled = true)),
        )

        f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "APP"), requestId = 3L)
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        assertTrue(f.board.writtenObjects.isEmpty(), "the Save must not overwrite the menu edit")
        // The board state goes out before the failed write, so the sync rules see it first.
        assertEquals(listOf("read", "read", "write"), f.events)
        val boardNow = f.reads.last().device
        assertEquals(listOf("MENU", "P1"), boardNow.knownProfiles.map { it.name })
        assertTrue(boardNow.lockoutEnabled, "the read result carries the current lockout")
        val result = f.writes.single()
        assertIs<BoardChangedBeforeWriteException>(result.error)
        assertEquals(3L, result.requestId)
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value, "the link stays up")
    }

    @Test
    fun write_afterABoardMenuEditOfDeviceConfig_doesNotWriteAndReportsTheBoard() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture()
            val synced = connectAndSync(f)
            // The user picks the second profile in the board menu.
            f.board.setContent(
                "device_config",
                ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), BOARD_CONFIG.copy(activeProfile = 2u)),
            )

            f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "APP"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()

            assertTrue(f.board.writtenObjects.isEmpty())
            assertEquals(synced.knownProfiles[1].id, f.reads.last().device.activeProfileId)
            assertIs<BoardChangedBeforeWriteException>(f.writes.single().error)
        }

    @Test
    fun write_afterTheBoardChangeWasReported_writesAndKeepsTheNewBoardAutoOff() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture()
            val synced = connectAndSync(f)
            f.board.setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), MENU_PROFILES))
            f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "APP"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()
            assertTrue(f.board.writtenObjects.isEmpty())

            // "Keep my values" sends the same device again. The engine now knows the board state,
            // so the write goes through.
            f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "APP"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()

            assertEquals(listOf("device_config", "profiles"), f.board.writtenObjects)
            assertNull(f.writes.last().error)
            val stored = decodeProfilesWire(f.board.content("profiles"))
            assertEquals(listOf("P0", "P1"), stored.profiles.map { it.name })
            // Each slot keeps the board-owned field from the read that found the menu edit.
            assertEquals(listOf(33u, 44u), stored.profiles.map { it.boardAutoOff })
        }

    @Test
    fun write_twiceInOneSession_isNotTakenForABoardMenuEdit() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)
        // Firmware that changes a value on commit: the board holds other Profiles than the app sent.
        f.board.onCommit = { name, bytes ->
            if (name == "profiles") {
                val sent = decodeProfilesWire(bytes)
                ProtoBuf.encodeToByteArray(
                    ProfilesWire.serializer(),
                    sent.copy(profiles = sent.profiles.map { it.copy(boardAutoOff = it.boardAutoOff + 1u) }),
                )
            } else {
                bytes
            }
        }

        f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "ONE"))
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()
        f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "TWO"))
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        // The app's own write neither blocks the next write nor starts a read of its own.
        assertEquals(listOf("read", "write", "write"), f.events)
        assertTrue(f.writes.all { it.error == null })
        assertEquals("TWO", decodeDeviceConfig(f.board.content("device_config")).bootText)
    }

    @Test
    fun write_afterAFailedReadBackOfDeviceConfig_doesNotCompareTheOldCopy() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture()
            val synced = connectAndSync(f)
            // The board keeps its own boot text, and the read-back that would tell the app fails.
            f.board.onCommit = { name, bytes ->
                if (name == "device_config") {
                    ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), decodeDeviceConfig(bytes).copy(bootText = "KEPT"))
                } else {
                    f.board.failReadOf = "device_config"
                    bytes
                }
            }
            f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "ONE"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()
            assertNull(f.writes.single().error, "a failed read-back does not fail the write")
            f.board.onCommit = null
            f.board.failReadOf = null

            f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "TWO"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()

            // The copy from before the first write is out of date. A compare with it would take the
            // app's own write for a menu edit.
            assertNull(f.writes.last().error)
            assertEquals(1, f.reads.size)
            assertEquals("TWO", decodeDeviceConfig(f.board.content("device_config")).bootText)
        }

    @Test
    fun write_afterABoardChangeOfBoardAutoOffOnly_writesAndKeepsTheNewValue() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture()
            val synced = connectAndSync(f)
            // Only a board-owned field changes. The user cannot see it in the app or resolve it,
            // so it must not stop the Save.
            val autoOffOnly = Profiles(
                profiles = BOARD_PROFILES.profiles.map { it!!.copy(boardAutoOff = it.boardAutoOff + 100u) }
            )
            f.board.setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), autoOffOnly))

            f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "APP"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()

            assertEquals(listOf("read", "write"), f.events)
            assertNull(f.writes.single().error)
            assertEquals(listOf("device_config", "profiles"), f.board.writtenObjects)
            val stored = decodeProfilesWire(f.board.content("profiles"))
            assertEquals(listOf(111u, 122u), stored.profiles.map { it.boardAutoOff })
        }

    @Test
    fun write_afterAWriteThatFailedAfterDeviceConfig_isNotTakenForABoardMenuEdit() =
        runTest(UnconfinedTestDispatcher()) {
            val f = newFixture()
            val synced = connectAndSync(f)
            // The board stores device_config, then the profiles step fails. The link stays up.
            f.board.failWriteOf = "profiles"
            f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "ONE"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()
            assertNotNull(f.writes.single().error)
            assertEquals("ONE", decodeDeviceConfig(f.board.content("device_config")).bootText)
            f.board.failWriteOf = null

            // The retry finds the app's own partial write on the board. That is not a menu edit.
            f.engine.writeDeviceConfiguration(DEVICE_ID, synced.copy(bootText = "ONE"))
            advanceTimeBy(WRITE_MILLIS)
            runCurrent()

            assertNull(f.writes.last().error)
            assertEquals(1, f.reads.size)
        }

    @Test
    fun write_afterADeleteAndAnAdd_keepsBoardAutoOffWithItsProfile() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)
        // Delete P0 the way the Profiles screen does (P1 moves to slot 0 and takes its id), then
        // add a profile. The list has two profiles again, as on the board.
        val moved = synced.knownProfiles[1].copy(id = synced.knownProfiles[0].id)
        val added = MarkerProfile(id = synced.knownProfiles[1].id, name = "NEW")
        val edited = synced.copy(knownProfiles = listOf(moved, added), activeProfileId = moved.id)

        f.engine.writeDeviceConfiguration(DEVICE_ID, edited, requestId = 1L)
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        // P1 keeps its own board_auto_off. A pairing by index sent the value of P0 (#119).
        val first = decodeProfilesWire(f.board.content("profiles"))
        assertEquals(listOf("P1", "NEW"), first.profiles.map { it.name })
        assertEquals(listOf(22u, 0u), first.profiles.map { it.boardAutoOff })
        // The result records the slot that each profile now has on the board.
        val result = f.writes.single()
        assertNull(result.error)
        assertEquals(listOf(0, 1), result.device.knownProfiles.map { it.sourceSlot })

        // A second save in the same session pairs each profile with its new slot.
        f.engine.writeDeviceConfiguration(DEVICE_ID, result.device.copy(bootText = "AGAIN"), requestId = 2L)
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        assertNull(f.writes.last().error)
        assertEquals(listOf(22u, 0u), decodeProfilesWire(f.board.content("profiles")).profiles.map { it.boardAutoOff })
    }

    @Test
    fun write_withNoProfiles_isRefusedBeforeAnyObjectGoes() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val synced = connectAndSync(f)

        f.engine.writeDeviceConfiguration(
            DEVICE_ID,
            synced.copy(bootText = "EMPTY", knownProfiles = emptyList(), activeProfileId = null),
            requestId = 3L,
        )
        advanceTimeBy(WRITE_MILLIS)
        runCurrent()

        // The board refuses a zero-length Profiles write. Without the guard, device_config would
        // go first and the board would keep half of the save.
        val result = f.writes.single()
        assertNotNull(result.error)
        assertEquals(3L, result.requestId)
        assertTrue(f.board.writtenObjects.isEmpty())
        assertEquals("HELLO", decodeDeviceConfig(f.board.content("device_config")).bootText)
    }

    /** The board's own configuration as a domain device, as a local copy holds it. */
    private fun boardDevice(): Device = toDomainDevice(
        protoDevice = BOARD_DEVICE,
        protoDeviceConfig = BOARD_CONFIG,
        protoProfiles = BOARD_PROFILES,
        deviceName = "Board",
        lastSeenDate = LocalDate(2026, 9, 22),
    )

    private companion object {
        /** The scan key of the board on the first phone. */
        const val DEVICE_ID = "5350425f-4f54-4000-8000-000000000001"

        /** The scan key of the same board on a second phone. */
        const val OTHER_SCAN_KEY = "7a1c0e52-3b1d-4c4e-9f00-000000000002"

        /** The board's own device_id, which the read gives as the device id (#127). */
        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"

        /** Enough for the object index and the first reads, but under the 5 s control timeout. */
        const val PARK_MILLIS = 4_000L
        const val CONNECT_MILLIS = 20_000L
        const val WRITE_MILLIS = 20_000L

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
            )
        )

        /** [BOARD_PROFILES] after an edit in the board menu, with new board-owned values. */
        val MENU_PROFILES = Profiles(
            profiles = listOf(
                BOARD_PROFILES.profiles[0]!!.copy(name = "MENU", boardAutoOff = 33u),
                BOARD_PROFILES.profiles[1]!!.copy(boardAutoOff = 44u),
            )
        )
    }
}
