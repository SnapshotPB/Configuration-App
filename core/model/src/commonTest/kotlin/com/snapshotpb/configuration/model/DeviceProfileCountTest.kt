package com.snapshotpb.configuration.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Tests for [Device.canDeleteProfile]: the board keeps at least [Device.MIN_PROFILE_COUNT] profiles. */
class DeviceProfileCountTest {

    private fun device(profileCount: Int) = Device(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        name = "Board",
        lastSeen = LocalDate(2026, 9, 29),
        knownProfiles = (0 until profileCount).map { index ->
            MarkerProfile(id = Uuid.parse("11111111-2222-3333-4444-55555555556$index"), name = "P$index")
        },
    )

    @Test
    fun aDeviceWithTwoProfilesCanDeleteOne() {
        assertTrue(device(profileCount = 2).canDeleteProfile())
    }

    @Test
    fun aDeviceWithOneProfileCannotDeleteIt() {
        assertFalse(device(profileCount = 1).canDeleteProfile())
    }

    @Test
    fun aDeviceWithNoProfilesCannotDelete() {
        assertFalse(device(profileCount = 0).canDeleteProfile())
    }
}
