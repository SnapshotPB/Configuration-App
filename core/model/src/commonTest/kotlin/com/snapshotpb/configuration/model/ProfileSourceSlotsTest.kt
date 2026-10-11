package com.snapshotpb.configuration.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Tests for [MarkerProfile.sourceSlot] and the helpers that keep it (#119). */
class ProfileSourceSlotsTest {

    private fun id(index: Int) = Uuid.parse("11111111-2222-3333-4444-55555555556$index")

    private fun profile(index: Int, name: String, sourceSlot: Int?) =
        MarkerProfile(id = id(index), name = name, sourceSlot = sourceSlot)

    private fun device(profiles: List<MarkerProfile>, baseline: List<MarkerProfile>) = Device(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        name = "Board",
        lastSeen = LocalDate(2026, 9, 29),
        knownProfiles = profiles,
        boardState = BoardState(
            bootText = "SnapshotPB",
            activeProfileId = null,
            lockoutEnabled = false,
            knownProfiles = baseline,
        ),
    )

    @Test
    fun aSourceSlotAloneIsNoDivergence() {
        val device = device(
            profiles = listOf(profile(0, "A", sourceSlot = 1)),
            baseline = listOf(profile(0, "A", sourceSlot = null)),
        )

        assertFalse(device.hasDiverged())
        assertFalse(device.hasProfilesDiverged())
        assertFalse(device.isProfileDiverged(id(0)))
        assertTrue(device.knownProfiles.equalsIgnoringSourceSlots(device.boardState!!.knownProfiles))
    }

    @Test
    fun aSettingIsStillADivergence() {
        val device = device(
            profiles = listOf(profile(0, "A", sourceSlot = 0).copy(searSolenoidDwell = 99)),
            baseline = listOf(profile(0, "A", sourceSlot = 0)),
        )

        assertTrue(device.hasProfilesDiverged())
        assertTrue(device.isProfileDiverged(id(0)))
        assertFalse(device.knownProfiles.equalsIgnoringSourceSlots(device.boardState!!.knownProfiles))
    }

    @Test
    fun aWriteGivesEachProfileTheSlotOfItsIndex() {
        val written = device(
            profiles = listOf(profile(0, "B", sourceSlot = 1), profile(1, "D", sourceSlot = null)),
            baseline = emptyList(),
        ).withProfilesInWrittenSlots()

        assertEquals(listOf(0, 1), written.knownProfiles.map { it.sourceSlot })
    }

    @Test
    fun anInFlightEditFollowsItsProfileToTheWrittenSlot() {
        val requested = listOf(profile(0, "B", sourceSlot = 1), profile(1, "D", sourceSlot = null))
        val local = listOf(
            requested[0].copy(searSolenoidDwell = 99),
            requested[1],
            // Added while the write was in flight: the board has no slot for it yet.
            profile(2, "E", sourceSlot = null),
        )

        val rebased = local.withSourceSlotsAfterWrite(requested, writtenCount = 2)

        assertEquals(listOf(0, 1, null), rebased.map { it.sourceSlot })
        assertEquals(99, rebased[0].searSolenoidDwell)
    }

    @Test
    fun aProfileThatTheWriteCutGetsNoSlot() {
        val requested = listOf(profile(0, "A", sourceSlot = 0), profile(1, "B", sourceSlot = 1))

        val rebased = requested.withSourceSlotsAfterWrite(requested, writtenCount = 1)

        assertEquals(listOf(0, null), rebased.map { it.sourceSlot })
    }
}
