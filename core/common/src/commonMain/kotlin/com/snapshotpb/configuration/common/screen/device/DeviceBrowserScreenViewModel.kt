package com.snapshotpb.configuration.common.screen.device

import androidx.lifecycle.ViewModel
import com.snapshotpb.configuration.bluetooth.BluetoothDeviceState
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.ots.getProfileUuid
import com.snapshotpb.configuration.bluetooth.resolveBoardId
import com.snapshotpb.configuration.bluetooth.toDeviceUuid
import com.snapshotpb.configuration.model.AdvertisementData
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardModel
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.BoardState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.DisplayWindow
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.SystemConfig
import com.snapshotpb.configuration.model.newDeviceName
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.configuration.store.devicePhotoStoreName
import com.snapshotpb.configuration.store.storedDevicePhotoName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The name the board advertises over BLE.
 *
 * Discovery does not use it any more: every target scans for the board service UUID (see
 * `SnapshotAdvertisement`, #128), because the name is not reliable on iOS. The app uses the name
 * only as the name of a board that reports none, and the simulated boards advertise it.
 */
const val BOARD_ADVERTISED_NAME = "Snapshot PB"

/**
 * Deterministic UUID of the demo device created by [DeviceBrowserScreenViewModel.addDemoDevice].
 * Matches `FAKE_DEVICE_ID` in `:core:fake-board` so a debug build's simulated board services the
 * same device seamlessly.
 */
const val DEMO_DEVICE_ID = "5350425f-4f54-4000-8000-000000000001"

abstract class DeviceBrowserScreenViewModel(
    private val bluetoothRequestManager: BluetoothRequestManager,
    bluetoothStateManager: BluetoothStateManager,
    private val deviceStore: DeviceStore,
    val systemConfig: SystemConfig,
    deviceState: Flow<BluetoothDeviceState>,
) : ViewModel() {

    val bluetoothState: StateFlow<BluetoothState> = bluetoothStateManager.bluetoothState
    val knownDevices: StateFlow<List<Device>> = deviceStore.knownDevices

    /**
     * Boards currently visible to the scanner, resolved against the store so an already-known board
     * keeps its name, photo and profiles instead of appearing as a blank stranger.
     *
     * Shared by every platform. The four per-platform copies of this derivation had already drifted:
     * only Android attached the parsed advertisement (so desktop/iOS/web could never show a real
     * firmware version), and desktop and browser each carried a `flow { while (true) { emit(Unit);
     * delay(500) } }` ticker that recomputed and recomposed the whole list twice a second for as
     * long as the screen was open, scan or no scan. Neither behaviour was intentional; both are
     * gone. Advertisement data flows through wherever a platform populates it — [BluetoothDeviceState]
     * carries it on every target, it is simply empty where nothing parses advertisements.
     */
    val discoveredDevices: Flow<List<Device>> =
        combine(deviceStore.knownDevices, deviceState, bluetoothStateManager.boardIds) { known, discovered, boardIds ->
            val knownById: Map<String, Device> = known.associateBy { it.id.toString() }
            discovered.boardScanKeys().map { scanKey ->
                // The board id from the 0xFFE1 advertisement (#128) or from a read this session
                // (#127). Before that, the scan key is the only id, and it also finds a device that
                // an older build stored under it.
                val boardId = resolveBoardId(boardIds, scanKey)
                val deviceId = boardId.toDeviceUuid()
                val advertisement = discovered.advertisementData[scanKey]
                val base = knownById[boardId] ?: knownById[scanKey] ?: Device(
                    id = deviceId,
                    name = "",
                    lastSeen = Clock.System.todayIn(TimeZone.currentSystemDefault()),
                    knownProfiles = emptyList(),
                )
                // Keep whatever the device already carried when this scan parsed nothing.
                base.copy(advertisementData = advertisement ?: base.advertisementData)
            }
        }

    suspend fun requestEnableBluetooth() =
        bluetoothRequestManager.send(BluetoothRequest.EnableBluetooth)

    suspend fun requestEnableLocation() =
        bluetoothRequestManager.send(BluetoothRequest.EnableLocation)

    suspend fun requestPermission() =
        bluetoothRequestManager.send(BluetoothRequest.RequestPermission)

    suspend fun scanStart() =
        bluetoothRequestManager.send(BluetoothRequest.StartScan)

    suspend fun scanStop() =
        bluetoothRequestManager.send(BluetoothRequest.StopScan)

    /**
     * True where a scan is one choice in a browser device list (the web). The screen then shows a
     * "Pair a board" button that calls [pairBoard] in its click handler, in place of the scan loop.
     */
    open val pairsWithDeviceChooser: Boolean get() = false

    /**
     * Opens the browser device list. Call it directly from a click handler, because the browser
     * opens the list only during the user activation of a gesture. Only for [pairsWithDeviceChooser].
     */
    open fun pairBoard() = Unit

    /**
     * Forgets a device locally. Also drops its stored photo, which previously stayed behind as an
     * orphaned file for the lifetime of the install. A photo file name has a stamp (see
     * versionedDevicePhotoStoreName), so the name comes from the stored path. The name without a
     * stamp, which older builds wrote, is deleted too.
     */
    fun deleteDevice(device: Device) {
        val deviceId = device.id.toString()
        storedDevicePhotoName(deviceId, device.photoPath)?.let(deviceStore::deleteStoredFile)
        deviceStore.deleteStoredFile(devicePhotoStoreName(deviceId))
        deviceStore.update { current -> current.filter { it.id != device.id } }
    }

    /**
     * Hook invoked when [addDemoDevice] creates the demo device. Debug builds override this to
     * also turn on the in-process simulated board so connecting to the demo device works live.
     */
    open fun onDemoDeviceAdded() = Unit

    /**
     * Populates [deviceStore] with a pre-configured demo device so a user without a physical board
     * can explore device configuration, profiles, display window layout, and Wear sync.
     */
    fun addDemoDevice(defaultName: String = "SnapshotPB") {
        onDemoDeviceAdded()
        val deviceUuid = Uuid.parse(DEMO_DEVICE_ID)
        val profiles = listOf(
            MarkerProfile(
                id = getProfileUuid(deviceUuid, 0),
                name = "Semi",
                fireMode = MarkerFireMode.SEMI,
                sourceSlot = 0,
            ),
            MarkerProfile(
                id = getProfileUuid(deviceUuid, 1),
                name = "Ramp",
                fireMode = MarkerFireMode.RAMPING_NXL_2026,
                eyeMode = MarkerEyeMode.REFLECTIVE,
                sourceSlot = 1,
            ),
        )
        val activeProfileId = profiles.first().id
        val bootText = "FAKE BOARD"
        val demoDevice = Device(
            id = deviceUuid,
            name = newDeviceName(BOARD_ADVERTISED_NAME, defaultName),
            lastSeen = Clock.System.todayIn(TimeZone.currentSystemDefault()),
            lastModified = Clock.System.now().toEpochMilliseconds(),
            knownProfiles = profiles,
            bootText = bootText,
            activeProfileId = activeProfileId,
            lockoutEnabled = false,
            language = BoardLanguage.EN,
            screenBrightness = BoardScreenBrightness.DEFAULT,
            screenOffDelay = 30,
            displayWindow = DisplayWindow.FULL_PANEL,
            boardModel = BoardModel.AUTOCOCKER,
            supportedProfileCount = Device.DEFAULT_PROFILE_COUNT,
            boardState = BoardState(
                bootText = bootText,
                activeProfileId = activeProfileId,
                lockoutEnabled = false,
                knownProfiles = profiles,
                language = BoardLanguage.EN,
                screenBrightness = BoardScreenBrightness.DEFAULT,
                screenOffDelay = 30,
                displayWindow = DisplayWindow.FULL_PANEL,
            ),
            advertisementData = AdvertisementData(
                boardType = "FAKE",
                fwVersion = "2026.29.0-dev",
                firmwareVersion = FirmwareVersion(year = 2026, week = 29, count = 0, dev = true),
            ),
        )
        deviceStore.update { current ->
            if (current.any { it.id == deviceUuid }) current else current + demoDevice
        }
    }
}
