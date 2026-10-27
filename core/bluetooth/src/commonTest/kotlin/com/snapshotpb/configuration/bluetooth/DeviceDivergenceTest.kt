package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.boardValuesMatch
import com.snapshotpb.configuration.model.equalsIgnoringSourceSlots
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DeviceDivergenceTest {

    private val deviceId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val profileId1 = Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val profileId2 = Uuid.parse("ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val date = LocalDate(2026, 5, 30)

    private val baseProfile1 = MarkerProfile(
        id = profileId1,
        name = "Semi-Auto",
        fireRateCap = 10
    )
    private val baseProfile2 = MarkerProfile(
        id = profileId2,
        name = "Ramp",
        fireRateCap = 15
    )

    private val baseBoardState = BoardState(
        bootText = "SnapshotPB",
        activeProfileId = profileId1,
        lockoutEnabled = false,
        knownProfiles = listOf(baseProfile1, baseProfile2)
    )

    private val baseDevice = Device(
        id = deviceId,
        name = "Test Paintball Board",
        lastSeen = date,
        bootText = "SnapshotPB",
        activeProfileId = profileId1,
        lockoutEnabled = false,
        knownProfiles = listOf(baseProfile1, baseProfile2),
        boardState = baseBoardState
    )

    @Test
    fun testOfflineEditsResultInDivergedTrue() {
        // Initial state: matches boardState
        assertFalse(baseDevice.hasDiverged())

        // Edit bootText
        val deviceWithEditedBootText = baseDevice.copy(bootText = "New Boot Text")
        assertTrue(deviceWithEditedBootText.hasDiverged())

        // Edit activeProfileId
        val deviceWithEditedProfile = baseDevice.copy(activeProfileId = profileId2)
        assertTrue(deviceWithEditedProfile.hasDiverged())

        // Edit language. A side with no language (stored before the app read it, or a board that
        // reports none) is not a divergence, because no write can make the two agree.
        val withLanguages = baseDevice.copy(
            language = BoardLanguage.EN,
            boardState = baseBoardState.copy(language = BoardLanguage.EN),
        )
        assertFalse(withLanguages.hasDiverged())
        assertTrue(withLanguages.copy(language = BoardLanguage.FR).hasDiverged())
        assertFalse(baseDevice.copy(language = BoardLanguage.FR).hasDiverged())
        assertFalse(withLanguages.copy(language = null).hasDiverged())

        // Edit the screen settings. They follow the same rule as the language: a side with no
        // value is not a divergence, because no write can make the two agree.
        val withScreen = baseDevice.copy(
            screenBrightness = BoardScreenBrightness.DEFAULT,
            screenOffDelay = 5,
            boltMode = MarkerBoltMode.CLOSED,
            boardState = baseBoardState.copy(
                screenBrightness = BoardScreenBrightness.DEFAULT,
                screenOffDelay = 5,
                boltMode = MarkerBoltMode.CLOSED,
            ),
        )
        assertFalse(withScreen.hasDiverged())
        assertTrue(withScreen.copy(screenBrightness = BoardScreenBrightness.HIGH).hasDiverged())
        assertTrue(withScreen.copy(screenOffDelay = 30).hasDiverged())
        assertTrue(withScreen.copy(boltMode = MarkerBoltMode.OPEN).hasDiverged())
        assertFalse(baseDevice.copy(screenOffDelay = 30).hasDiverged())
        assertFalse(baseDevice.copy(boltMode = MarkerBoltMode.OPEN).hasDiverged())
        assertFalse(withScreen.copy(screenBrightness = null, screenOffDelay = null, boltMode = null).hasDiverged())

        // lockoutEnabled is a read-only board fact, NOT a divergence field: changing it alone must
        // not raise an unsaved/conflict state the user cannot push back to the board.
        val deviceWithEditedLockout = baseDevice.copy(lockoutEnabled = true)
        assertFalse(deviceWithEditedLockout.hasDiverged())

        // Edit a profile setting
        val editedProfile1 = baseProfile1.copy(fireRateCap = 12)
        val deviceWithEditedProfileSetting = baseDevice.copy(
            knownProfiles = listOf(editedProfile1, baseProfile2)
        )
        assertTrue(deviceWithEditedProfileSetting.hasDiverged())
        assertTrue(deviceWithEditedProfileSetting.hasProfilesDiverged())
        assertTrue(deviceWithEditedProfileSetting.isProfileDiverged(profileId1))
        assertFalse(deviceWithEditedProfileSetting.isProfileDiverged(profileId2))
    }

    @Test
    fun testDiscardingChangesRestoresOriginalValues() {
        // Create edited device
        val editedDevice = baseDevice.copy(
            bootText = "Modified text",
            activeProfileId = profileId2,
            lockoutEnabled = true,
            knownProfiles = listOf(
                baseProfile1.copy(fireRateCap = 20),
                baseProfile2
            )
        )
        assertTrue(editedDevice.hasDiverged())

        // Discard: copy values back from boardState (mimics DeviceViewModelDelegateImpl.discardPendingChanges)
        val bs = editedDevice.boardState ?: error("BoardState should not be null")
        val discardedDevice = editedDevice.copy(
            bootText = bs.bootText,
            activeProfileId = bs.activeProfileId,
            lockoutEnabled = bs.lockoutEnabled,
            knownProfiles = bs.knownProfiles,
            language = bs.language ?: editedDevice.language,
            screenBrightness = bs.screenBrightness ?: editedDevice.screenBrightness,
            screenOffDelay = bs.screenOffDelay ?: editedDevice.screenOffDelay,
            boltMode = bs.boltMode ?: editedDevice.boltMode,
        )

        // Verify restoration
        assertFalse(discardedDevice.hasDiverged())
        assertEquals(baseDevice.bootText, discardedDevice.bootText)
        assertEquals(baseDevice.activeProfileId, discardedDevice.activeProfileId)
        assertEquals(baseDevice.lockoutEnabled, discardedDevice.lockoutEnabled)
        assertEquals(baseDevice.knownProfiles, discardedDevice.knownProfiles)
    }

    @Test
    fun testReconnectingWithMatchingBoardPreservesPendingOfflineEdits() {
        // Local device has offline edits
        val localDevice = baseDevice.copy(
            bootText = "Edited Offline"
        )
        assertTrue(localDevice.hasDiverged())

        // Remote device read from board matches the cached boardState
        val remoteDevice = baseDevice.copy(
            lastSeen = LocalDate(2026, 6, 1) // remote has newer lastSeen
        )

        // Verify that remote matches local boardState
        val bs = localDevice.boardState ?: error("BoardState should not be null")
        assertTrue(remoteDevice.matchesBoardState(bs))

        // Simulating DeviceSyncDelegate: no conflict is raised, local edited values are preserved,
        // and only the lastSeen timestamp/photo details are updated from remote.
        val resolvedDevice = localDevice.copy(
            lastSeen = remoteDevice.lastSeen
        )

        assertEquals("Edited Offline", resolvedDevice.bootText)
        assertTrue(resolvedDevice.hasDiverged())
        assertEquals(LocalDate(2026, 6, 1), resolvedDevice.lastSeen)
    }

    @Test
    fun testReconnectingWithModifiedBoardTriggersConflict() {
        // Local device has offline edits
        val localDevice = baseDevice.copy(
            bootText = "Edited Offline"
        )
        assertTrue(localDevice.hasDiverged())

        // Remote device read from board does NOT match local cached boardState
        // e.g. someone else changed the active profile on the board
        val remoteDevice = baseDevice.copy(
            activeProfileId = profileId2
        )

        // Verify remote does not match local boardState, which triggers a conflict
        val bs = localDevice.boardState ?: error("BoardState should not be null")
        assertFalse(remoteDevice.matchesBoardState(bs))
    }

    // Mirrors DeviceSyncDelegate.matchesBoardState: lockoutEnabled is excluded (read-only fact).
    private fun Device.matchesBoardState(bs: BoardState): Boolean =
        bootText == bs.bootText &&
            activeProfileId == bs.activeProfileId &&
            boardValuesMatch(bs.language, language) &&
            boardValuesMatch(bs.screenBrightness, screenBrightness) &&
            boardValuesMatch(bs.screenOffDelay, screenOffDelay) &&
            boardValuesMatch(bs.boltMode, boltMode) &&
            knownProfiles.equalsIgnoringSourceSlots(bs.knownProfiles)

    @Test
    fun testClampChangedSaveClearsDivergenceOnBothPaths() {
        // Editable profile holds over-range values the firmware silently clamps on write
        // (fireRateCap 2000 -> 1000, eyeThreshold 400 -> 100). Before saving, this diverges.
        val editableProfile = baseProfile1.copy(fireRateCap = 2_000, eyeThreshold = 400)
        val editable = baseDevice.copy(
            knownProfiles = listOf(editableProfile, baseProfile2),
        )
        assertTrue(editable.hasDiverged())

        // Mirror BluetoothConnectionEngine.writeDeviceConfiguration + onWriteResult: clamp ONCE,
        // then set BOTH the editable device and the cached baseline to the clamped values.
        val clamped = editable.clampedToFirmwareRanges()
        val reconciled = clamped.copy(
            boardState = BoardState(
                bootText = clamped.bootText,
                activeProfileId = clamped.activeProfileId,
                lockoutEnabled = clamped.lockoutEnabled,
                knownProfiles = clamped.knownProfiles,
            )
        )

        // (a) editable-device path: no lingering "unsaved" badge after a clamp-changed save.
        assertFalse(reconciled.hasDiverged())
        // (b) conflict path: the board reads back exactly the clamped values it stored, so a
        // reconnect matches the baseline instead of looping on PendingSync.Conflict.
        assertTrue(reconciled.matchesBoardState(reconciled.boardState!!))
        // Sanity: the firmware would indeed have changed these values.
        assertEquals(1_000, reconciled.knownProfiles[0].fireRateCap)
        assertEquals(100, reconciled.knownProfiles[0].eyeThreshold)
    }
}
