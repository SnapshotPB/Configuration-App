package com.snapshotpb.configuration.common.screen.delegate

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResult
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.bluetooth.toDeviceUuid
import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.common.testDeviceStore
import com.snapshotpb.configuration.common.testProfile
import com.snapshotpb.configuration.model.AdvertisementData
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.DisplayWindow
import com.snapshotpb.configuration.model.GripFrame
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.Photo
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Cover for the reconcile-on-read state machine: new device vs. silent re-sync vs. conflict, and
 * what each conflict resolution leaves in the store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSyncDelegateTest {

    private class Fixture(
        val store: DeviceStore,
        val readBus: DeviceReadResultBus,
        val requestManager: BluetoothRequestManager,
        val delegate: DeviceSyncDelegate,
    )

    private fun fixture(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        devices: List<Device> = emptyList(),
        photoStamp: () -> Long = { 0L },
    ): Fixture {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val store = testDeviceStore(dispatcher, devices)
        val readBus = DeviceReadResultBus()
        val requestManager = BluetoothRequestManager()
        return Fixture(
            store = store,
            readBus = readBus,
            requestManager = requestManager,
            delegate = DeviceSyncDelegateImpl(
                deviceStore = store,
                deviceReadResultBus = readBus,
                bluetoothRequestManager = requestManager,
                dispatcher = dispatcher,
                photoStamp = photoStamp,
            ),
        )
    }

    private fun baseline(device: Device) = BoardState(
        bootText = device.bootText,
        activeProfileId = device.activeProfileId,
        lockoutEnabled = device.lockoutEnabled,
        knownProfiles = device.knownProfiles,
        language = device.language,
        screenBrightness = device.screenBrightness,
        screenOffDelay = device.screenOffDelay,
        displayWindow = device.displayWindow,
    )

    @Test
    fun firstReadOfAnUnknownBoardIsSurfacedAsANewDevice() = runTest {
        val f = fixture(testScheduler)
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice()))
        testScheduler.advanceUntilIdle()

        assertIs<PendingSync.NewDevice>(f.delegate.pendingSyncs.value[testDeviceId])
        assertTrue(f.store.knownDevices.value.isEmpty(), "nothing is stored until the user confirms")
    }

    @Test
    fun confirmNewDevice_storesTheDeviceWithABaselineAndClearsThePrompt() = runTest {
        val f = fixture(testScheduler)
        testScheduler.advanceUntilIdle()
        f.readBus.emit(DeviceReadResult(testDevice(language = BoardLanguage.DE)))
        testScheduler.advanceUntilIdle()

        f.delegate.confirmNewDevice(testDeviceId, name = "My Marker", photo = null)
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals("My Marker", stored.name)
        assertEquals(stored.bootText, stored.boardState?.bootText)
        assertEquals(BoardLanguage.DE, stored.boardState?.language)
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun confirmNewDevice_storesThePhotoUnderAStampedName() = runTest {
        // One fixed name for every photo kept the path the same after a new photo, so Coil and
        // the Wear sync kept the old image. The name has a stamp, as in DeviceViewModelDelegate.
        val f = fixture(testScheduler, photoStamp = { 77L })
        testScheduler.advanceUntilIdle()
        f.readBus.emit(DeviceReadResult(testDevice()))
        testScheduler.advanceUntilIdle()

        f.delegate.confirmNewDevice(testDeviceId, name = "My Marker", photo = Photo(uri = "content://photo"))
        testScheduler.advanceUntilIdle()

        assertEquals("device_photo_${testDeviceId}_77", f.store.knownDevices.value.single().photoPath)
    }

    @Test
    fun confirmNewDevice_twiceDoesNotDuplicateTheDevice() = runTest {
        val f = fixture(testScheduler)
        testScheduler.advanceUntilIdle()
        f.readBus.emit(DeviceReadResult(testDevice()))
        testScheduler.advanceUntilIdle()

        f.delegate.confirmNewDevice(testDeviceId, name = "First", photo = null)
        // A second read + confirm for the same board must replace, not append.
        f.readBus.emit(DeviceReadResult(testDevice()))
        testScheduler.advanceUntilIdle()
        f.delegate.confirmNewDevice(testDeviceId, name = "Second", photo = null)
        testScheduler.advanceUntilIdle()

        assertEquals(1, f.store.knownDevices.value.count { it.id == testDeviceId })
    }

    @Test
    fun readOfAKnownDeviceWithNoBaselineInitialisesItWhenTheFieldsMatch() = runTest {
        val local = testDevice()
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(local.copy(lastSeen = LocalDate(2026, 7, 27))))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(LocalDate(2026, 7, 27), stored.lastSeen)
        assertEquals(local.bootText, stored.boardState?.bootText, "baseline is seeded from the read")
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun readOfAKnownDeviceWithNoBaselineRaisesAConflictWhenTheFieldsDiffer() = runTest {
        val f = fixture(testScheduler, listOf(testDevice(bootText = "Local")))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice(bootText = "Board")))
        testScheduler.advanceUntilIdle()

        val pending = f.delegate.pendingSyncs.value[testDeviceId]
        assertIs<PendingSync.Conflict>(pending)
        assertEquals("Local", pending.local.bootText)
        assertEquals("Board", pending.remote.bootText)
    }

    @Test
    fun reconnectMatchingTheCachedBaselinePreservesPendingLocalEdits() = runTest {
        // Local has an unsaved edit; the board still reports what the baseline recorded.
        val board = testDevice(bootText = "Board")
        val local = testDevice(bootText = "Edited", boardState = baseline(board))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(board.copy(lastSeen = LocalDate(2026, 7, 27))))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals("Edited", stored.bootText, "the unsaved edit survives a reconnect")
        assertEquals(LocalDate(2026, 7, 27), stored.lastSeen)
        assertTrue(f.delegate.pendingSyncs.value.isEmpty(), "this is not a conflict")
    }

    @Test
    fun reconnectToACopyStoredWithoutSourceSlots_raisesNoConflictAndTakesTheReadSlots() = runTest {
        // A copy stored before the app kept source slots: no profile has one, in either list.
        val legacy = testDevice(bootText = "Board")
        val local = legacy.copy(boardState = baseline(legacy))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        // A read gives each profile the slot that it came from.
        val read = legacy.copy(knownProfiles = legacy.knownProfiles.mapIndexed { i, p -> p.copy(sourceSlot = i) })
        f.readBus.emit(DeviceReadResult(read))
        testScheduler.advanceUntilIdle()

        // The source slot is not a setting, so it raises no conflict and no unsaved badge.
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
        val stored = f.store.knownDevices.value.single()
        assertEquals(listOf(0), stored.knownProfiles.map { it.sourceSlot })
        assertEquals(listOf(0), stored.boardState?.knownProfiles?.map { it.sourceSlot })
        assertFalse(stored.hasDiverged())
    }

    @Test
    fun reconnectWithAnUnsavedProfileEdit_keepsTheLocalSourceSlots() = runTest {
        val board = testDevice(profiles = listOf(testProfile().copy(sourceSlot = 0)))
        // An unsaved edit: the profile came from slot 1, and a delete moved it to slot 0.
        val local = board.copy(
            knownProfiles = listOf(testProfile(name = "Edited").copy(sourceSlot = 1)),
            boardState = baseline(board),
        )
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(board))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals("Edited", stored.knownProfiles.single().name)
        assertEquals(1, stored.knownProfiles.single().sourceSlot)
    }

    @Test
    fun reconnectAfterAnExternalBoardChangeOverALocalEditRaisesAConflict() = runTest {
        val board = testDevice(bootText = "Board")
        val local = testDevice(bootText = "Local", boardState = baseline(board))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        // Somebody changed the board from another client, and the app holds an unsaved edit.
        f.readBus.emit(DeviceReadResult(testDevice(bootText = "ChangedElsewhere")))
        testScheduler.advanceUntilIdle()

        assertIs<PendingSync.Conflict>(f.delegate.pendingSyncs.value[testDeviceId])
        val stored = f.store.knownDevices.value.single()
        assertEquals("Local", stored.bootText, "the store keeps the edit until the user decides")
        assertEquals("Board", stored.boardState?.bootText)
    }

    @Test
    fun reconnectAfterAnExternalBoardChangeWithNoLocalEditsTakesTheBoardValues() = runTest {
        // With no local edits the app has nothing to lose, so no prompt shows (issue #122).
        val board = testDevice(bootText = "Board", language = BoardLanguage.EN)
        val local = board.copy(name = "My Name", photoPath = "device_photo_kept", boardState = baseline(board))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        val changedProfile = testProfile(name = "P9", searSolenoidDwell = 60)
        val changed = testDevice(
            name = "Board Name",
            bootText = "ChangedElsewhere",
            profiles = listOf(changedProfile),
            language = BoardLanguage.FR,
            screenBrightness = BoardScreenBrightness.HIGH,
            screenOffDelay = 30,
            displayWindow = GripFrame.E1.window,
        )
        f.readBus.emit(DeviceReadResult(changed))
        testScheduler.advanceUntilIdle()

        assertTrue(f.delegate.pendingSyncs.value.isEmpty(), "no local edits raise no conflict")
        val stored = f.store.knownDevices.value.single()
        assertEquals("ChangedElsewhere", stored.bootText)
        assertEquals(listOf(changedProfile), stored.knownProfiles)
        assertEquals(BoardLanguage.FR, stored.language)
        assertEquals(BoardScreenBrightness.HIGH, stored.screenBrightness)
        assertEquals(30, stored.screenOffDelay)
        assertEquals(GripFrame.E1.window, stored.displayWindow)
        assertEquals(baseline(changed), stored.boardState, "the baseline records the new board state")
        assertFalse(stored.hasDiverged())
        assertEquals("My Name", stored.name, "the local name is a user label, not board state")
        assertEquals("device_photo_kept", stored.photoPath)
    }

    @Test
    fun reconnectAfterAnExternalBoardChangeWithNoLocalEditsSendsNoBoardWrite() = runTest {
        // Before issue #122, "Keep local" wrote the old value back over the board menu choice.
        val board = testDevice(screenBrightness = BoardScreenBrightness.LOW)
        val f = fixture(testScheduler, listOf(board.copy(boardState = baseline(board))))
        testScheduler.advanceUntilIdle()
        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        f.readBus.emit(DeviceReadResult(testDevice(screenBrightness = BoardScreenBrightness.HIGH)))
        testScheduler.advanceUntilIdle()

        assertTrue(f.delegate.pendingSyncs.value.isEmpty(), "no conflict, so no choice can write")
        assertTrue(writes.filterIsInstance<BluetoothRequest.WriteDevice>().isEmpty())
        assertEquals(BoardScreenBrightness.HIGH, f.store.knownDevices.value.single().screenBrightness)
    }

    @Test
    fun resolveConflict_takeDevice_adoptsBoardValuesButKeepsLocalNameAndPhoto() = runTest {
        val local = testDevice(name = "My Name", bootText = "Local").copy(photoPath = "device_photo_kept")
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()
        val board = testDevice(name = "Board Name", bootText = "Board", language = BoardLanguage.ES)
        f.readBus.emit(DeviceReadResult(board))
        testScheduler.advanceUntilIdle()

        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeDevice)
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals("Board", stored.bootText)
        assertEquals(BoardLanguage.ES, stored.language)
        assertEquals(BoardLanguage.ES, stored.boardState?.language)
        assertEquals("My Name", stored.name, "the local name is a user label, not board state")
        assertEquals("device_photo_kept", stored.photoPath)
        assertEquals("Board", stored.boardState?.bootText)
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun resolveConflict_takeDevice_keepsARenameAndAPhotoThatLandedWhileTheDialogWasOpen() = runTest {
        val local = testDevice(name = "Old Name", bootText = "Local").copy(photoPath = "device_photo_old")
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()
        f.readBus.emit(DeviceReadResult(testDevice(name = "Board Name", bootText = "Board")))
        testScheduler.advanceUntilIdle()

        // The user renames the device and picks a new photo while the conflict dialog is open.
        f.store.update { current ->
            current.map { it.copy(name = "New Name", photoPath = "device_photo_new") }
        }
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeDevice)
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals("New Name", stored.name, "the rename must not go back to the snapshot")
        assertEquals("device_photo_new", stored.photoPath, "the new photo must not go back to the snapshot")
        assertEquals("Board", stored.bootText)
    }

    @Test
    fun resolveConflict_takeLocal_writesAnEditThatLandedWhileTheDialogWasOpen() = runTest {
        val local = testDevice(name = "Old Name", bootText = "Local")
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()
        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }
        f.readBus.emit(DeviceReadResult(testDevice(bootText = "Board")))
        testScheduler.advanceUntilIdle()

        // An edit and a new photo land while the conflict dialog is open.
        f.store.update { current ->
            current.map { it.copy(name = "New Name", bootText = "Edited", photoPath = "device_photo_new") }
        }
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals("Edited", stored.bootText)
        assertEquals("New Name", stored.name)
        assertEquals("device_photo_new", stored.photoPath)
        assertEquals("Board", stored.boardState?.bootText)
        val write = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single()
        assertEquals("Edited", write.device.bootText, "the board must get the current edit")
    }

    @Test
    fun resolveConflict_takeLocal_writesTheClampedLocalDeviceToTheBoard() = runTest {
        // 99999 is in range; 0 for trigger debounce is the "firmware substitutes its default"
        // sentinel the clamp must replace, so the stored copy and the wire value agree.
        val local = testDevice(profiles = listOf(testProfile().copy(triggerDebounceOn = 0)))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        f.readBus.emit(DeviceReadResult(testDevice(bootText = "Board")))
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        val write = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single()
        val sentDebounce = write.device.knownProfiles.single().triggerDebounceOn
        val storedDebounce = f.store.knownDevices.value.single().knownProfiles.single().triggerDebounceOn
        assertTrue(sentDebounce > 0, "the 0 sentinel must never reach the board")
        assertEquals(
            sentDebounce,
            storedDebounce,
            "stored copy must equal the wire value, or the conflict reappears on every reconnect",
        )
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun resolveConflict_takeLocal_whenTheBoardProfilesChanged_clearsTheSourceSlots() = runTest {
        val before = testDevice(profiles = listOf(testProfile(name = "A").copy(sourceSlot = 0)))
        // A local edit, so that the board change raises a conflict and not a silent take.
        val local = before.copy(bootText = "Local", boardState = baseline(before))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()
        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        // Another client changed the profiles, so slot 0 is not the slot the baseline recorded.
        f.readBus.emit(DeviceReadResult(testDevice(profiles = listOf(testProfile(name = "Z").copy(sourceSlot = 0)))))
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        val write = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single()
        assertEquals("A", write.device.knownProfiles.single().name)
        assertNull(write.device.knownProfiles.single().sourceSlot, "the write pairs by position and content")
    }

    @Test
    fun resolveConflict_takeLocal_whenOnlyOtherSettingsChanged_keepsTheSourceSlots() = runTest {
        val before = testDevice(profiles = listOf(testProfile(name = "A").copy(sourceSlot = 0)))
        // A local edit, so that the board change raises a conflict and not a silent take.
        val local = before.copy(bootText = "Local", boardState = baseline(before))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()
        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        f.readBus.emit(DeviceReadResult(before.copy(bootText = "ChangedElsewhere")))
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        val write = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single()
        assertEquals(0, write.device.knownProfiles.single().sourceSlot)
    }

    @Test
    fun resolveConflict_takeLocal_keepsTheBoardValuesAsTheBaselineUntilTheBoardAcceptsTheWrite() = runTest {
        val local = testDevice(bootText = "Local", boardState = baseline(testDevice(bootText = "Old")))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect {}
        }

        f.readBus.emit(DeviceReadResult(testDevice(bootText = "Board")))
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        // The write is still in flight (or has failed): the store must not claim that the board
        // holds the local values, or a failed write shows no banner and no error.
        val stored = f.store.knownDevices.value.single()
        assertEquals("Local", stored.bootText)
        assertEquals("Board", stored.boardState?.bootText)
        assertTrue(stored.hasDiverged(), "divergence stays visible until the write result settles it")
    }

    @Test
    fun resolveConflict_takeLocal_keepsTheEditsAndSendsNoWriteWhileTheBoardReportsLockout() = runTest {
        // device.proto: a board in lockout does not accept a change until the user turns off the
        // lockout switch, so the local values stay as unsaved edits.
        val local = testDevice(bootText = "Local", boardState = baseline(testDevice(bootText = "Old")))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()
        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        f.readBus.emit(DeviceReadResult(testDevice(bootText = "Board").copy(lockoutEnabled = true)))
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        assertTrue(writes.filterIsInstance<BluetoothRequest.WriteDevice>().isEmpty())
        val stored = f.store.knownDevices.value.single()
        assertEquals("Local", stored.bootText)
        assertEquals("Board", stored.boardState?.bootText)
        assertTrue(stored.lockoutEnabled, "the stored device must show the lockout")
        assertTrue(stored.hasDiverged(), "the local values must stay as unsaved edits")
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun readOfAKnownDeviceWithNoBaselineAdoptsTheBoardLanguage() = runTest {
        // A device stored before the app read the language has none; that is not a conflict.
        val f = fixture(testScheduler, listOf(testDevice()))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice(language = BoardLanguage.FR)))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.FR, stored.language)
        assertEquals(BoardLanguage.FR, stored.boardState?.language)
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun reconnectToABaselineWithNoLanguageAdoptsTheBoardLanguage() = runTest {
        // The baseline predates the language field, so the board's language fills it silently.
        val local = testDevice(boardState = baseline(testDevice()))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice(language = BoardLanguage.DE)))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.DE, stored.language)
        assertEquals(BoardLanguage.DE, stored.boardState?.language)
        assertFalse(stored.hasDiverged())
        assertTrue(f.delegate.pendingSyncs.value.isEmpty(), "an app update raises no conflict")
    }

    @Test
    fun reconnectMatchingTheBaselineKeepsAnUnsavedLanguageChoice() = runTest {
        val board = testDevice(language = BoardLanguage.EN)
        val local = testDevice(language = BoardLanguage.ES, boardState = baseline(board))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(board))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.ES, stored.language, "the unsaved choice survives a reconnect")
        assertTrue(stored.hasDiverged())
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun reconnectToABoardThatReportsNoLanguageClearsTheStoredOneWithoutAConflict() = runTest {
        // Older firmware, or a language this app does not know: no write could make them agree.
        val board = testDevice(language = BoardLanguage.EN)
        val local = testDevice(language = BoardLanguage.DE, boardState = baseline(board))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice()))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertNull(stored.language)
        assertNull(stored.boardState?.language)
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun reconnectAfterTheBoardLanguageChangedWithNoLocalEditsTakesTheBoardLanguage() = runTest {
        // The user changed the language in the board's own menu, and the app has no edits.
        val board = testDevice(language = BoardLanguage.EN)
        val f = fixture(testScheduler, listOf(board.copy(boardState = baseline(board))))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice(language = BoardLanguage.FR)))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.FR, stored.language)
        assertEquals(BoardLanguage.FR, stored.boardState?.language)
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun reconnectAfterTheBoardLanguageChangedOverALocalEditRaisesAConflict() = runTest {
        // The user changed the language in the board's own menu, and the app holds an edit.
        val board = testDevice(language = BoardLanguage.EN)
        val local = testDevice(bootText = "Local", language = BoardLanguage.EN, boardState = baseline(board))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice(language = BoardLanguage.FR)))
        testScheduler.advanceUntilIdle()

        assertIs<PendingSync.Conflict>(f.delegate.pendingSyncs.value[testDeviceId])
        assertEquals(BoardLanguage.EN, f.store.knownDevices.value.single().language)
    }

    @Test
    fun resolveConflict_takeLocal_fillsAMissingLocalLanguageFromTheBoard() = runTest {
        val f = fixture(testScheduler, listOf(testDevice(bootText = "Local")))
        testScheduler.advanceUntilIdle()

        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        val board = testDevice(bootText = "Board", language = BoardLanguage.ES)
        f.readBus.emit(DeviceReadResult(board))
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        // The board keeps its own language for an UNSPECIFIED write, so all three record that one.
        val write = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single()
        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardLanguage.ES, write.device.language)
        assertEquals(BoardLanguage.ES, stored.language)
        assertEquals(BoardLanguage.ES, stored.boardState?.language)
    }

    @Test
    fun reconnectToABaselineWithNoScreenSettingsAdoptsTheBoardValues() = runTest {
        // The baseline predates the screen settings, so the board's values fill them silently.
        val local = testDevice(boardState = baseline(testDevice()))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(
            DeviceReadResult(
                testDevice(screenBrightness = BoardScreenBrightness.HIGH, screenOffDelay = 30)
            )
        )
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardScreenBrightness.HIGH, stored.screenBrightness)
        assertEquals(30, stored.screenOffDelay)
        assertEquals(BoardScreenBrightness.HIGH, stored.boardState?.screenBrightness)
        assertEquals(30, stored.boardState?.screenOffDelay)
        assertFalse(stored.hasDiverged())
        assertTrue(f.delegate.pendingSyncs.value.isEmpty(), "an app update raises no conflict")
    }

    @Test
    fun reconnectAfterTheBoardScreenSettingsChangedWithNoLocalEditsTakesTheBoardValues() = runTest {
        // The user changed the brightness in the board's own menu, and the app has no edits.
        val board = testDevice(screenBrightness = BoardScreenBrightness.LOW, screenOffDelay = 5)
        val f = fixture(testScheduler, listOf(board.copy(boardState = baseline(board))))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(
            DeviceReadResult(
                testDevice(screenBrightness = BoardScreenBrightness.HIGH, screenOffDelay = 5)
            )
        )
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardScreenBrightness.HIGH, stored.screenBrightness)
        assertEquals(BoardScreenBrightness.HIGH, stored.boardState?.screenBrightness)
        assertFalse(stored.hasDiverged())
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun reconnectAfterTheBoardScreenSettingsChangedOverALocalEditRaisesAConflict() = runTest {
        // The user changed the brightness in the board's own menu, and the app holds an edit.
        val board = testDevice(screenBrightness = BoardScreenBrightness.LOW, screenOffDelay = 5)
        val local = testDevice(
            screenBrightness = BoardScreenBrightness.LOW,
            screenOffDelay = 20,
            boardState = baseline(board),
        )
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(
            DeviceReadResult(
                testDevice(screenBrightness = BoardScreenBrightness.HIGH, screenOffDelay = 5)
            )
        )
        testScheduler.advanceUntilIdle()

        assertTrue(f.delegate.pendingSyncs.value[testDeviceId] is PendingSync.Conflict)
    }

    @Test
    fun resolveConflict_takeLocal_fillsMissingLocalScreenSettingsFromTheBoard() = runTest {
        val f = fixture(testScheduler, listOf(testDevice(bootText = "Local")))
        testScheduler.advanceUntilIdle()

        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        val board = testDevice(
            bootText = "Board",
            screenBrightness = BoardScreenBrightness.DEFAULT,
            screenOffDelay = 45,
        )
        f.readBus.emit(DeviceReadResult(board))
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        // The board keeps its own values for an unset write, so all three record those.
        val write = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single()
        val stored = f.store.knownDevices.value.single()
        assertEquals(BoardScreenBrightness.DEFAULT, write.device.screenBrightness)
        assertEquals(45, write.device.screenOffDelay)
        assertEquals(BoardScreenBrightness.DEFAULT, stored.screenBrightness)
        assertEquals(45, stored.boardState?.screenOffDelay)
    }

    @Test
    fun resolveConflict_takeLocal_twiceSendsOneWrite() = runTest {
        // A double tap on "Keep my values" called resolveConflict two times. Both calls saw the
        // pending entry, so the board got two writes.
        val f = fixture(testScheduler, listOf(testDevice(bootText = "Local")))
        testScheduler.advanceUntilIdle()

        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        f.readBus.emit(DeviceReadResult(testDevice(bootText = "Board")))
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        assertEquals(1, writes.filterIsInstance<BluetoothRequest.WriteDevice>().size)
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun resolveConflict_ignoresAPendingNewDevice() = runTest {
        // Only a conflict can be resolved. A new-device prompt must stay until it is confirmed.
        val f = fixture(testScheduler)
        testScheduler.advanceUntilIdle()
        f.readBus.emit(DeviceReadResult(testDevice()))
        testScheduler.advanceUntilIdle()

        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeDevice)
        testScheduler.advanceUntilIdle()

        assertIs<PendingSync.NewDevice>(f.delegate.pendingSyncs.value[testDeviceId])
        assertTrue(f.store.knownDevices.value.isEmpty())
    }

    @Test
    fun reconnectToABaselineWithNoDisplayWindowAdoptsTheBoardWindow() = runTest {
        // The baseline predates the display window, so the board's window fills it silently.
        val local = testDevice(boardState = baseline(testDevice()))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice(displayWindow = GripFrame.E1.window)))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(GripFrame.E1.window, stored.displayWindow)
        assertEquals(GripFrame.E1.window, stored.boardState?.displayWindow)
        assertFalse(stored.hasDiverged())
        assertTrue(f.delegate.pendingSyncs.value.isEmpty(), "an app update raises no conflict")
    }

    @Test
    fun reconnectAfterTheBoardDisplayWindowChangedWithNoLocalEditsTakesTheBoardWindow() = runTest {
        // The user chose a different window in the board's own menu, and the app has no edits.
        val board = testDevice(displayWindow = DisplayWindow.FULL_PANEL)
        val f = fixture(testScheduler, listOf(board.copy(boardState = baseline(board))))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice(displayWindow = GripFrame.E1.window)))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(GripFrame.E1.window, stored.displayWindow)
        assertEquals(GripFrame.E1.window, stored.boardState?.displayWindow)
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun reconnectAfterTheBoardDisplayWindowChangedOverALocalEditRaisesAConflict() = runTest {
        // The user chose a different window in the board's own menu, and the app holds an edit.
        val board = testDevice(displayWindow = DisplayWindow.FULL_PANEL)
        val local = testDevice(
            bootText = "Local",
            displayWindow = DisplayWindow.FULL_PANEL,
            boardState = baseline(board),
        )
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice(displayWindow = GripFrame.E1.window)))
        testScheduler.advanceUntilIdle()

        assertIs<PendingSync.Conflict>(f.delegate.pendingSyncs.value[testDeviceId])
    }

    @Test
    fun reconnectMatchingTheBaselineKeepsAnUnsavedDisplayWindow() = runTest {
        val board = testDevice(displayWindow = DisplayWindow.FULL_PANEL)
        val local = testDevice(displayWindow = GripFrame.E1.window, boardState = baseline(board))
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(board))
        testScheduler.advanceUntilIdle()

        val stored = f.store.knownDevices.value.single()
        assertEquals(GripFrame.E1.window, stored.displayWindow, "the unsaved choice stays")
        assertEquals(DisplayWindow.FULL_PANEL, stored.boardState?.displayWindow)
        assertTrue(stored.hasDiverged())
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun resolveConflict_takeLocal_fillsAMissingLocalDisplayWindowFromTheBoard() = runTest {
        val f = fixture(testScheduler, listOf(testDevice(bootText = "Local")))
        testScheduler.advanceUntilIdle()

        val writes = mutableListOf<BluetoothRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.requestManager.bus.collect { writes += it }
        }

        f.readBus.emit(
            DeviceReadResult(testDevice(bootText = "Board", displayWindow = GripFrame.E1.window))
        )
        testScheduler.advanceUntilIdle()
        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeLocal)
        testScheduler.advanceUntilIdle()

        val write = writes.filterIsInstance<BluetoothRequest.WriteDevice>().single()
        val stored = f.store.knownDevices.value.single()
        assertEquals(GripFrame.E1.window, write.device.displayWindow)
        assertEquals(GripFrame.E1.window, stored.displayWindow)
        assertEquals(GripFrame.E1.window, stored.boardState?.displayWindow)
    }

    /**
     * A device that an older build stored under the scan key of its board (#127), and the read of
     * the same board under the new rule, which gives the board device_id.
     */
    private fun oldAndRead(): Pair<Device, Device> {
        val oldId = SCAN_KEY.toDeviceUuid()
        fun profiles(deviceId: Uuid) = listOf(
            testProfile(id = getProfileUuid(deviceId, 0), name = "Semi"),
            testProfile(id = getProfileUuid(deviceId, 1), name = "Ramp"),
        )
        val stored = testDevice(id = oldId, name = "My Marker", bootText = "HELLO", profiles = profiles(oldId))
            .let { it.copy(photoPath = "device_photo_${oldId}_7", boardState = baseline(it)) }
        val read = testDevice(id = BOARD_ID, name = "Snapshot PB", bootText = "HELLO", profiles = profiles(BOARD_ID))
        return stored to read
    }

    @Test
    fun firstReadUnderTheBoardId_movesTheDeviceStoredUnderTheScanKey() = runTest {
        val (stored, read) = oldAndRead()
        val f = fixture(testScheduler, devices = listOf(stored), photoStamp = { 5L })
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(read, scanKey = SCAN_KEY))
        testScheduler.advanceUntilIdle()

        val moved = f.store.knownDevices.value.single()
        assertEquals(BOARD_ID, moved.id)
        assertEquals("My Marker", moved.name, "the local name moves with the device")
        assertEquals("device_photo_${BOARD_ID}_5", moved.photoPath)
        assertEquals(read.knownProfiles.map { it.id }, moved.knownProfiles.map { it.id })
        assertEquals(read.knownProfiles.map { it.id }, moved.boardState?.knownProfiles?.map { it.id })
        // The moved device matches the board, so the read raises no prompt.
        assertTrue(f.delegate.pendingSyncs.value.isEmpty())
    }

    @Test
    fun readUnderTheBoardId_whenTheBoardIdIsStored_dropsTheOldDeviceAndKeepsTheStoredOne() = runTest {
        // The phone moved the device first, and the Wear sync sent the board id to the watch.
        val (stored, read) = oldAndRead()
        val alreadyMoved = read.copy(name = "Moved", boardState = baseline(read))
        val f = fixture(testScheduler, devices = listOf(stored, alreadyMoved), photoStamp = { 9L })
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(read, scanKey = SCAN_KEY))
        testScheduler.advanceUntilIdle()

        val kept = f.store.knownDevices.value.single()
        assertEquals(BOARD_ID, kept.id)
        assertEquals("Moved", kept.name, "the newer copy under the board id stays")
        assertEquals("device_photo_${BOARD_ID}_9", kept.photoPath, "it had no photo, so it takes the old one")
    }

    @Test
    fun readOfAKnownBoard_keepsTheFirmwareVersionOfTheLatestAdvertisement() = runTest {
        val old = AdvertisementData(boardType = "Cocker", fwVersion = "2026.28.0")
        val new = AdvertisementData(boardType = "Cocker", fwVersion = "2026.29.1")
        val stored = testDevice().let { it.copy(advertisementData = old, boardState = baseline(it)) }
        val f = fixture(testScheduler, devices = listOf(stored))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(testDevice().copy(advertisementData = new)))
        testScheduler.advanceUntilIdle()
        assertEquals(new, f.store.knownDevices.value.single().advertisementData)

        // A platform that gives no advertisement keeps the version that the store has.
        f.readBus.emit(DeviceReadResult(testDevice()))
        testScheduler.advanceUntilIdle()
        assertEquals(new, f.store.knownDevices.value.single().advertisementData)
    }

    @Test
    fun takeDevice_takesTheFirmwareVersionOfTheRead() = runTest {
        val old = AdvertisementData(boardType = "Cocker", fwVersion = "2026.28.0")
        val new = AdvertisementData(boardType = "Cocker", fwVersion = "2026.29.1")
        val stored = testDevice(bootText = "Local").let {
            it.copy(advertisementData = old, boardState = baseline(it))
        }
        val f = fixture(testScheduler, devices = listOf(stored))
        testScheduler.advanceUntilIdle()
        f.readBus.emit(DeviceReadResult(testDevice(bootText = "Board").copy(advertisementData = new)))
        testScheduler.advanceUntilIdle()

        f.delegate.resolveConflict(testDeviceId, ConflictChoice.TakeDevice)
        testScheduler.advanceUntilIdle()

        assertEquals(new, f.store.knownDevices.value.single().advertisementData)
    }

    private companion object {
        /** A MAC address, the scan key of a board on Android. */
        const val SCAN_KEY = "AC:EB:E6:81:A8:5C"
        val BOARD_ID: Uuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
    }

    @Test
    fun dismissNewDevice_clearsThePromptWithoutStoringAnything() = runTest {
        val f = fixture(testScheduler)
        testScheduler.advanceUntilIdle()
        f.readBus.emit(DeviceReadResult(testDevice()))
        testScheduler.advanceUntilIdle()

        f.delegate.dismissNewDevice(testDeviceId)
        testScheduler.advanceUntilIdle()

        assertNull(f.delegate.pendingSyncs.value[testDeviceId])
        assertTrue(f.store.knownDevices.value.isEmpty())
    }

    @Test
    fun readAfterTheUpdateMovesAStoredPlaceholderModeToUnknownWithoutAConflict() = runTest {
        // An older build stored MECHANICAL and OFF for a mode that the board reports as UNKNOWN.
        val placeholder = testDevice(
            profiles = listOf(testProfile().copy(fireMode = MarkerFireMode.MECHANICAL, eyeMode = MarkerEyeMode.OFF)),
        )
        val local = placeholder.copy(boardState = baseline(placeholder))
        val board = testDevice(
            profiles = listOf(testProfile().copy(fireMode = MarkerFireMode.UNKNOWN, eyeMode = MarkerEyeMode.UNKNOWN)),
        )
        val f = fixture(testScheduler, listOf(local))
        testScheduler.advanceUntilIdle()

        f.readBus.emit(DeviceReadResult(board))
        testScheduler.advanceUntilIdle()

        assertTrue(f.delegate.pendingSyncs.value.isEmpty(), "the placeholder is not a conflict")
        val stored = f.store.knownDevices.value.single()
        assertEquals(MarkerFireMode.UNKNOWN, stored.knownProfiles.single().fireMode)
        assertEquals(MarkerEyeMode.UNKNOWN, stored.knownProfiles.single().eyeMode)
        assertEquals(MarkerFireMode.UNKNOWN, stored.boardState?.knownProfiles?.single()?.fireMode)
    }
}
