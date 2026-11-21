package com.snapshotpb.configuration.common.layout

import com.snapshotpb.configuration.model.BoardBluetoothAtBoot
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.GripFrame
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

private val DEFAULT_DEVICE_ID = Uuid.parse("11111111-2222-3333-4444-555555555550")
private val PROFILE_ID_1 = Uuid.parse("11111111-2222-3333-4444-555555555561")
private val PROFILE_ID_2 = Uuid.parse("11111111-2222-3333-4444-555555555562")
private val PROFILE_ID_3 = Uuid.parse("11111111-2222-3333-4444-555555555563")
private val PROFILE_ID_4 = Uuid.parse("11111111-2222-3333-4444-555555555564")

/** A board with four profiles and a value for each board setting. Its board state equals it. */
internal fun layoutDevice(name: String = "Snapshot PB", diverged: Boolean = false): Device {
    val profiles = layoutProfiles()
    val activeProfileId = profiles.first().id
    val bootText = "SNAPSHOT"
    val language = BoardLanguage.EN
    val screenBrightness = BoardScreenBrightness.DEFAULT
    val screenOffDelay = 30
    val displayWindow = GripFrame.E1.window
    val boltMode = MarkerBoltMode.CLOSED
    val bluetoothAtBoot = BoardBluetoothAtBoot.RESTORE
    val boardProfiles = if (diverged) {
        profiles.mapIndexed { index, profile ->
            if (index == 1) profile.copy(name = "Board NXL") else profile
        }
    } else {
        profiles
    }
    return Device(
        id = DEFAULT_DEVICE_ID,
        name = name,
        lastSeen = LocalDate(2026, 11, 21),
        knownProfiles = profiles,
        photoPath = "",
        bootText = bootText,
        activeProfileId = activeProfileId,
        language = language,
        screenBrightness = screenBrightness,
        screenOffDelay = screenOffDelay,
        displayWindow = displayWindow,
        boltMode = boltMode,
        bluetoothAtBoot = bluetoothAtBoot,
        boardState = BoardState(
            bootText = bootText,
            activeProfileId = activeProfileId,
            lockoutEnabled = false,
            knownProfiles = boardProfiles,
            language = language,
            screenBrightness = screenBrightness,
            screenOffDelay = screenOffDelay,
            displayWindow = displayWindow,
            boltMode = boltMode,
            bluetoothAtBoot = bluetoothAtBoot,
        ),
        advertisementData = null,
    )
}

/** Four marker profiles with fixed ids and different fire modes, for layout tests. */
internal fun layoutProfiles(): List<MarkerProfile> = listOf(
    MarkerProfile(
        id = PROFILE_ID_1,
        name = "Practice",
        fireMode = MarkerFireMode.SEMI,
    ),
    MarkerProfile(
        id = PROFILE_ID_2,
        name = "Tournament NXL",
        fireMode = MarkerFireMode.RAMPING_NXL_2026,
    ),
    MarkerProfile(
        id = PROFILE_ID_3,
        name = "Custom Ramp",
        fireMode = MarkerFireMode.CUSTOM_RAMPING,
    ),
    MarkerProfile(
        id = PROFILE_ID_4,
        name = "Full Auto",
        fireMode = MarkerFireMode.FULL_AUTO,
    ),
)
