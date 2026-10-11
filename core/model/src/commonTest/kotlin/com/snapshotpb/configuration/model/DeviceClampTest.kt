package com.snapshotpb.configuration.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** Tests for the profile-slot cut in [Device.clampedToFirmwareRanges] (L16). */
class DeviceClampTest {

    private val deviceId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun profileIds(count: Int): List<Uuid> =
        (0 until count).map { index -> Uuid.parse("11111111-2222-3333-4444-55555555556$index") }

    private fun device(profileCount: Int, supportedProfileCount: Int, activeIndex: Int? = 0): Device {
        val ids = profileIds(profileCount)
        return Device(
            id = deviceId,
            name = "Board",
            lastSeen = LocalDate(2026, 9, 22),
            knownProfiles = ids.mapIndexed { index, id -> MarkerProfile(id = id, name = "P$index") },
            activeProfileId = activeIndex?.let { ids[it] },
            supportedProfileCount = supportedProfileCount,
        )
    }

    @Test
    fun keepsEveryProfileThatFitsTheBoardSlots() {
        val clamped = device(profileCount = 3, supportedProfileCount = 4).clampedToFirmwareRanges()

        assertEquals(listOf("P0", "P1", "P2"), clamped.knownProfiles.map { it.name })
    }

    @Test
    fun cutsTheListToTheSlotsTheBoardReports() {
        val clamped = device(profileCount = 4, supportedProfileCount = 2).clampedToFirmwareRanges()

        assertEquals(listOf("P0", "P1"), clamped.knownProfiles.map { it.name })
    }

    @Test
    fun neverSendsMoreThanTheProtocolCap() {
        // Profiles.profiles has max_count:4. A board that reports more slots still gets four.
        val clamped = device(profileCount = 6, supportedProfileCount = 8).clampedToFirmwareRanges()

        assertEquals(Device.DEFAULT_PROFILE_COUNT, clamped.knownProfiles.size)
    }

    @Test
    fun anUnreportedSlotCountFallsBackToTheProtocolCap() {
        val clamped = device(profileCount = 5, supportedProfileCount = 0).clampedToFirmwareRanges()

        assertEquals(Device.DEFAULT_PROFILE_COUNT, clamped.knownProfiles.size)
    }

    @Test
    fun anActiveProfileThatTheCutDropsBecomesUnset() {
        val clamped = device(profileCount = 4, supportedProfileCount = 2, activeIndex = 3)
            .clampedToFirmwareRanges()

        assertNull(clamped.activeProfileId)
    }

    @Test
    fun anActiveProfileThatTheCutKeepsStaysActive() {
        val original = device(profileCount = 4, supportedProfileCount = 2, activeIndex = 1)

        assertEquals(original.activeProfileId, original.clampedToFirmwareRanges().activeProfileId)
    }
}
