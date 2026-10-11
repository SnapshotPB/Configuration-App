package com.snapshotpb.configuration.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Cover for the step that moves a stored placeholder mode to UNKNOWN after the update. */
class UnknownModesTest {

    private val profileId = Uuid.parse("11111111-2222-3333-4444-555555555556")

    private fun profile(fireMode: MarkerFireMode, eyeMode: MarkerEyeMode) =
        MarkerProfile(id = profileId, name = "P0", fireMode = fireMode, eyeMode = eyeMode)

    private fun device(local: MarkerProfile, baseline: MarkerProfile?) = Device(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        name = "Marker",
        lastSeen = LocalDate(2026, 9, 1),
        knownProfiles = listOf(local),
        boardState = baseline?.let {
            BoardState(bootText = "Hi", activeProfileId = null, lockoutEnabled = false, knownProfiles = listOf(it))
        },
    )

    private val placeholder = profile(MarkerFireMode.MECHANICAL, MarkerEyeMode.OFF)
    private val unknown = profile(MarkerFireMode.UNKNOWN, MarkerEyeMode.UNKNOWN)
    private val boardReportsUnknown = device(local = unknown, baseline = null)

    @Test
    fun anUneditedPlaceholderBecomesUnknownInTheProfileAndTheBaseline() {
        val migrated = device(local = placeholder, baseline = placeholder)
            .withPlaceholderModesAsUnknown(boardReportsUnknown)

        assertEquals(unknown, migrated.knownProfiles.single())
        assertEquals(unknown, migrated.boardState?.knownProfiles?.single())
    }

    @Test
    fun aModeThatTheUserSelectedStays() {
        val edited = profile(MarkerFireMode.SEMI, MarkerEyeMode.REFLECTIVE)
        val migrated = device(local = edited, baseline = placeholder)
            .withPlaceholderModesAsUnknown(boardReportsUnknown)

        assertEquals(edited, migrated.knownProfiles.single())
        // The baseline moves, so the edit still shows as unsaved against what the board holds.
        assertEquals(unknown, migrated.boardState?.knownProfiles?.single())
    }

    @Test
    fun aBoardThatReportsAKnownModeChangesNothing() {
        val stored = device(local = placeholder, baseline = placeholder)
        val boardReportsMechanical = device(local = placeholder, baseline = null)

        assertEquals(stored, stored.withPlaceholderModesAsUnknown(boardReportsMechanical))
    }

    @Test
    fun aDeviceWithoutABaselineChangesNothing() {
        val stored = device(local = placeholder, baseline = null)

        assertEquals(stored, stored.withPlaceholderModesAsUnknown(boardReportsUnknown))
    }
}
