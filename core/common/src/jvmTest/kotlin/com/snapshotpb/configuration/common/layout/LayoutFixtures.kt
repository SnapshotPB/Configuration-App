package com.snapshotpb.configuration.common.layout

import com.snapshotpb.configuration.common.screen.device.DeviceBrowserActions
import com.snapshotpb.configuration.common.screen.profile.ProfilesActions
import com.snapshotpb.configuration.firmware.FirmwareStatus
import com.snapshotpb.configuration.model.BoardBluetoothAtBoot
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.FirmwareAsset
import com.snapshotpb.configuration.model.FirmwareAssets
import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.GripFrame
import com.snapshotpb.configuration.model.HardwareTarget
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
private const val LATEST_FIRMWARE_VERSION = "2026.43.1"

/** A board with four profiles and a value for each board setting. Its board state equals it. */
internal fun layoutDevice(name: String = "Snapshot", diverged: Boolean = false): Device {
    require(name.length <= Device.MAX_NAME_LENGTH) {
        "Device name \"$name\" exceeds ${Device.MAX_NAME_LENGTH} characters"
    }
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
    for (profile in profiles) {
        require(profile.name.length <= Device.MAX_PROFILE_NAME_LENGTH) {
            "Profile name \"${profile.name}\" exceeds ${Device.MAX_PROFILE_NAME_LENGTH} characters"
        }
    }
    for (profile in boardProfiles) {
        require(profile.name.length <= Device.MAX_PROFILE_NAME_LENGTH) {
            "Board profile name \"${profile.name}\" exceeds ${Device.MAX_PROFILE_NAME_LENGTH} characters"
        }
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
        name = "NXL Event",
        fireMode = MarkerFireMode.RAMPING_NXL_2026,
    ),
    MarkerProfile(
        id = PROFILE_ID_3,
        name = "Ramp",
        fireMode = MarkerFireMode.CUSTOM_RAMPING,
    ),
    MarkerProfile(
        id = PROFILE_ID_4,
        name = "Full Auto",
        fireMode = MarkerFireMode.FULL_AUTO,
    ),
)

/** A connected board status with a newer stable release available, for layout tests. */
internal fun layoutFirmwareStatus(
    deviceId: String = DEFAULT_DEVICE_ID.toString(),
): FirmwareStatus = FirmwareStatus(
    deviceId = deviceId,
    connected = true,
    boardVersion = FirmwareVersion(2026, 29, 1, dev = false),
    target = HardwareTarget(board = "snapshot_v2", qualifiers = "esp32c3"),
    latest = FirmwareManifest(
        version = LATEST_FIRMWARE_VERSION,
        tag = "v$LATEST_FIRMWARE_VERSION",
        channel = FirmwareChannel.STABLE,
        compatibleHardware = listOf("snapshot_v2"),
        assets = FirmwareAssets(
            image = FirmwareAsset(
                name = "snapshot_v2-$LATEST_FIRMWARE_VERSION-app.signed.bin",
                url = "https://snapshotpb.github.io/Firmware/snapshot_v2/$LATEST_FIRMWARE_VERSION/snapshot_v2-$LATEST_FIRMWARE_VERSION-app.signed.bin",
                size = 900_000L,
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            ),
        ),
    ),
    reason = null,
)

/** Device list actions that do nothing, for layout tests. */
internal fun noOpDeviceBrowserActions(): DeviceBrowserActions = DeviceBrowserActions(
    onDeviceSelected = {},
    onDeviceConnect = {},
    onDeviceDeleted = {},
    onStartScanning = {},
    onStopScanning = {},
    onPairBoard = {},
    onRequestPermission = {},
    onRequestEnableBluetooth = {},
    onRequestEnableLocation = {},
    onAddDemoDevice = {},
    onOpenBluetoothLog = {},
)

/** Profile list actions that do nothing, for layout tests. */
internal fun noOpProfilesActions(): ProfilesActions = ProfilesActions(
    onShowImagePicker = {},
    onShowGalleryPicker = {},
    onConnect = {},
    onDisconnect = {},
    onOpenConfiguration = {},
    onOpenProfile = {},
    onDeleteProfile = {},
    onAddProfile = {},
    onDiscardChanges = {},
    onSaveToBoard = {},
)
