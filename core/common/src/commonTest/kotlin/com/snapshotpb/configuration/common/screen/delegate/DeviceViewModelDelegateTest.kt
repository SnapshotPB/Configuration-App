package com.snapshotpb.configuration.common.screen.delegate

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BoardChangedBeforeWriteException
import com.snapshotpb.configuration.bluetooth.DeviceWriteResult
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.common.InMemoryStoreIO
import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.common.testDeviceStore
import com.snapshotpb.configuration.common.testProfile
import com.snapshotpb.configuration.common.testProfileId
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.DisplayWindow
import com.snapshotpb.configuration.model.GripFrame
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.configuration.model.RATE_CAP_MIN
import com.snapshotpb.configuration.model.TENTHS_MS_MAX
import com.snapshotpb.configuration.model.TRIGGER_DEBOUNCE_DEFAULT
import com.snapshotpb.configuration.model.withProfilesInWrittenSlots
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.configuration.store.appJson
import com.snapshotpb.configuration.store.storedDevicePhotoName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Cover for the write-result reconciliation state machine — the code that decides whether a board
 * write clears the "unsaved" badge, adopts a firmware clamp, or preserves an edit that landed while
 * the write was in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceViewModelDelegateTest {

    private fun boardState(
        bootText: String = "SnapshotPB",
        dwell: Int = 45,
        language: BoardLanguage? = null,
        screenBrightness: BoardScreenBrightness? = null,
        screenOffDelay: Int? = null,
        displayWindow: DisplayWindow? = null,
        boltMode: MarkerBoltMode? = null,
    ) = BoardState(
        bootText = bootText,
        activeProfileId = null,
        lockoutEnabled = false,
        knownProfiles = listOf(testProfile(searSolenoidDwell = dwell)),
        language = language,
        screenBrightness = screenBrightness,
        screenOffDelay = screenOffDelay,
        displayWindow = displayWindow,
        boltMode = boltMode,
    )

    private class Fixture(
        val store: DeviceStore,
        val bus: DeviceWriteResultBus,
        val requestManager: BluetoothRequestManager = BluetoothRequestManager(),
        photoStamp: () -> Long = { 0L },
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    ) {
        val delegate: DeviceViewModelDelegate
        init {
            delegate = DeviceViewModelDelegateImpl(
                deviceStore = store,
                bluetoothRequestManager = requestManager,
                deviceWriteResultBus = bus,
                dispatcher = dispatcher,
                photoStamp = photoStamp,
            )
        }
    }

    private fun fixture(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, device: com.snapshotpb.configuration.model.Device) =
        Fixture(testDeviceStore(UnconfinedTestDispatcher(scheduler), listOf(device)), DeviceWriteResultBus())

    @Test
    fun updateDevice_givesEachNewPhotoANewPathAndDeletesTheReplacedFile() = runTest {
        // One fixed file name for every photo kept the path the same after a new photo. The
        // store then did not emit, and the watch and Coil kept the old image.
        val io = InMemoryStoreIO(mapOf("devices.json" to appJson.encodeToString(listOf(testDevice()))))
        val store = DeviceStore(io, appJson, UnconfinedTestDispatcher(testScheduler))
        var stamp = 100L
        val f = Fixture(store, DeviceWriteResultBus(), photoStamp = { stamp++ })
        testScheduler.advanceUntilIdle()
        val baseName = "device_photo_$testDeviceId"

        f.delegate.updateDevice(testDeviceId.toString(), "Marker", Photo(uri = "content://first"))
        testScheduler.advanceUntilIdle()
        val firstPath = store.knownDevices.value.single().photoPath
        assertEquals("${baseName}_100", firstPath, "the store keeps the store name, not a path")
        assertTrue(io.content.containsKey("${baseName}_100"))

        f.delegate.updateDevice(testDeviceId.toString(), "Marker", Photo(uri = "content://second"))
        testScheduler.advanceUntilIdle()
        val secondPath = store.knownDevices.value.single().photoPath
        assertNotEquals(firstPath, secondPath, "a new photo must change the path")
        assertEquals("${baseName}_101", secondPath)
        assertFalse(io.content.containsKey("${baseName}_100"), "the replaced file must be deleted")
        assertTrue(io.content.containsKey("${baseName}_101"))
    }

    @Test
    fun storedDevicePhotoName_acceptsOnlyThePhotoFilesOfTheDevice() {
        val id = testDeviceId.toString()
        assertEquals("device_photo_$id", storedDevicePhotoName(id, "file:///data/device_photo_$id"))
        assertEquals(
            "device_photo_${id}_42",
            storedDevicePhotoName(id, "file:///data/device_photo_${id}_42"),
        )
        // The store now keeps the store name only.
        assertEquals("device_photo_${id}_42", storedDevicePhotoName(id, "device_photo_${id}_42"))
        assertNull(storedDevicePhotoName(id, ""))
        assertNull(storedDevicePhotoName(id, "file:///data/devices.json"))
        assertNull(storedDevicePhotoName(id, "file:///data/device_photo_other"))
    }

    @Test
    fun saveChangesToBoard_sendsOneWriteForADoubleTap() = runTest {
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
        val requestManager = BluetoothRequestManager()
        val f = Fixture(
            testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(device)),
            DeviceWriteResultBus(),
            requestManager,
        )
        val writes = mutableListOf<BluetoothRequest>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestManager.bus.collect { writes += it }
        }
        testScheduler.advanceUntilIdle()

        f.delegate.saveChangesToBoard(testDeviceId.toString())
        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.advanceUntilIdle()

        assertEquals(1, writes.filterIsInstance<BluetoothRequest.WriteDevice>().size)
        assertEquals(setOf(testDeviceId), f.delegate.writesInFlight.value)

        // The result ends the write, so the next Save sends again.
        val requestId = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single().requestId
        f.bus.emit(DeviceWriteResult(device = device, error = null, requestedDevice = device, requestId = requestId))
        testScheduler.advanceUntilIdle()
        assertTrue(f.delegate.writesInFlight.value.isEmpty())
        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.advanceUntilIdle()
        assertEquals(2, writes.filterIsInstance<BluetoothRequest.WriteDevice>().size)
        collector.cancel()
    }

    @Test
    fun saveChangesToBoard_sendsNoWriteWhileTheBoardReportsLockout() = runTest {
        // device.proto: a board in lockout does not accept a change until the user turns off the
        // lockout switch, so a save must not send a write.
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
            .copy(lockoutEnabled = true)
        val requestManager = BluetoothRequestManager()
        val f = Fixture(
            testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(device)),
            DeviceWriteResultBus(),
            requestManager,
        )
        val writes = mutableListOf<BluetoothRequest>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestManager.bus.collect { writes += it }
        }
        testScheduler.advanceUntilIdle()

        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.advanceUntilIdle()

        assertTrue(writes.filterIsInstance<BluetoothRequest.WriteDevice>().isEmpty())
        assertTrue(f.delegate.writesInFlight.value.isEmpty(), "a refused save must not hold a claim")
        assertTrue(f.store.knownDevices.value.single().hasDiverged(), "the local edit must stay")
        collector.cancel()
    }

    @Test
    fun saveChangesToBoard_keepsTheClaimUntilTheResultOfItsOwnWrite() = runTest {
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
        val requestManager = BluetoothRequestManager()
        val f = Fixture(
            testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(device)),
            DeviceWriteResultBus(),
            requestManager,
        )
        val writes = mutableListOf<BluetoothRequest>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestManager.bus.collect { writes += it }
        }
        testScheduler.advanceUntilIdle()

        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.advanceUntilIdle()
        val requestId = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single().requestId
        assertTrue(requestId > 0L, "a tracked write carries a request id")

        // The result of a conflict write (no request id) and of an older write must not end the
        // claim, or a second Save could start while this write still runs.
        f.bus.emit(DeviceWriteResult(device = device, error = null, requestedDevice = device))
        f.bus.emit(
            DeviceWriteResult(device = device, error = null, requestedDevice = device, requestId = requestId + 1)
        )
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(testDeviceId), f.delegate.writesInFlight.value)

        f.bus.emit(DeviceWriteResult(device = device, error = null, requestedDevice = device, requestId = requestId))
        testScheduler.advanceUntilIdle()
        assertTrue(f.delegate.writesInFlight.value.isEmpty())
        collector.cancel()
    }

    @Test
    fun saveResults_reportsTheResultOfEachSaveAndNothingForOtherWrites() = runTest {
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
        val requestManager = BluetoothRequestManager()
        val f = Fixture(
            testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(device)),
            DeviceWriteResultBus(),
            requestManager,
        )
        val writes = mutableListOf<BluetoothRequest>()
        val results = mutableListOf<SaveResult>()
        val writeCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestManager.bus.collect { writes += it }
        }
        val resultCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.delegate.saveResults.collect { results += it }
        }
        testScheduler.advanceUntilIdle()

        // A failed save reports a failure, and writeErrors keeps it.
        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.advanceUntilIdle()
        val failedId = writes.filterIsInstance<BluetoothRequest.WriteDevice>().last().requestId
        f.bus.emit(
            DeviceWriteResult(
                device = device,
                error = IllegalStateException("link lost"),
                requestedDevice = device,
                requestId = failedId,
            )
        )
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(SaveResult(testDeviceId, succeeded = false)), results)
        assertEquals(setOf(testDeviceId), f.delegate.writeErrors.value)

        // The write of a conflict resolution has no request id, so it reports nothing.
        f.bus.emit(DeviceWriteResult(device = device, error = null, requestedDevice = device))
        testScheduler.advanceUntilIdle()
        assertEquals(1, results.size)

        // A save that the board accepts reports a success.
        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.advanceUntilIdle()
        val savedId = writes.filterIsInstance<BluetoothRequest.WriteDevice>().last().requestId
        f.bus.emit(DeviceWriteResult(device = device, error = null, requestedDevice = device, requestId = savedId))
        testScheduler.advanceUntilIdle()
        assertEquals(SaveResult(testDeviceId, succeeded = true), results.last())
        assertEquals(2, results.size)
        assertTrue(f.delegate.writeErrors.value.isEmpty())
        writeCollector.cancel()
        resultCollector.cancel()
    }

    @Test
    fun saveChangesToBoard_recordsAFailureWhenNoResultArrivesBeforeTheTimeout() = runTest {
        // A write that is cancelled, for example by a disconnect, has no result. The user must
        // see a failed save, not only a Save button that is enabled again.
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
        val requestManager = BluetoothRequestManager()
        val f = Fixture(
            testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(device)),
            DeviceWriteResultBus(),
            requestManager,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val results = mutableListOf<SaveResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { requestManager.bus.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.delegate.saveResults.collect { results += it }
        }
        testScheduler.runCurrent()

        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.runCurrent()
        assertEquals(setOf(testDeviceId), f.delegate.writesInFlight.value)
        assertTrue(results.isEmpty())

        testScheduler.advanceTimeBy(DeviceViewModelDelegateImpl.WRITE_RESULT_TIMEOUT_MILLIS + 1)

        assertTrue(f.delegate.writesInFlight.value.isEmpty())
        assertEquals(setOf(testDeviceId), f.delegate.writeErrors.value)
        assertEquals(listOf(SaveResult(testDeviceId, succeeded = false)), results)
        assertTrue(f.store.knownDevices.value.single().hasDiverged(), "the local edit must stay")
    }

    @Test
    fun onWriteResult_success_clearsDivergenceByAdoptingWhatTheBoardStored() = runTest {
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()
        assertTrue(f.store.knownDevices.value.single().hasDiverged())

        val written = device.copy(bootText = "Edited")
        f.bus.emit(DeviceWriteResult(device = written, error = null, requestedDevice = written))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals("Edited", after.bootText)
        assertEquals("Edited", after.boardState?.bootText, "baseline must track what the board now holds")
        assertFalse(after.hasDiverged())
    }

    @Test
    fun onWriteResult_success_adoptsAFirmwareClampIntoTheEditableDevice() = runTest {
        // The user asked for dwell 99999; the firmware clamped it to 500. Editable AND baseline must
        // both become 500, or the badge would never clear (the pre-clamp finding 3b).
        val requested = testDevice(
            profiles = listOf(testProfile(searSolenoidDwell = 99_999)),
            boardState = boardState(dwell = 45),
        )
        val f = fixture(testScheduler, requested)
        testScheduler.advanceUntilIdle()

        val clamped = requested.copy(knownProfiles = listOf(testProfile(searSolenoidDwell = 500)))
        f.bus.emit(DeviceWriteResult(device = clamped, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(500, after.knownProfiles.single().searSolenoidDwell)
        assertEquals(500, after.boardState?.knownProfiles?.single()?.searSolenoidDwell)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun onWriteResult_success_keepsAnEditThatLandedWhileTheWriteWasInFlight() = runTest {
        val f = fixture(testScheduler, testDevice(bootText = "InFlight", boardState = boardState(bootText = "Old")))
        testScheduler.advanceUntilIdle()

        // The write was dispatched for "Requested" but the user has since typed "InFlight".
        val requested = testDevice(bootText = "Requested")
        f.bus.emit(DeviceWriteResult(device = requested, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals("InFlight", after.bootText, "a concurrent edit must not be silently lost")
        assertEquals("Requested", after.boardState?.bootText, "but the baseline tracks the board")
        assertTrue(after.hasDiverged(), "so the badge stays honest")
    }

    @Test
    fun onWriteResult_success_movesTheSourceSlotsOfAnInFlightEditToTheWrittenSlots() = runTest {
        // A delete moved B and C down one slot, and the write sent them to slots 0 and 1.
        val b = testProfile(id = getProfileUuid(testDeviceId, 0), name = "B").copy(sourceSlot = 1)
        val c = testProfile(id = getProfileUuid(testDeviceId, 1), name = "C").copy(sourceSlot = 2)
        val requested = testDevice(profiles = listOf(b, c))
        val written = requested.withProfilesInWrittenSlots()
        // The user edited B while the write was in flight.
        val local = requested.copy(knownProfiles = listOf(b.copy(searSolenoidDwell = 99), c))
        val f = fixture(testScheduler, local.copy(boardState = boardState()))
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device = written, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(99, after.knownProfiles[0].searSolenoidDwell, "the in-flight edit is kept")
        // The board now holds B in slot 0 and C in slot 1, so the next write must pair them there.
        assertEquals(listOf(0, 1), after.knownProfiles.map { it.sourceSlot })
    }

    @Test
    fun onWriteResult_failure_leavesLocalEditsAndFlagsTheDevice() = runTest {
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.bus.emit(
            DeviceWriteResult(
                device = device,
                error = IllegalStateException("link lost"),
                requestedDevice = device,
            )
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals("Edited", after.bootText)
        assertEquals("Old", after.boardState?.bootText)
        assertTrue(after.hasDiverged())
        assertTrue(testDeviceId in f.delegate.writeErrors.value)
    }

    @Test
    fun onWriteResult_boardChangedBeforeTheWrite_keepsTheEditsWithNoSaveFailedHint() = runTest {
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
        val requestManager = BluetoothRequestManager()
        val f = Fixture(
            testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(device)),
            DeviceWriteResultBus(),
            requestManager,
        )
        val writes = mutableListOf<BluetoothRequest>()
        val results = mutableListOf<SaveResult>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestManager.bus.collect { writes += it }
        }
        val resultCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.delegate.saveResults.collect { results += it }
        }
        testScheduler.advanceUntilIdle()
        // An earlier Save failed, so the "save failed" hint shows.
        f.bus.emit(DeviceWriteResult(device, error = IllegalStateException("boom"), requestedDevice = device))
        testScheduler.advanceUntilIdle()
        assertTrue(testDeviceId in f.delegate.writeErrors.value)
        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.advanceUntilIdle()
        val requestId = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single().requestId

        f.bus.emit(
            DeviceWriteResult(
                device = device,
                error = BoardChangedBeforeWriteException(testDeviceId.toString()),
                requestedDevice = device,
                requestId = requestId,
            )
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals("Edited", after.bootText, "the local edit stays for the conflict choice")
        assertEquals("Old", after.boardState?.bootText)
        // The conflict dialog tells the user. A "save failed" hint, new or old, would also offer
        // a retry.
        assertFalse(testDeviceId in f.delegate.writeErrors.value)
        assertTrue(results.isEmpty(), "the watch must not show \"Save failed\" over the conflict")
        assertTrue(f.delegate.writesInFlight.value.isEmpty(), "the result still ends the write")
        collector.cancel()
        resultCollector.cancel()
    }

    @Test
    fun updateDeviceConfiguration_clearsAStaleWriteError() = runTest {
        val device = testDevice(boardState = boardState())
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device, error = IllegalStateException("boom"), requestedDevice = device))
        testScheduler.advanceUntilIdle()
        assertTrue(testDeviceId in f.delegate.writeErrors.value)

        f.delegate.updateDeviceConfiguration(
            deviceId = testDeviceId.toString(),
            name = "Renamed",
            bootText = "Boot",
            activeProfileId = null,
            language = null,
            screenBrightness = null,
            screenOffDelay = null,
            displayWindow = null,
        )
        testScheduler.advanceUntilIdle()

        assertFalse(testDeviceId in f.delegate.writeErrors.value, "a new edit supersedes the old failure")
        assertEquals("Renamed", f.store.knownDevices.value.single().name)
    }

    @Test
    fun updateDeviceConfiguration_appliesTheSharedInputLimits() = runTest {
        val f = fixture(testScheduler, testDevice())
        testScheduler.advanceUntilIdle()

        f.delegate.updateDeviceConfiguration(
            deviceId = testDeviceId.toString(),
            name = "AVeryLongDeviceName",
            bootText = "Bóót Téxt Is Long",
            activeProfileId = null,
            language = null,
            screenBrightness = null,
            screenOffDelay = null,
            displayWindow = null,
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(10, after.name.length, "device name caps at MAX_NAME_LENGTH")
        assertTrue(after.bootText.all { it.code <= 127 }, "boot text is ASCII-only")
        assertTrue(after.bootText.length <= 10)
    }

    @Test
    fun updateDeviceConfiguration_storesTheLanguageChoiceAsAnUnsavedChange() = runTest {
        val board = boardState(language = BoardLanguage.EN)
        val f = fixture(testScheduler, testDevice(language = BoardLanguage.EN, boardState = board))
        testScheduler.advanceUntilIdle()

        f.delegate.updateDeviceConfiguration(
            deviceId = testDeviceId.toString(),
            name = "Marker",
            bootText = "SnapshotPB",
            activeProfileId = null,
            language = BoardLanguage.DE,
            screenBrightness = null,
            screenOffDelay = null,
            displayWindow = null,
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.DE, after.language)
        assertEquals(BoardLanguage.EN, after.boardState?.language)
        assertTrue(after.hasDiverged())
    }

    @Test
    fun onWriteResult_success_movesTheBaselineToTheWrittenLanguage() = runTest {
        val board = boardState(language = BoardLanguage.EN)
        val device = testDevice(language = BoardLanguage.FR, boardState = board)
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device = device, error = null, requestedDevice = device))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.FR, after.boardState?.language)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun onWriteResult_success_adoptsTheLanguageTheBoardReportedBack() = runTest {
        // The user asked for German, but the board draws no German and kept English. The engine
        // reports the language it read back, so the choice reverts instead of looking saved.
        val board = boardState(language = BoardLanguage.EN)
        val requested = testDevice(language = BoardLanguage.DE, boardState = board)
        val f = fixture(testScheduler, requested)
        testScheduler.advanceUntilIdle()

        val stored = requested.copy(language = BoardLanguage.EN)
        f.bus.emit(DeviceWriteResult(device = stored, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.EN, after.language)
        assertEquals(BoardLanguage.EN, after.boardState?.language)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun onWriteResult_success_keepsALanguageChosenWhileTheWriteWasInFlight() = runTest {
        val board = boardState(language = BoardLanguage.EN)
        val f = fixture(testScheduler, testDevice(language = BoardLanguage.DE, boardState = board))
        testScheduler.advanceUntilIdle()

        // The write was dispatched for French, and the user has since picked German.
        val requested = testDevice(language = BoardLanguage.FR)
        f.bus.emit(DeviceWriteResult(device = requested, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.DE, after.language, "a concurrent choice must not be lost")
        assertEquals(BoardLanguage.FR, after.boardState?.language, "the baseline tracks the board")
        assertTrue(after.hasDiverged())
    }

    @Test
    fun updateDeviceConfiguration_storesTheScreenSettingsAsUnsavedChanges() = runTest {
        val board = boardState(
            screenBrightness = BoardScreenBrightness.DEFAULT,
            screenOffDelay = 5,
        )
        val f = fixture(
            testScheduler,
            testDevice(
                screenBrightness = BoardScreenBrightness.DEFAULT,
                screenOffDelay = 5,
                boardState = board,
            ),
        )
        testScheduler.advanceUntilIdle()

        f.delegate.updateDeviceConfiguration(
            deviceId = testDeviceId.toString(),
            name = "Marker",
            bootText = "SnapshotPB",
            activeProfileId = null,
            language = null,
            screenBrightness = BoardScreenBrightness.HIGH,
            screenOffDelay = 45,
            displayWindow = null,
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardScreenBrightness.HIGH, after.screenBrightness)
        assertEquals(45, after.screenOffDelay)
        assertEquals(BoardScreenBrightness.DEFAULT, after.boardState?.screenBrightness)
        assertEquals(5, after.boardState?.screenOffDelay)
        assertTrue(after.hasDiverged())
    }

    @Test
    fun updateDeviceConfiguration_withNoScreenSettingsKeepsTheKnownOnes() = runTest {
        val f = fixture(
            testScheduler,
            testDevice(screenBrightness = BoardScreenBrightness.LOW, screenOffDelay = 30),
        )
        testScheduler.advanceUntilIdle()

        f.delegate.updateDeviceConfiguration(
            deviceId = testDeviceId.toString(),
            name = "Marker",
            bootText = "SnapshotPB",
            activeProfileId = null,
            language = null,
            screenBrightness = null,
            screenOffDelay = null,
            displayWindow = null,
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardScreenBrightness.LOW, after.screenBrightness)
        assertEquals(30, after.screenOffDelay)
    }

    @Test
    fun onWriteResult_success_adoptsTheScreenSettingsTheBoardReported() = runTest {
        // The board refused the brightness and clamped the delay, so the app adopts what it holds.
        val board = boardState(screenBrightness = BoardScreenBrightness.DEFAULT, screenOffDelay = 5)
        val requested = testDevice(
            screenBrightness = BoardScreenBrightness.HIGH,
            screenOffDelay = 1500,
            boardState = board,
        )
        val f = fixture(testScheduler, requested)
        testScheduler.advanceUntilIdle()

        val written = requested.copy(
            screenBrightness = BoardScreenBrightness.DEFAULT,
            screenOffDelay = 999,
        )
        f.bus.emit(DeviceWriteResult(written, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardScreenBrightness.DEFAULT, after.screenBrightness)
        assertEquals(999, after.screenOffDelay)
        assertEquals(BoardScreenBrightness.DEFAULT, after.boardState?.screenBrightness)
        assertEquals(999, after.boardState?.screenOffDelay)
        assertFalse(after.hasDiverged(), "the app and the board now agree")
    }

    @Test
    fun onWriteResult_success_doesNotRecordAValueTheBoardSaysItDoesNotHold() = runTest {
        // Firmware from before these fields reports nothing back. The engine only reports a null
        // when the read-back SUCCEEDED, so a null is the board's answer, not missing data. Keeping
        // the user's value here would show a saved setting that the next connection read wiped
        // with no message.
        val requested = testDevice(
            screenBrightness = BoardScreenBrightness.HIGH,
            screenOffDelay = 30,
        )
        val f = fixture(testScheduler, requested)
        testScheduler.advanceUntilIdle()

        val written = requested.copy(screenBrightness = null, screenOffDelay = null)
        f.bus.emit(DeviceWriteResult(written, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertNull(after.screenBrightness, "the board holds no brightness, so neither does the app")
        assertNull(after.screenOffDelay)
        assertNull(after.boardState?.screenBrightness)
        assertNull(after.boardState?.screenOffDelay)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun onWriteResult_success_recordsARefusalEvenWhenABaselineExists() = runTest {
        // The board reported LOW and 20 s before. The user asked for HIGH and 30 s, and the
        // read-back after the write reports no value. The null is the board's answer, so the
        // old baseline must not come back.
        val board = boardState(
            language = BoardLanguage.ES,
            screenBrightness = BoardScreenBrightness.LOW,
            screenOffDelay = 20,
            displayWindow = DisplayWindow.FULL_PANEL,
            boltMode = MarkerBoltMode.CLOSED,
        )
        val requested = testDevice(
            language = BoardLanguage.EN,
            screenBrightness = BoardScreenBrightness.HIGH,
            screenOffDelay = 30,
            displayWindow = GripFrame.E1.window,
            boltMode = MarkerBoltMode.OPEN,
            boardState = board,
        )
        val f = fixture(testScheduler, requested)
        testScheduler.advanceUntilIdle()

        val written = requested.copy(
            language = null,
            screenBrightness = null,
            screenOffDelay = null,
            displayWindow = null,
            boltMode = null,
        )
        f.bus.emit(DeviceWriteResult(written, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertNull(after.boardState?.language, "the refusal must replace the old baseline")
        assertNull(after.boardState?.screenBrightness)
        assertNull(after.boardState?.screenOffDelay)
        assertNull(after.boardState?.displayWindow)
        assertNull(after.boardState?.boltMode)
        assertNull(after.language)
        assertNull(after.screenBrightness)
        assertNull(after.screenOffDelay)
        assertNull(after.displayWindow)
        assertNull(after.boltMode)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun settledBoardValue_followsTheWrittenTheRequestedAndTheBaselineValues() {
        assertEquals(5, settledBoardValue(written = 5, requested = 7, baseline = 9))
        assertNull(settledBoardValue(written = null, requested = 7, baseline = 9))
        assertEquals(9, settledBoardValue(written = null, requested = null, baseline = 9))
        assertNull(settledBoardValue<Int>(written = null, requested = null, baseline = null))
    }

    @Test
    fun discardPendingChanges_restoresTheBaselineScreenSettingsExactly() = runTest {
        val board = boardState(screenBrightness = BoardScreenBrightness.LOW, screenOffDelay = 5)
        val f = fixture(
            testScheduler,
            testDevice(
                screenBrightness = BoardScreenBrightness.HIGH,
                screenOffDelay = 300,
                boardState = board,
            ),
        )
        testScheduler.advanceUntilIdle()

        f.delegate.discardPendingChanges(testDeviceId.toString())
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardScreenBrightness.LOW, after.screenBrightness)
        assertEquals(5, after.screenOffDelay)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun discardPendingChanges_restoresTheBaselineDisplayWindow() = runTest {
        val board = boardState(displayWindow = DisplayWindow.FULL_PANEL)
        val f = fixture(
            testScheduler,
            testDevice(displayWindow = GripFrame.E2.window, boardState = board),
        )
        testScheduler.advanceUntilIdle()
        assertTrue(f.store.knownDevices.value.single().hasDiverged())

        f.delegate.discardPendingChanges(testDeviceId.toString())
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(DisplayWindow.FULL_PANEL, after.displayWindow)
        assertFalse(after.hasDiverged(), "the banner must clear after a discard")
    }

    @Test
    fun aWindowTheBoardHasNotReportedIsStillWritable() = runTest {
        // The board reports none only when its firmware predates the field, or when a read was
        // malformed. The user must still be able to push a window, so this counts as diverged.
        val f = fixture(
            testScheduler,
            testDevice(displayWindow = GripFrame.E1.window, boardState = boardState()),
        )
        testScheduler.advanceUntilIdle()

        assertTrue(f.store.knownDevices.value.single().hasDiverged())
    }

    @Test
    fun onWriteResult_success_settlesTheDisplayWindowBaseline() = runTest {
        val device = testDevice(displayWindow = GripFrame.E1.window, boardState = boardState())
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device, error = null, requestedDevice = device))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(GripFrame.E1.window, after.boardState?.displayWindow)
        assertFalse(after.hasDiverged(), "a write settles the baseline, so no badge can persist")
    }

    @Test
    fun onWriteResult_success_keepsTheBoardScreenSettingsForAWriteWithNone() = runTest {
        // A write with no screen settings sends the unset values, and the board keeps its own.
        val board = boardState(screenBrightness = BoardScreenBrightness.LOW, screenOffDelay = 20)
        val device = testDevice(bootText = "Edited", boardState = board)
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device, error = null, requestedDevice = device))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardScreenBrightness.LOW, after.boardState?.screenBrightness)
        assertEquals(20, after.boardState?.screenOffDelay)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun updateDeviceConfiguration_clampsTheDisplayWindowAndStoresItAsAnUnsavedChange() = runTest {
        // The board REJECTS a whole message that carries an out-of-bounds rectangle, so the value
        // the user sees after a save must already be the one the board will accept.
        val board = boardState(displayWindow = DisplayWindow.FULL_PANEL)
        val f = fixture(
            testScheduler,
            testDevice(boardState = board).copy(displayWindow = DisplayWindow.FULL_PANEL),
        )
        testScheduler.advanceUntilIdle()

        f.delegate.updateDeviceConfiguration(
            deviceId = testDeviceId.toString(),
            name = "Marker",
            bootText = "SnapshotPB",
            activeProfileId = null,
            language = null,
            screenBrightness = null,
            screenOffDelay = null,
            displayWindow = DisplayWindow(x = 0, y = 3, w = 200, h = 26),
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(DisplayWindow(x = 0, y = 3, w = 128, h = 26), after.displayWindow)
        assertEquals(DisplayWindow.FULL_PANEL, after.boardState?.displayWindow)
        assertTrue(after.hasDiverged())
    }

    @Test
    fun updateDeviceConfiguration_withNoDisplayWindowKeepsTheKnownOne() = runTest {
        val known = DisplayWindow(x = 4, y = 3, w = 110, h = 26)
        val f = fixture(testScheduler, testDevice().copy(displayWindow = known))
        testScheduler.advanceUntilIdle()

        f.delegate.updateDeviceConfiguration(
            deviceId = testDeviceId.toString(),
            name = "Marker",
            bootText = "SnapshotPB",
            activeProfileId = null,
            language = null,
            screenBrightness = null,
            screenOffDelay = null,
            displayWindow = null,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(known, f.store.knownDevices.value.single().displayWindow)
    }

    @Test
    fun updateDeviceConfiguration_withNoLanguageKeepsTheKnownOne() = runTest {
        val board = boardState(language = BoardLanguage.ES)
        val f = fixture(testScheduler, testDevice(language = BoardLanguage.ES, boardState = board))
        testScheduler.advanceUntilIdle()

        f.delegate.updateDeviceConfiguration(
            deviceId = testDeviceId.toString(),
            name = "Marker",
            bootText = "SnapshotPB",
            activeProfileId = null,
            language = null,
            screenBrightness = null,
            screenOffDelay = null,
            displayWindow = null,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(BoardLanguage.ES, f.store.knownDevices.value.single().language)
    }

    @Test
    fun onWriteResult_success_keepsTheBoardLanguageForAWriteWithNoLanguage() = runTest {
        // A write with no language sends UNSPECIFIED, and the board keeps the language it has.
        val board = boardState(bootText = "Old", language = BoardLanguage.ES)
        val device = testDevice(bootText = "Edited", boardState = board)
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device = device, error = null, requestedDevice = device))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.ES, after.boardState?.language)
        assertEquals(BoardLanguage.ES, after.language)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun discardPendingChanges_restoresTheBoardLanguage() = runTest {
        val board = boardState(language = BoardLanguage.EN)
        val f = fixture(testScheduler, testDevice(language = BoardLanguage.DE, boardState = board))
        testScheduler.advanceUntilIdle()

        f.delegate.discardPendingChanges(testDeviceId.toString())
        testScheduler.advanceUntilIdle()

        assertEquals(BoardLanguage.EN, f.store.knownDevices.value.single().language)
    }

    @Test
    fun updateProfile_replacesTheProfileAndClampsItAtTheStore() = runTest {
        val device = testDevice(boardState = boardState())
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device, error = IllegalStateException("boom"), requestedDevice = device))
        testScheduler.advanceUntilIdle()
        assertTrue(testDeviceId in f.delegate.writeErrors.value)

        // The dwell is above the firmware ceiling and the cap sits in the gap the firmware raises.
        f.delegate.updateProfile(
            testDeviceId.toString(),
            testProfile(name = "Ramp", searSolenoidDwell = 200_000).copy(fireRateCap = 3),
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        val stored = after.knownProfiles.single()
        assertEquals("Ramp", stored.name)
        assertEquals(TENTHS_MS_MAX, stored.searSolenoidDwell, "the store keeps the clamped value")
        assertEquals(RATE_CAP_MIN, stored.fireRateCap)
        assertTrue(after.hasDiverged())
        assertFalse(testDeviceId in f.delegate.writeErrors.value, "a new edit supersedes the old failure")
    }

    @Test
    fun updateProfile_keepsTheStoredSourceSlotOverAnOldCopy() = runTest {
        // The editor took its copy when the source slot was 1. A write result then moved the
        // profile to slot 0. A save of the old copy must not put slot 1 back.
        val stored = testProfile(name = "B").copy(sourceSlot = 0)
        val f = fixture(testScheduler, testDevice(profiles = listOf(stored), boardState = boardState()))
        testScheduler.advanceUntilIdle()

        f.delegate.updateProfile(testDeviceId.toString(), stored.copy(sourceSlot = 1, searSolenoidDwell = 99))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single().knownProfiles.single()
        assertEquals(99, after.searSolenoidDwell, "the edit is stored")
        assertEquals(0, after.sourceSlot, "the store keeps its own source slot")
    }

    @Test
    fun onWriteResult_success_aSourceSlotChangeAloneIsNoInFlightEdit() = runTest {
        // A read during the write gave the stored profile a source slot. That is not an edit, so
        // the write result still takes the clamped values that the board stored.
        val requested = testDevice(profiles = listOf(testProfile(searSolenoidDwell = 200_000)))
        val written = requested.clampedToFirmwareRanges().withProfilesInWrittenSlots()
        val local = requested.copy(
            knownProfiles = requested.knownProfiles.map { it.copy(sourceSlot = 0) },
            boardState = boardState(),
        )
        val f = fixture(testScheduler, local)
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device = written, error = null, requestedDevice = requested))
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(TENTHS_MS_MAX, after.knownProfiles.single().searSolenoidDwell)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun updateProfileField_changesOneFieldOfTheStoredProfile() = runTest {
        val f = fixture(testScheduler, testDevice(boardState = boardState(dwell = 45)))
        testScheduler.advanceUntilIdle()

        f.delegate.updateProfileField(
            testDeviceId.toString(),
            testProfileId,
            MarkerProfileField.SEAR_SOLENOID_DWELL,
            60,
        )
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        val stored = after.knownProfiles.single()
        assertEquals(60, stored.searSolenoidDwell)
        assertEquals(testProfile(searSolenoidDwell = 60), stored, "no other field changed")
        assertTrue(after.hasDiverged())
    }

    @Test
    fun updateProfileField_keepsTheOtherFieldsOfANewerProfile() = runTest {
        // The watch stepper started from a profile with threshold 50. A phone push then replaced
        // the profile in the store (threshold 70, renamed). The field commit must land on top of
        // the pushed profile, not on the copy the stepper started from.
        val f = fixture(testScheduler, testDevice(boardState = boardState(dwell = 45)))
        testScheduler.advanceUntilIdle()

        f.store.update { current ->
            current.map { it.copy(knownProfiles = listOf(testProfile(name = "Phone", eyeThreshold = 70))) }
        }
        testScheduler.advanceUntilIdle()

        f.delegate.updateProfileField(
            testDeviceId.toString(),
            testProfileId,
            MarkerProfileField.SEAR_SOLENOID_DWELL,
            60,
        )
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single().knownProfiles.single()
        assertEquals(60, stored.searSolenoidDwell)
        assertEquals("Phone", stored.name, "the phone push survives the field commit")
        assertEquals(70, stored.eyeThreshold)
    }

    @Test
    fun updateProfileField_clampsTheValueAtTheStore() = runTest {
        val f = fixture(testScheduler, testDevice())
        testScheduler.advanceUntilIdle()

        f.delegate.updateProfileField(
            testDeviceId.toString(),
            testProfileId,
            MarkerProfileField.TRIGGER_DEBOUNCE_ON,
            0,
        )
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single().knownProfiles.single()
        assertEquals(TRIGGER_DEBOUNCE_DEFAULT, stored.triggerDebounceOn, "0 is a sentinel the app never stores")
    }

    @Test
    fun discardProfileChanges_restoresOneProfileAndLeavesTheRestAlone() = runTest {
        val otherId = Uuid.parse("aaaaaaaa-2222-3333-4444-555555555557")
        val board = boardState(bootText = "Board", dwell = 45).copy(
            knownProfiles = listOf(testProfile(searSolenoidDwell = 45), testProfile(id = otherId, name = "P1")),
        )
        val device = testDevice(
            bootText = "Edited",
            profiles = listOf(
                testProfile(searSolenoidDwell = 999),
                testProfile(id = otherId, name = "P1", searSolenoidDwell = 77),
            ),
            boardState = board,
        )
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.bus.emit(DeviceWriteResult(device, error = IllegalStateException("boom"), requestedDevice = device))
        testScheduler.advanceUntilIdle()

        f.delegate.discardProfileChanges(testDeviceId.toString(), testProfileId)
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals(45, after.knownProfiles.first { it.id == testProfileId }.searSolenoidDwell)
        assertEquals(77, after.knownProfiles.first { it.id == otherId }.searSolenoidDwell, "the other profile keeps its edit")
        assertEquals("Edited", after.bootText, "the boot text keeps its edit")
        assertTrue(after.hasDiverged())
        assertFalse(testDeviceId in f.delegate.writeErrors.value)
    }

    @Test
    fun discardProfileChanges_inAListWithNoSourceSlots_addsNoSourceSlot() = runTest {
        // A copy stored before the app kept source slots, with a baseline from a newer read.
        val otherId = getProfileUuid(testDeviceId, 1)
        val board = boardState().copy(
            knownProfiles = listOf(
                testProfile(searSolenoidDwell = 45).copy(sourceSlot = 0),
                testProfile(id = otherId, name = "P1").copy(sourceSlot = 1),
            ),
        )
        val device = testDevice(
            profiles = listOf(testProfile(searSolenoidDwell = 999), testProfile(id = otherId, name = "P1")),
            boardState = board,
        )
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.delegate.discardProfileChanges(testDeviceId.toString(), testProfileId)
        testScheduler.advanceUntilIdle()

        // One slot in the list would stop the content alignment for the other profiles.
        val after = f.store.knownDevices.value.single()
        assertEquals(45, after.knownProfiles[0].searSolenoidDwell)
        assertEquals(listOf<Int?>(null, null), after.knownProfiles.map { it.sourceSlot })
    }

    @Test
    fun discardProfileChanges_doesNotGiveOneSourceSlotToTwoProfiles() = runTest {
        // A delete moved the profile from slot 1 into slot 0, so it holds source slot 1.
        val slot0 = getProfileUuid(testDeviceId, 0)
        val slot1 = getProfileUuid(testDeviceId, 1)
        val board = boardState().copy(
            knownProfiles = listOf(
                testProfile(id = slot0, name = "A").copy(sourceSlot = 0),
                testProfile(id = slot1, name = "B").copy(sourceSlot = 1),
            ),
        )
        val device = testDevice(
            profiles = listOf(testProfile(id = slot0, name = "B").copy(sourceSlot = 1)),
            boardState = board,
        )
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.delegate.discardProfileChanges(testDeviceId.toString(), slot0)
        testScheduler.advanceUntilIdle()

        // The profile takes the settings of board slot 0 and that slot, which no other profile holds.
        val after = f.store.knownDevices.value.single().knownProfiles.single()
        assertEquals("A", after.name)
        assertEquals(0, after.sourceSlot)
    }

    @Test
    fun discardProfileChanges_ignoresAProfileTheBoardDoesNotHold() = runTest {
        val f = fixture(testScheduler, testDevice(profiles = listOf(testProfile(searSolenoidDwell = 999))))
        testScheduler.advanceUntilIdle()

        f.delegate.discardProfileChanges(testDeviceId.toString(), testProfileId)
        testScheduler.advanceUntilIdle()

        assertEquals(999, f.store.knownDevices.value.single().knownProfiles.single().searSolenoidDwell)
    }

    @Test
    fun discardPendingChanges_restoresTheCachedBoardState() = runTest {
        val device = testDevice(
            bootText = "Edited",
            profiles = listOf(testProfile(searSolenoidDwell = 999)),
            boardState = boardState(bootText = "Board", dwell = 45),
        )
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()
        assertTrue(f.store.knownDevices.value.single().hasDiverged())

        f.delegate.discardPendingChanges(testDeviceId.toString())
        testScheduler.advanceUntilIdle()

        val after = f.store.knownDevices.value.single()
        assertEquals("Board", after.bootText)
        assertEquals(45, after.knownProfiles.single().searSolenoidDwell)
        assertFalse(after.hasDiverged())
    }

    @Test
    fun updateBoardSettings_keepsAPhonePushThatLandsDuringTheCommit() = runTest {
        val device = testDevice(
            language = BoardLanguage.EN,
            screenBrightness = BoardScreenBrightness.DEFAULT,
            boardState = boardState(),
        )
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        // The first run of the transform pushes a phone edit into the store, as a Wear sync that
        // lands between the read and the commit does. The store then runs the transform again on
        // the new device, so the commit must keep the phone edit. The nested update works only
        // because DeviceStore.update is a lock-free compare-and-set: the outer compare-and-set
        // fails, and the store runs the transform again. A store with a lock needs a new test.
        var pushed = false
        f.delegate.updateBoardSettings(testDeviceId.toString()) { current ->
            if (!pushed) {
                pushed = true
                f.store.update { list ->
                    list.map { it.copy(bootText = "Phone", screenBrightness = BoardScreenBrightness.HIGH) }
                }
            }
            current.copy(language = BoardLanguage.DE)
        }
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.DE, stored.language)
        assertEquals("Phone", stored.bootText)
        assertEquals(BoardScreenBrightness.HIGH, stored.screenBrightness)
    }

    @Test
    fun updateBoardSettings_changesOnlyTheBoardSettingsAndKeepsAStoredValueForNull() = runTest {
        val device = testDevice(
            name = "Marker",
            language = BoardLanguage.EN,
            screenOffDelay = 30,
            boardState = boardState(),
        )
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        f.delegate.updateBoardSettings(testDeviceId.toString()) {
            it.copy(name = "Other", activeProfileId = testProfileId, language = null, screenOffDelay = 60)
        }
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals("Marker", stored.name, "a board setting commit must not change the name")
        assertEquals(testProfileId, stored.activeProfileId)
        assertEquals(BoardLanguage.EN, stored.language, "null is no choice and keeps the stored value")
        assertEquals(60, stored.screenOffDelay)
    }

    @Test
    fun updateProfile_keepsAPhonePushThatLandsDuringTheCommit() = runTest {
        val device = testDevice(boardState = boardState())
        val f = fixture(testScheduler, device)
        testScheduler.advanceUntilIdle()

        // A nested store update forces a second run of the transform, as in the test above.
        var pushed = false
        f.delegate.updateProfile(testDeviceId.toString(), testProfileId) { current ->
            if (!pushed) {
                pushed = true
                f.store.update { list ->
                    list.map { d ->
                        d.copy(knownProfiles = d.knownProfiles.map { it.copy(searSolenoidDwell = 60) })
                    }
                }
            }
            current.copy(eyeMode = MarkerEyeMode.BREAK_BEAM)
        }
        testScheduler.advanceUntilIdle()

        val profile = f.store.knownDevices.value.single().knownProfiles.single()
        assertEquals(MarkerEyeMode.BREAK_BEAM, profile.eyeMode)
        assertEquals(60, profile.searSolenoidDwell, "the phone edit must survive the watch commit")
    }

    @Test
    fun updateProfile_withNoChangeKeepsTheWriteError() = runTest {
        val device = testDevice(bootText = "Edited", boardState = boardState(bootText = "Old"))
        val requestManager = BluetoothRequestManager()
        val f = Fixture(
            testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(device)),
            DeviceWriteResultBus(),
            requestManager,
        )
        val writes = mutableListOf<BluetoothRequest>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestManager.bus.collect { writes += it }
        }
        testScheduler.advanceUntilIdle()
        f.delegate.saveChangesToBoard(testDeviceId.toString())
        testScheduler.advanceUntilIdle()
        val requestId = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single().requestId
        f.bus.emit(
            DeviceWriteResult(
                device = device,
                error = IllegalStateException("link lost"),
                requestedDevice = device,
                requestId = requestId,
            )
        )
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(testDeviceId), f.delegate.writeErrors.value)

        // A choice of the value the profile already holds is no edit, so the failure stays.
        f.delegate.updateProfile(testDeviceId.toString(), testProfileId) { it }
        f.delegate.updateBoardSettings(testDeviceId.toString()) { it }
        assertEquals(setOf(testDeviceId), f.delegate.writeErrors.value)

        // A real edit supersedes the failed write.
        f.delegate.updateProfile(testDeviceId.toString(), testProfileId) {
            it.copy(eyeMode = MarkerEyeMode.REFLECTIVE)
        }
        assertTrue(f.delegate.writeErrors.value.isEmpty())
        collector.cancel()
    }
}
