package com.snapshotpb.configuration.common.screen.profile

import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.common.testDevice
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.common.testDeviceStore
import com.snapshotpb.configuration.common.testProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * A board read derives each profile id from the device id and the slot index. The app must give
 * a profile the same id when it adds or deletes one, or the next read after a save differs from
 * the local list only in `id` and raises a conflict that "Take local" can never clear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSlotIdTest {

    private val slot0 = getProfileUuid(testDeviceId, 0)
    private val slot1 = getProfileUuid(testDeviceId, 1)
    private val slot2 = getProfileUuid(testDeviceId, 2)

    @Test
    fun addProfile_givesTheNewProfileTheIdOfItsSlot() = runTest {
        val store = testDeviceStore(
            dispatcher = StandardTestDispatcher(testScheduler),
            devices = listOf(testDevice(profiles = listOf(testProfile(id = slot0)))),
        )
        testScheduler.advanceUntilIdle()
        val viewModel = ProfileAddViewModel(testDeviceId.toString(), store)

        val newId = viewModel.addProfile("Ramp")
        testScheduler.advanceUntilIdle()

        val stored = store.knownDevices.value.single().knownProfiles
        assertEquals(slot1.toString(), newId)
        assertEquals(listOf(slot0, slot1), stored.map { it.id })
    }

    @Test
    fun addProfile_repairsIdsThatDoNotMatchTheirSlot() = runTest {
        // A device stored by an older build carries a random id.
        val legacyId = Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val store = testDeviceStore(
            dispatcher = StandardTestDispatcher(testScheduler),
            devices = listOf(
                testDevice(profiles = listOf(testProfile(id = legacyId))).copy(activeProfileId = legacyId)
            ),
        )
        testScheduler.advanceUntilIdle()
        val viewModel = ProfileAddViewModel(testDeviceId.toString(), store)

        viewModel.addProfile("Ramp")
        testScheduler.advanceUntilIdle()

        val stored = store.knownDevices.value.single()
        assertEquals(listOf(slot0, slot1), stored.knownProfiles.map { it.id })
        assertEquals(slot0, stored.activeProfileId, "the active profile follows its new id")
    }

    @Test
    fun withSlotProfileIds_afterADeleteMovesTheLaterProfilesDownOneSlot() {
        val device = testDevice(
            profiles = listOf(
                testProfile(id = slot0, name = "A"),
                testProfile(id = slot2, name = "C"),
            ),
        ).copy(activeProfileId = slot2)

        val remapped = device.withSlotProfileIds()

        assertEquals(listOf(slot0, slot1), remapped.knownProfiles.map { it.id })
        assertEquals(listOf("A", "C"), remapped.knownProfiles.map { it.name })
        assertEquals(slot1, remapped.activeProfileId, "the active profile keeps its position")
    }

    @Test
    fun withSlotProfileIds_keepsAnActiveIdThatMatchesNoProfile() {
        val device = testDevice(profiles = listOf(testProfile(id = slot0))).copy(activeProfileId = null)

        assertNull(device.withSlotProfileIds().activeProfileId)
    }
}
