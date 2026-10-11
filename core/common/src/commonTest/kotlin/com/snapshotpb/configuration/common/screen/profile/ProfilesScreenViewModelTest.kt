package com.snapshotpb.configuration.common.screen.profile

import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegateImpl
import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.common.testDeviceStore
import com.snapshotpb.configuration.common.testProfile
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cover for [ProfilesScreenViewModel.deleteProfile]: the delete clears a deleted active profile,
 * every later profile moves down one slot and takes the id of its new slot, and the last profile
 * stays, because the board refuses a write with no profiles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesScreenViewModelTest {

    private val slot0 = getProfileUuid(testDeviceId, 0)
    private val slot1 = getProfileUuid(testDeviceId, 1)
    private val slot2 = getProfileUuid(testDeviceId, 2)

    private val threeProfiles = listOf(
        testProfile(id = slot0, name = "A"),
        testProfile(id = slot1, name = "B"),
        testProfile(id = slot2, name = "C"),
    )

    @BeforeTest
    fun setUp() {
        // The view model builds a StateFlow on viewModelScope, which uses the main dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(store: DeviceStore, scheduler: TestCoroutineScheduler) = ProfilesScreenViewModel(
        deviceId = testDeviceId.toString(),
        deviceStore = store,
        bluetoothStateManager = BluetoothStateManager(),
        deviceViewModelDelegate = DeviceViewModelDelegateImpl(
            deviceStore = store,
            bluetoothRequestManager = BluetoothRequestManager(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            dispatcher = UnconfinedTestDispatcher(scheduler),
        ),
    )

    private fun storeWith(device: Device, scheduler: TestCoroutineScheduler): DeviceStore =
        testDeviceStore(dispatcher = StandardTestDispatcher(scheduler), devices = listOf(device))

    @Test
    fun deleteProfile_movesTheLaterProfilesDownOneSlot() = runTest {
        val store = storeWith(testDevice(profiles = threeProfiles).copy(activeProfileId = slot2), testScheduler)
        testScheduler.advanceUntilIdle()

        viewModel(store, testScheduler).deleteProfile(threeProfiles[1])
        testScheduler.advanceUntilIdle()

        val stored = store.knownDevices.value.single()
        assertEquals(listOf("A", "C"), stored.knownProfiles.map { it.name })
        assertEquals(listOf(slot0, slot1), stored.knownProfiles.map { it.id })
        assertEquals(slot1, stored.activeProfileId, "the active profile follows its new slot")
    }

    @Test
    fun deleteProfile_clearsADeletedActiveProfile() = runTest {
        val store = storeWith(testDevice(profiles = threeProfiles).copy(activeProfileId = slot1), testScheduler)
        testScheduler.advanceUntilIdle()

        viewModel(store, testScheduler).deleteProfile(threeProfiles[1])
        testScheduler.advanceUntilIdle()

        val stored = store.knownDevices.value.single()
        assertEquals(listOf("A", "C"), stored.knownProfiles.map { it.name })
        // The profile that moved into slot 1 must not become active by its new id.
        assertNull(stored.activeProfileId)
    }

    @Test
    fun deleteProfile_ofTheLastProfileLeavesTheOthersAlone() = runTest {
        val store = storeWith(testDevice(profiles = threeProfiles).copy(activeProfileId = slot0), testScheduler)
        testScheduler.advanceUntilIdle()

        viewModel(store, testScheduler).deleteProfile(threeProfiles[2])
        testScheduler.advanceUntilIdle()

        val stored = store.knownDevices.value.single()
        assertEquals(listOf(slot0, slot1), stored.knownProfiles.map { it.id })
        assertEquals(listOf("A", "B"), stored.knownProfiles.map { it.name })
        assertEquals(slot0, stored.activeProfileId)
    }

    @Test
    fun deleteThenAdd_eachProfileKeepsItsSourceSlot() = runTest {
        // Three profiles as a board read gives them: each knows the slot that it came from.
        val read = threeProfiles.mapIndexed { index, profile -> profile.copy(sourceSlot = index) }
        val store = storeWith(testDevice(profiles = read), testScheduler)
        testScheduler.advanceUntilIdle()

        viewModel(store, testScheduler).deleteProfile(read[0])
        testScheduler.advanceUntilIdle()
        ProfileAddViewModel(testDeviceId.toString(), store).addProfile("D")
        testScheduler.advanceUntilIdle()

        // The list has three profiles again, and the ids follow the slots. The source slots still
        // tell the write which board slot each profile came from (#119); the new one has none.
        val stored = store.knownDevices.value.single()
        assertEquals(listOf("B", "C", "D"), stored.knownProfiles.map { it.name })
        assertEquals(listOf(slot0, slot1, slot2), stored.knownProfiles.map { it.id })
        assertEquals(listOf(1, 2, null), stored.knownProfiles.map { it.sourceSlot })
    }

    @Test
    fun deleteProfile_ofTheOnlyProfileKeepsIt() = runTest {
        val only = testProfile(id = slot0, name = "A")
        val store = storeWith(testDevice(profiles = listOf(only)).copy(activeProfileId = slot0), testScheduler)
        testScheduler.advanceUntilIdle()

        viewModel(store, testScheduler).deleteProfile(only)
        testScheduler.advanceUntilIdle()

        // A save with no profiles sends a zero-length Profiles write, which the board refuses.
        val stored = store.knownDevices.value.single()
        assertEquals(listOf(only), stored.knownProfiles)
        assertEquals(slot0, stored.activeProfileId)
    }

    @Test
    fun deleteProfile_downToOneProfileThenStops() = runTest {
        val store = storeWith(testDevice(profiles = threeProfiles.take(2)), testScheduler)
        testScheduler.advanceUntilIdle()
        val viewModel = viewModel(store, testScheduler)

        viewModel.deleteProfile(threeProfiles[1])
        testScheduler.advanceUntilIdle()
        viewModel.deleteProfile(threeProfiles[0])
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("A"), store.knownDevices.value.single().knownProfiles.map { it.name })
    }
}
