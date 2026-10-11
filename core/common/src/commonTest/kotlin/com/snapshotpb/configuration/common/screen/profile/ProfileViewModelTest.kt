package com.snapshotpb.configuration.common.screen.profile

import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegateImpl
import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.common.testDeviceStore
import com.snapshotpb.configuration.common.testProfile
import com.snapshotpb.configuration.common.testProfileId
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Regression cover for the store race that stranded this screen on "Loading profile…" forever (and
 * crashed the add-profile screen) whenever it composed before [com.snapshotpb.configuration.store.DeviceStore]
 * finished its asynchronous disk load — routinely, on Android saved-state restore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private fun args() = ProfileViewModel.Args(
        deviceId = testDeviceId.toString(),
        profileId = testProfileId.toString(),
    )

    @Test
    fun profileLoad_reportsLoadingWhileTheStoreIsStillReadingFromDisk() = runTest {
        // The store runs on a SEPARATE scheduler that this test never advances, so its
        // init-launched disk load stays queued for the whole test no matter how often the test's
        // own scheduler yields. (Tying it to `testScheduler` would let `first()`'s suspension run
        // the load before the assertion, which is exactly the race being pinned down here.)
        val store = testDeviceStore(
            dispatcher = StandardTestDispatcher(TestCoroutineScheduler()),
            devices = listOf(testDevice()),
        )
        val viewModel = viewModel(store)

        // The disk load has not run: the store answers an empty list, which must NOT be reported as
        // "this profile does not exist".
        assertIs<ProfileLoad.Loading>(viewModel.profileLoad().first())
    }

    @Test
    fun profileLoad_resolvesOnceTheStoreHasLoaded() = runTest {
        val store = testDeviceStore(
            dispatcher = StandardTestDispatcher(testScheduler),
            devices = listOf(testDevice()),
        )
        val viewModel = viewModel(store)

        testScheduler.advanceUntilIdle()

        val load = viewModel.profileLoad().first()
        assertIs<ProfileLoad.Found>(load)
        assertEquals("P0", load.profile.name)
        assertNull(load.boardProfile, "no board state has been read yet")
    }

    @Test
    fun profileLoad_reportsMissingOnlyAfterTheStoreIsLoaded() = runTest {
        val store = testDeviceStore(dispatcher = StandardTestDispatcher(testScheduler))
        val viewModel = viewModel(store)

        testScheduler.advanceUntilIdle()

        assertIs<ProfileLoad.Missing>(
            viewModel.profileLoad().first(),
            "an empty loaded store means the profile is genuinely gone",
        )
    }

    @Test
    fun profileLoad_surfacesTheBoardCopyForDivergenceBadges() = runTest {
        val boardProfile = testProfile(searSolenoidDwell = 60)
        val device = testDevice(
            profiles = listOf(testProfile(searSolenoidDwell = 45)),
            boardState = BoardState(
                bootText = "SnapshotPB",
                activeProfileId = null,
                lockoutEnabled = false,
                knownProfiles = listOf(boardProfile),
            ),
        )
        val store = testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(device))
        testScheduler.advanceUntilIdle()

        val load = viewModel(store).profileLoad().first()
        assertIs<ProfileLoad.Found>(load)
        assertEquals(45, load.profile.searSolenoidDwell)
        assertEquals(60, load.boardProfile?.searSolenoidDwell)
    }

    @Test
    fun profileLoad_isMissingWhenTheDeviceExistsButTheProfileDoesNot() = runTest {
        val otherProfileId = Uuid.parse("aaaaaaaa-2222-3333-4444-555555555556")
        val store = testDeviceStore(
            UnconfinedTestDispatcher(testScheduler),
            listOf(testDevice(profiles = listOf(testProfile(id = otherProfileId)))),
        )
        testScheduler.advanceUntilIdle()

        assertIs<ProfileLoad.Missing>(viewModel(store).profileLoad().first())
    }

    @Test
    fun updateProfile_writesThroughToTheStore() = runTest {
        val store = testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(testDevice()))
        testScheduler.advanceUntilIdle()

        viewModel(store).updateProfile(testProfile(name = "Renamed"))
        testScheduler.advanceUntilIdle()

        assertEquals("Renamed", store.knownDevices.value.single().knownProfiles.single().name)
    }

    @Test
    fun updateProfile_appliesTheStoreClampThroughTheDelegate() = runTest {
        val store = testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(testDevice()))
        testScheduler.advanceUntilIdle()

        viewModel(store).updateProfile(testProfile(searSolenoidDwell = 200_000))
        testScheduler.advanceUntilIdle()

        assertEquals(99_999, store.knownDevices.value.single().knownProfiles.single().searSolenoidDwell)
    }

    @Test
    fun seedDraft_setsTheDraftOnlyOneTime() = runTest {
        // The screen seeds the draft on each Found emission. A store write after an edit must not
        // replace the edit, and a rotation recomposes the screen against the same view model.
        val store = testDeviceStore(UnconfinedTestDispatcher(testScheduler), listOf(testDevice()))
        val viewModel = viewModel(store)
        assertNull(viewModel.draft)

        viewModel.seedDraft(testProfile(name = "Stored"))
        viewModel.editDraft(testProfile(name = "Edited"))
        viewModel.seedDraft(testProfile(name = "Newer"))

        assertEquals("Edited", viewModel.draft?.name)
    }

    /** The screen commits through the shared delegate, so the test builds the real one. */
    private fun viewModel(store: DeviceStore) = ProfileViewModel(
        args = args(),
        deviceStore = store,
        deviceViewModelDelegate = DeviceViewModelDelegateImpl(
            deviceStore = store,
            bluetoothRequestManager = BluetoothRequestManager(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            dispatcher = UnconfinedTestDispatcher(),
        ),
    )
}
