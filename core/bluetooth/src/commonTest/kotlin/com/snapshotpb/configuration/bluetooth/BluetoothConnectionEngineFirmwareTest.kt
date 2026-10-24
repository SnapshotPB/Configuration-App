package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.ots.toDomainDevice
import com.snapshotpb.configuration.bluetooth.smp.FakeImageBoard
import com.snapshotpb.configuration.bluetooth.smp.ImageCommand
import com.snapshotpb.configuration.bluetooth.smp.ImageHash
import com.snapshotpb.configuration.bluetooth.smp.OsCommand
import com.snapshotpb.configuration.bluetooth.smp.SmpGroup
import com.snapshotpb.configuration.bluetooth.smp.SmpOperation
import com.snapshotpb.configuration.model.FirmwareAsset
import com.snapshotpb.configuration.model.FirmwareAssets
import com.snapshotpb.configuration.model.FirmwareImage
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.ConnectionPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.protobuf.ProtoBuf
import snapshotpb.v1.BoardModel
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.Profile
import snapshotpb.v1.Profiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Engine tests of the firmware update over [FakeSmpBoard]: the read of the update service after the
 * 4-step connect (with SMP, without SMP, an SMP error), and the [BluetoothRequest.UpdateFirmware]
 * and [BluetoothRequest.ConfirmFirmware] steps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionEngineFirmwareTest {

    private class Fixture(
        val engine: BluetoothConnectionEngine,
        val stateManager: BluetoothStateManager,
        val falconEngine: FakeBlueFalconEngine,
        val board: FakeOtsBoard,
        val smp: FakeSmpBoard?,
        val uploads: List<FirmwareUploadState>,
        val writes: List<DeviceWriteResult>,
    )

    /** A board with the OTS service, and with [smp] when it is not null. */
    private fun TestScope.newFixture(smp: FakeSmpBoard? = FakeSmpBoard(), installSmp: Boolean = true): Fixture {
        val falconEngine = FakeBlueFalconEngine(backgroundScope).apply {
            emitManagerState(BluetoothManagerState.Ready)
            connectionStateResult = BluetoothPeripheralState.Connected
        }
        val board = FakeOtsBoard(extraServices = listOfNotNull(smp?.service)).apply {
            setContent("device", ProtoBuf.encodeToByteArray(snapshotpb.v1.Device.serializer(), BOARD_DEVICE))
            setContent("device_config", ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), BOARD_CONFIG))
            setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), BOARD_PROFILES))
            install(falconEngine)
        }
        if (installSmp) smp?.install(falconEngine)
        val seams = FakePlatformBluetoothSeams().apply { setDevices(mapOf(DEVICE_ID to board.peripheral)) }
        val stateManager = BluetoothStateManager().apply { update(BluetoothState.Ready) }
        val writeBus = DeviceWriteResultBus()
        val uploads = mutableListOf<FirmwareUploadState>()
        val writes = mutableListOf<DeviceWriteResult>()
        backgroundScope.launch { stateManager.firmwareUpload.collect { uploads += it } }
        backgroundScope.launch { writeBus.results.collect { writes += it } }
        val engine = BluetoothConnectionEngine(
            blueFalcon = BlueFalcon(falconEngine),
            bluetoothStateManager = stateManager,
            bleDebugLog = BleDebugLog(),
            deviceReadResultBus = DeviceReadResultBus(),
            deviceWriteResultBus = writeBus,
            seams = seams,
            coroutineScope = backgroundScope,
        )
        return Fixture(engine, stateManager, falconEngine, board, smp, uploads, writes)
    }

    private fun TestScope.connect(f: Fixture) {
        f.engine.connect(DEVICE_ID)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value, "the connect should succeed")
        assertTrue(f.stateManager.connectionProgress.value.isCompleted)
        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
    }

    private fun Fixture.service(): BoardUpdateService? = stateManager.updateServices.value[BOARD_ID]

    private fun Fixture.lastUpload(): FirmwareUploadState = uploads.last()

    @Test
    fun connect_withSmp_recordsTheTargetTheBufferSizeAndTheRunningImage() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        connect(f)

        val service = assertNotNull(f.service())
        assertEquals(UpdateServiceStatus.AVAILABLE, service.status)
        assertEquals(HardwareTarget("snapshot_v2", qualifiers = "esp32c3"), service.target)
        assertEquals(FakeSmpBoard.BUFFER_SIZE, service.bufferSize)
        assertEquals(BoardImage("26.29.0", ImageHash(FakeSmpBoard.OLD_HASH).toHex(), confirmed = true), service.activeImage)
        assertEquals(FirmwareVersion(2026, 29, 0, dev = false), service.activeImage?.firmwareVersion)
        assertEquals(
            listOf(
                SmpGroup.OS to OsCommand.INFO,
                SmpGroup.OS to OsCommand.MCUMGR_PARAMS,
                SmpGroup.IMAGE to ImageCommand.STATE,
            ),
            f.smp?.commands,
        )
    }

    @Test
    fun connect_withoutSmp_connectsAndRecordsNoUpdateService() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(smp = null)

        connect(f)

        assertEquals(BoardUpdateService(UpdateServiceStatus.ABSENT), f.service())
    }

    @Test
    fun connect_whenOsInfoFails_connectsAndRecordsNoTarget() = runTest(UnconfinedTestDispatcher()) {
        val smp = FakeSmpBoard().apply { refuseOsInfo = true }
        val f = newFixture(smp)

        connect(f)

        assertEquals(BoardUpdateService(UpdateServiceStatus.FAILED), f.service())
        // The failed session gives its subscription back.
        assertTrue(f.falconEngine.notifyCalls.contains(smp.characteristic to false))
    }

    @Test
    fun connect_whenTheBoardDoesNotAnswer_connectsAfterTheTimeLimit() = runTest(UnconfinedTestDispatcher()) {
        val smp = FakeSmpBoard()
        val f = newFixture(smp, installSmp = false)
        // Each write reaches the board, but no answer comes.
        f.falconEngine.onTypedWrite = { _, _, _ -> CharacteristicWriteResult.Sent }

        connect(f)

        assertEquals(BoardUpdateService(UpdateServiceStatus.FAILED), f.service())
    }

    @Test
    fun connect_onAnEngineWithNoTypedWrite_recordsAnUnsupportedPlatform() = runTest(UnconfinedTestDispatcher()) {
        // With no typed-write answer, the fake engine reports Unsupported, as JsEngine does.
        val f = newFixture(FakeSmpBoard(), installSmp = false)

        connect(f)

        assertEquals(BoardUpdateService(UpdateServiceStatus.UNSUPPORTED_PLATFORM), f.service())
    }

    @Test
    fun connect_whenTheBoardHasNoParams_usesNoBufferSize() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(FakeSmpBoard().apply { refuseParams = true })

        connect(f)

        val service = assertNotNull(f.service())
        assertEquals(UpdateServiceStatus.AVAILABLE, service.status)
        assertNull(service.bufferSize)
    }

    @Test
    fun aSecondConnectOnTheLiveLinkKeepsTheSession() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)
        val commands = f.smp?.commands?.size

        connect(f)

        assertEquals(commands, f.smp?.commands?.size, "the live link sends no SMP command again")
        assertEquals(UpdateServiceStatus.AVAILABLE, f.service()?.status)
    }

    @Test
    fun disconnect_removesTheUpdateService() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)

        f.engine.disconnect(DEVICE_ID)

        assertNull(f.service())
    }

    @Test
    fun disconnect_duringTheUpdateServiceRead_closesTheSessionAndLeavesNoEntry() = runTest(UnconfinedTestDispatcher()) {
        val smp = FakeSmpBoard().apply { osInfoGate = CompletableDeferred() }
        val f = newFixture(smp)
        f.engine.connect(DEVICE_ID)
        advanceTimeBy(PARK_MILLIS)
        // The board is connected, and the engine waits for the answer to `os info`.
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(UpdateServiceStatus.CHECKING, f.service()?.status)

        f.engine.disconnect(DEVICE_ID)
        runCurrent()

        assertNull(f.service())
        assertFalse(BOARD_ID in f.stateManager.connectedDevices.value)
        assertTrue(f.falconEngine.notifyCalls.contains(smp.characteristic to false))
    }

    @Test
    fun disconnect_duringAnUpload_stopsItWithLinkLost() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)
        val smp = assertNotNull(f.smp)
        smp.uploadGate = CompletableDeferred()
        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 3L)
        runCurrent()

        f.engine.disconnect(DEVICE_ID)
        // A closed link refuses each later write.
        smp.writeResult = CharacteristicWriteResult.Disconnected
        smp.uploadGate?.complete(Unit)
        advanceTimeBy(UPLOAD_MILLIS)
        runCurrent()

        val failed = assertIs<FirmwareUpdateProgress.Failed>(f.lastUpload().progress)
        assertEquals(FirmwareUpdateFailure.LINK_LOST, failed.reason)
        assertEquals(0, smp.resetCount)
        assertNull(f.service())
    }

    @Test
    fun updateFirmware_uploadsTestsAndResets() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)
        val smp = assertNotNull(f.smp)
        val commandsBefore = smp.commands.size

        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 5L)
        advanceTimeBy(UPLOAD_MILLIS)
        runCurrent()

        val last = f.lastUpload()
        assertEquals(5L, last.requestId)
        assertEquals(BOARD_ID, last.deviceId)
        assertIs<FirmwareUpdateProgress.AwaitingSwap>(last.progress)
        assertEquals(ImageHash(FakeSmpBoard.NEW_HASH).toHex(), last.uploadedImageHash)
        assertContentEquals(IMAGE.bytes, smp.imageBoard.written)
        assertEquals(1, smp.resetCount)
        val steps = f.uploads.filter { it.requestId == 5L }.map { it.progress }
        assertIs<FirmwareUpdateProgress.Preparing>(steps.first())
        assertTrue(steps.any { it is FirmwareUpdateProgress.Uploading })
        assertTrue(steps.contains(FirmwareUpdateProgress.Resetting))
        val sequence = smp.commands.drop(commandsBefore).distinct()
        assertEquals(
            listOf(
                SmpGroup.IMAGE to ImageCommand.STATE,
                SmpGroup.IMAGE to ImageCommand.UPLOAD,
                SmpGroup.OS to OsCommand.RESET,
            ),
            sequence,
        )
        // The board got the buffer size of mcumgr_params: each chunk frame fills it.
        assertTrue(smp.requests.filter { it.header.command == ImageCommand.UPLOAD }.all { it.encode().size <= FakeSmpBoard.BUFFER_SIZE })
        assertTrue(smp.requests.any { it.header.operation == SmpOperation.Write && it.header.command == ImageCommand.STATE })
        // High priority for the upload, then balanced again.
        assertEquals(listOf(ConnectionPriority.High, ConnectionPriority.Balanced), f.falconEngine.connectionPriorities.takeLast(2))
    }

    @Test
    fun updateFirmware_whenNotConnected_failsWithNotConnected() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 1L)
        runCurrent()

        assertEquals(FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.NOT_CONNECTED), f.lastUpload().progress)
    }

    @Test
    fun updateFirmware_withoutSmp_failsWithNoUpdateService() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(smp = null)
        connect(f)

        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 1L)
        runCurrent()

        assertEquals(FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.NO_UPDATE_SERVICE), f.lastUpload().progress)
    }

    @Test
    fun updateFirmware_whenTheBoardRefusesAChunk_failsWithBoardRefused() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)
        val smp = assertNotNull(f.smp)
        smp.imageBoard = FakeImageBoard(refuseAt = 0L)

        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 1L)
        advanceTimeBy(UPLOAD_MILLIS)
        runCurrent()

        val failed = assertIs<FirmwareUpdateProgress.Failed>(f.lastUpload().progress)
        assertEquals(FirmwareUpdateFailure.BOARD_REFUSED, failed.reason)
        assertEquals("SmpGroupError(NO_FREE_SLOT)", failed.detail)
        assertEquals(0, smp.resetCount)
    }

    @Test
    fun updateFirmware_whenTheBoardDoesNotMarkTheImage_failsWithBoardRefused() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(FakeSmpBoard().apply { ignoreTest = true })
        connect(f)

        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 1L)
        advanceTimeBy(UPLOAD_MILLIS)
        runCurrent()

        val failed = assertIs<FirmwareUpdateProgress.Failed>(f.lastUpload().progress)
        assertEquals(FirmwareUpdateFailure.BOARD_REFUSED, failed.reason)
        assertEquals(0, f.smp?.resetCount, "the board does not reset to an image that it did not mark")
    }

    @Test
    fun updateFirmware_whenTheLinkDrops_failsWithLinkLost() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)
        val smp = assertNotNull(f.smp)
        smp.uploadGate = CompletableDeferred()

        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 1L)
        runCurrent()
        // The first chunk is on the board. The link then drops.
        smp.writeResult = CharacteristicWriteResult.Disconnected
        smp.uploadGate?.complete(Unit)
        advanceTimeBy(UPLOAD_MILLIS)
        runCurrent()

        val failed = assertIs<FirmwareUpdateProgress.Failed>(f.lastUpload().progress)
        assertEquals(FirmwareUpdateFailure.LINK_LOST, failed.reason)
        assertEquals(ConnectionPriority.Balanced, f.falconEngine.connectionPriorities.last())
    }

    @Test
    fun updateFirmware_holdsTheWriteLockForTheWholeUpload() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)
        val smp = assertNotNull(f.smp)
        smp.uploadGate = CompletableDeferred()
        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 1L)
        runCurrent()
        val otsOpcodes = f.board.olcpOpcodes.size

        val write = launch { f.engine.writeDeviceConfiguration(BOARD_ID, boardDevice()) }
        advanceTimeBy(PARK_MILLIS)

        // The write waits: no OTS read (the check for a board edit) and no OTS write runs while the
        // board holds fire control for the upload.
        assertTrue(write.isActive)
        assertEquals(otsOpcodes, f.board.olcpOpcodes.size)
        assertTrue(f.board.writtenObjects.isEmpty())
        assertTrue(f.writes.isEmpty())

        smp.uploadGate?.complete(Unit)
        advanceTimeBy(UPLOAD_MILLIS + WRITE_MILLIS)
        runCurrent()

        assertIs<FirmwareUpdateProgress.AwaitingSwap>(f.lastUpload().progress)
        assertFalse(write.isActive)
        assertEquals(listOf("device_config", "profiles"), f.board.writtenObjects)
    }

    @Test
    fun confirmFirmware_confirmsAnImageInTest() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(FakeSmpBoard().apply { activeConfirmed = false })
        connect(f)
        assertEquals(false, f.service()?.activeImage?.confirmed)

        f.engine.confirmFirmware(BOARD_ID, requestId = 9L)
        runCurrent()

        assertEquals(9L, f.lastUpload().requestId)
        assertEquals(FirmwareUpdateProgress.Done(FirmwareVersion(2026, 29, 0, dev = false)), f.lastUpload().progress)
        assertEquals(true, f.smp?.activeConfirmed)
        assertEquals(true, f.service()?.activeImage?.confirmed)
    }

    @Test
    fun confirmFirmware_whenTheFirmwareConfirmedItself_sendsNoConfirm() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)
        val smp = assertNotNull(f.smp)

        f.engine.confirmFirmware(BOARD_ID, requestId = 9L)
        runCurrent()

        assertIs<FirmwareUpdateProgress.Done>(f.lastUpload().progress)
        assertTrue(smp.requests.none { it.header.operation == SmpOperation.Write })
    }

    @Test
    fun confirmFirmware_whenNotConnected_failsWithNotConnected() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        f.engine.confirmFirmware(BOARD_ID, requestId = 9L)
        runCurrent()

        assertEquals(FirmwareUpdateProgress.Failed(FirmwareUpdateFailure.NOT_CONNECTED), f.lastUpload().progress)
    }

    private fun boardDevice() = toDomainDevice(
        protoDevice = BOARD_DEVICE,
        protoDeviceConfig = BOARD_CONFIG,
        protoProfiles = BOARD_PROFILES,
        deviceName = "Board",
        lastSeenDate = LocalDate(2026, 10, 23),
    )

    private companion object {
        const val DEVICE_ID = "5350425f-4f54-4000-8000-000000000001"
        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val CONNECT_MILLIS = 30_000L
        const val UPLOAD_MILLIS = 60_000L
        const val WRITE_MILLIS = 20_000L
        const val PARK_MILLIS = 4_000L

        val BOARD_DEVICE = snapshotpb.v1.Device(
            deviceId = Uuid.parse(BOARD_ID).toByteArray(),
            model = BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 4u,
        )
        val BOARD_CONFIG = DeviceConfig(activeProfile = 0u, bootText = "HELLO")
        val BOARD_PROFILES = Profiles(profiles = listOf(Profile(name = "P0")))

        /** An image of three chunk frames. The fake board does not check the hash of the file. */
        val IMAGE = FirmwareImage(
            manifest = FirmwareManifest(
                version = "2026.43.1",
                compatibleHardware = listOf("snapshot_v2"),
                assets = FirmwareAssets(
                    image = FirmwareAsset(
                        name = "snapshot_v2-2026.43.1-app.signed.bin",
                        url = "https://example.invalid/image.bin",
                        size = 6_000,
                        sha256 = "ab".repeat(32),
                    ),
                ),
            ),
            bytes = ByteArray(6_000) { (it % 251).toByte() },
        )
    }
}
