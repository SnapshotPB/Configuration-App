package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.eye.EyeStatusUuids
import com.snapshotpb.configuration.bluetooth.smp.BlueFalconSmpTransport
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import com.snapshotpb.configuration.model.FirmwareAsset
import com.snapshotpb.configuration.model.FirmwareAssets
import com.snapshotpb.configuration.model.FirmwareImage
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import snapshotpb.v1.BoardModel
import snapshotpb.v1.DeviceConfig
import snapshotpb.v1.EyeStatus
import snapshotpb.v1.Profile
import snapshotpb.v1.Profiles
import snapshotpb.v1.EyeSensorState as ProtoEyeSensorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Engine tests for live eye status lookup, session notifications, upload pause, and teardown. */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionEngineEyeStatusTest {

    private class Fixture(
        val engine: BluetoothConnectionEngine,
        val stateManager: BluetoothStateManager,
        val falconEngine: FakeBlueFalconEngine,
        val seams: FakePlatformBluetoothSeams,
        val log: RecordingBluetoothLog,
        val smp: FakeSmpBoard?,
        val eyeService: FakeEyeStatusService?,
    ) {
        fun eyeNotifyStates(): List<Boolean> = falconEngine.notifyCalls
            .filter { (characteristic, _) -> characteristic.uuid == EyeStatusUuids.Status }
            .map { (_, notify) -> notify }
    }

    private fun TestScope.newFixture(
        eyeService: FakeEyeStatusService? = FakeEyeStatusService(),
        smp: FakeSmpBoard? = null,
        extraServices: List<BluetoothService> = emptyList(),
        bluetoothLog: RecordingBluetoothLog = RecordingBluetoothLog(),
        servicesFollowPermission: Boolean = false,
    ): Fixture {
        val falconEngine = FakeBlueFalconEngine(backgroundScope).apply {
            emitManagerState(BluetoothManagerState.Ready)
            connectionStateResult = BluetoothPeripheralState.Connected
        }
        val board = FakeOtsBoard(
            extraServices = listOfNotNull(eyeService?.service, smp?.service) + extraServices,
        ).apply {
            setContent("device", ProtoBuf.encodeToByteArray(snapshotpb.v1.Device.serializer(), BOARD_DEVICE))
            setContent("device_config", ProtoBuf.encodeToByteArray(DeviceConfig.serializer(), BOARD_CONFIG))
            setContent("profiles", ProtoBuf.encodeToByteArray(Profiles.serializer(), BOARD_PROFILES))
            install(falconEngine)
        }
        smp?.install(falconEngine)
        val seams = FakePlatformBluetoothSeams().apply {
            this.servicesFollowPermission = servicesFollowPermission
            setDevices(mapOf(DEVICE_ID to board.peripheral))
        }
        val stateManager = BluetoothStateManager().apply { update(BluetoothState.Ready) }
        val engine = BluetoothConnectionEngine(
            blueFalcon = BlueFalcon(falconEngine),
            bluetoothStateManager = stateManager,
            bluetoothLog = bluetoothLog,
            deviceReadResultBus = DeviceReadResultBus(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            seams = seams,
            coroutineScope = backgroundScope,
        )
        return Fixture(
            engine = engine,
            stateManager = stateManager,
            falconEngine = falconEngine,
            seams = seams,
            log = bluetoothLog,
            smp = smp,
            eyeService = eyeService,
        )
    }

    private fun TestScope.connect(f: Fixture) {
        f.engine.connect(DEVICE_ID)
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value, "the connect must succeed")
        assertTrue(f.stateManager.connectionProgress.value.isCompleted)
    }

    private fun TestScope.startWatcher(
        stateManager: BluetoothStateManager,
    ): Pair<Job, MutableList<EyeReadingState?>> {
        val states = mutableListOf<EyeReadingState?>()
        val job = backgroundScope.launch {
            stateManager.eyeReading(BOARD_ID).collect { states += it }
        }
        return job to states
    }

    private fun encodeEyeStatus(
        levelRaw: UInt? = 2048U,
        fullScaleRaw: UInt = 4095U,
        state: ProtoEyeSensorState = ProtoEyeSensorState.EYE_SENSOR_STATE_ACTIVE,
    ): ByteArray = ProtoBuf.encodeToByteArray(
        EyeStatus.serializer(),
        EyeStatus(levelRaw = levelRaw, fullScaleRaw = fullScaleRaw, state = state),
    )

    @Test
    fun noEyeService_connectsAndRecordsUnsupported() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(eyeService = null)
        val (_, states) = startWatcher(f.stateManager)

        connect(f)

        assertEquals(EyeReadingState.Unsupported, states.last())
        assertTrue("app(1, EYE_UNAVAILABLE, absent)" in f.log.calls)
    }

    @Test
    fun noEyeService_whenServicesFollowPermission_recordsNotPermitted() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(eyeService = null, servicesFollowPermission = true)
        val (_, states) = startWatcher(f.stateManager)

        connect(f)

        assertEquals(EyeReadingState.NotPermitted, states.last())
        assertTrue("app(1, EYE_UNAVAILABLE, not_visible)" in f.log.calls)
    }

    @Test
    fun eyeServiceWithoutStatusCharacteristic_connectsAndRecordsFailed() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(eyeService = FakeEyeStatusService(withStatus = false))
        val (_, states) = startWatcher(f.stateManager)

        connect(f)

        assertEquals(EyeReadingState.Failed, states.last())
        assertTrue("app(1, EYE_SUBSCRIBE_FAILED, no_characteristic)" in f.log.calls)
    }

    @Test
    fun eyeLookupTimeout_recordsFailedAndKeepsBoardConnected() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture(bluetoothLog = RecordingBluetoothLog(recordsBoardLines = false))
        f.seams.serviceWithCharacteristicsGate = CompletableDeferred()
        val (_, states) = startWatcher(f.stateManager)

        f.engine.connect(DEVICE_ID)
        advanceTimeBy(2_999L)
        runCurrent()
        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(EyeReadingState.Waiting, states.last())

        advanceTimeBy(1L)
        runCurrent()

        assertTrue(BOARD_ID in f.stateManager.connectedDevices.value)
        assertEquals(EyeReadingState.Failed, states.last())
        assertTrue("app(1, EYE_SUBSCRIBE_FAILED, timeout)" in f.log.calls)
    }

    @Test
    fun withEyeServiceAndNoWatcher_writesNoEyeCccAfterTenSeconds() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(f.eyeNotifyStates().isEmpty())
    }

    @Test
    fun firstWatcher_enablesEyeNotificationsAndStalesToWaiting() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val eyeService = assertNotNull(f.eyeService)
        connect(f)

        val (_, states) = startWatcher(f.stateManager)
        runCurrent()
        assertEquals(listOf(true), f.eyeNotifyStates())

        val expected = EyeReading(levelRaw = 1200, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE)
        eyeService.send(encodeEyeStatus(levelRaw = 1200U, fullScaleRaw = 4095U))
        runCurrent()
        assertEquals(EyeReadingState.Reading(expected), states.last())

        advanceTimeBy(EyeStatusSession.READING_STALE_MILLIS - 1L)
        runCurrent()
        assertEquals(EyeReadingState.Reading(expected), states.last())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(EyeReadingState.Waiting, states.last())
    }

    @Test
    fun lastWatcherStops_disablesEyeNotificationsAfterTwoSeconds() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        connect(f)

        val (watcher, _) = startWatcher(f.stateManager)
        runCurrent()
        assertEquals(listOf(true), f.eyeNotifyStates())

        watcher.cancelAndJoin()
        advanceTimeBy(EyeStatusSession.UNSUBSCRIBE_DELAY_MILLIS - 1L)
        runCurrent()
        assertEquals(listOf(true), f.eyeNotifyStates())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(true, false), f.eyeNotifyStates())
    }

    @Test
    fun firmwareUpload_pausesEyeNotificationsWhileParkedAndResumesAfterUpload() = runTest(UnconfinedTestDispatcher()) {
        val smp = FakeSmpBoard().apply { uploadGate = CompletableDeferred() }
        val f = newFixture(smp = smp)
        connect(f)

        startWatcher(f.stateManager)
        runCurrent()
        assertEquals(listOf(true), f.eyeNotifyStates())

        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 1L)
        runCurrent()
        assertEquals(listOf(true, false), f.eyeNotifyStates())
        assertTrue("app(1, EYE_UNSUBSCRIBED, firmware)" in f.log.calls)

        // Stay below the 10 s SMP write limit so the parked upload stays in flight.
        advanceTimeBy(BlueFalconSmpTransport.BACKPRESSURE_LIMIT_MILLIS - 1L)
        runCurrent()
        assertEquals(listOf(true, false), f.eyeNotifyStates())

        smp.uploadGate?.complete(Unit)
        advanceTimeBy(UPLOAD_MILLIS)
        runCurrent()
        assertIs<FirmwareUpdateProgress.AwaitingSwap>(f.stateManager.firmwareUpload.value.progress)
        assertEquals(listOf(true, false, true), f.eyeNotifyStates())
    }

    @Test
    fun watcherAddedDuringParkedUpload_enablesOnlyAfterUploadEnds() = runTest(UnconfinedTestDispatcher()) {
        val smp = FakeSmpBoard().apply { uploadGate = CompletableDeferred() }
        val f = newFixture(smp = smp)
        connect(f)
        assertTrue(f.eyeNotifyStates().isEmpty())

        f.engine.updateFirmware(BOARD_ID, IMAGE, requestId = 2L)
        runCurrent()

        startWatcher(f.stateManager)
        // Stay below the 10 s SMP write limit so the parked upload stays in flight.
        advanceTimeBy(BlueFalconSmpTransport.BACKPRESSURE_LIMIT_MILLIS - 1L)
        runCurrent()
        assertTrue(f.eyeNotifyStates().isEmpty())

        smp.uploadGate?.complete(Unit)
        advanceTimeBy(UPLOAD_MILLIS)
        runCurrent()
        assertIs<FirmwareUpdateProgress.AwaitingSwap>(f.stateManager.firmwareUpload.value.progress)
        assertEquals(listOf(true), f.eyeNotifyStates())
    }

    @Test
    fun disconnect_clearsEyeStateAndIgnoresLateNotification() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val eyeService = assertNotNull(f.eyeService)
        connect(f)

        val (_, states) = startWatcher(f.stateManager)
        runCurrent()
        val expected = EyeReading(levelRaw = 1500, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE)
        eyeService.send(encodeEyeStatus(levelRaw = 1500U, fullScaleRaw = 4095U))
        runCurrent()
        assertEquals(EyeReadingState.Reading(expected), states.last())

        f.engine.disconnect(DEVICE_ID)
        runCurrent()
        assertNull(states.last())

        eyeService.send(encodeEyeStatus(levelRaw = 3000U, fullScaleRaw = 4095U))
        runCurrent()

        f.stateManager.deviceConnected(BOARD_ID)
        runCurrent()
        assertEquals(EyeReadingState.Waiting, states.last())
    }

    @Test
    fun linkLoss_clearsEyeStateAndIgnoresLateNotification() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val eyeService = assertNotNull(f.eyeService)
        connect(f)

        val (_, states) = startWatcher(f.stateManager)
        runCurrent()
        val expected = EyeReading(levelRaw = 1500, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE)
        eyeService.send(encodeEyeStatus(levelRaw = 1500U, fullScaleRaw = 4095U))
        runCurrent()
        assertEquals(EyeReadingState.Reading(expected), states.last())

        f.falconEngine.connectionStateResult = BluetoothPeripheralState.Disconnected
        advanceTimeBy(LINK_LOSS_POLL_MILLIS)
        runCurrent()
        assertNull(states.last())

        eyeService.send(encodeEyeStatus(levelRaw = 3000U, fullScaleRaw = 4095U))
        runCurrent()

        f.stateManager.deviceConnected(BOARD_ID)
        runCurrent()
        assertEquals(EyeReadingState.Waiting, states.last())
    }

    @Test
    fun secondConnectOnLiveLink_keepsEyeSessionAndReading() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val eyeService = assertNotNull(f.eyeService)
        connect(f)

        val (_, states) = startWatcher(f.stateManager)
        runCurrent()
        val expected = EyeReading(levelRaw = 2200, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE)
        eyeService.send(encodeEyeStatus(levelRaw = 2200U, fullScaleRaw = 4095U))
        runCurrent()
        assertEquals(EyeReadingState.Reading(expected), states.last())

        f.engine.connect(DEVICE_ID)
        runCurrent()

        assertEquals(listOf(true), f.eyeNotifyStates())
        assertEquals(EyeReadingState.Reading(expected), states.last())
    }

    private companion object {
        const val DEVICE_ID = "5350425f-4f54-4000-8000-000000000001"
        const val BOARD_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val CONNECT_MILLIS = 30_000L
        const val UPLOAD_MILLIS = 60_000L
        const val LINK_LOSS_POLL_MILLIS = 5_000L

        val BOARD_DEVICE = snapshotpb.v1.Device(
            deviceId = Uuid.parse(BOARD_ID).toByteArray(),
            model = BoardModel.BOARD_MODEL_AUTOCOCKER,
            supportedProfileCount = 4u,
        )
        val BOARD_CONFIG = DeviceConfig(activeProfile = 0u, bootText = "HELLO")
        val BOARD_PROFILES = Profiles(profiles = listOf(Profile(name = "P0")))

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
