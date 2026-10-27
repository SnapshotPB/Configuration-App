package com.snapshotpb.configuration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.SwipeToDismissBox
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.resolveBoardId
import com.snapshotpb.configuration.common.composable.boardLanguageLabel
import com.snapshotpb.configuration.common.composable.boardScreenBrightnessLabel
import com.snapshotpb.configuration.common.composable.displayName
import com.snapshotpb.configuration.common.composable.markerEyeModeLabel
import com.snapshotpb.configuration.common.composable.markerFireModeLabel
import com.snapshotpb.configuration.common.screen.delegate.ConflictChoice
import com.snapshotpb.configuration.common.screen.delegate.DeviceSyncDelegate
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegate
import com.snapshotpb.configuration.common.screen.delegate.PendingSync
import com.snapshotpb.configuration.common.screen.delegate.SaveResult
import com.snapshotpb.configuration.common.screen.device.BOARD_ADVERTISED_NAME
import com.snapshotpb.configuration.demo.WearDemoBoard
import com.snapshotpb.configuration.model.BoardLanguage
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.configuration.model.DiscoveredDevice
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.newDeviceName
import com.snapshotpb.configuration.screen.BoardSettingSelectionScreen
import com.snapshotpb.configuration.screen.ConflictResolutionScreen
import com.snapshotpb.configuration.screen.ConnectingScreen
import com.snapshotpb.configuration.screen.DeviceConfigScreen
import com.snapshotpb.configuration.screen.DeviceListScreen
import com.snapshotpb.configuration.screen.ProfileEditScreen
import com.snapshotpb.configuration.screen.ProfileFieldStepperScreen
import com.snapshotpb.configuration.screen.ProfileSelectionScreen
import com.snapshotpb.configuration.screen.SaveResultDialog
import com.snapshotpb.configuration.screen.screenOffDelayOptions
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.board_screen_off_delay_seconds
import com.snapshotpb.core.resources.configure_device_screen_bolt_mode_label
import com.snapshotpb.core.resources.configure_device_screen_brightness_label
import com.snapshotpb.core.resources.configure_device_screen_language_label
import com.snapshotpb.core.resources.configure_device_screen_off_delay_label
import com.snapshotpb.core.resources.device_default_name
import com.snapshotpb.core.resources.profile_label_eye_mode
import com.snapshotpb.core.resources.profile_label_fire_mode
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

enum class WearScreen {
    DeviceList,
    Connecting,

    /**
     * The choice between the board values and the watch values after a reconnect that found a
     * difference. Back goes to [DeviceList] and leaves the conflict open.
     */
    ConflictResolution,
    DeviceConfig,
    ProfileSelection,
    LanguageSelection,
    BrightnessSelection,
    ScreenOffDelaySelection,

    /** The editor of the active profile. Back goes to [DeviceConfig]. */
    ProfileEdit,

    /** The stepper of one numeric field of the active profile. Back goes to [ProfileEdit]. */
    ProfileFieldEdit,

    /** The fire mode list of the active profile. Back goes to [ProfileEdit]. */
    FireModeSelection,

    /** The bolt mode list of the board. Back goes to [DeviceConfig]. */
    BoltModeSelection,

    /** The eye mode list of the active profile. Back goes to [ProfileEdit]. */
    EyeModeSelection,
}

/** The screen that Back opens from this screen, or null for the root screen. */
internal val WearScreen.parent: WearScreen?
    get() = when (this) {
        WearScreen.DeviceList -> null

        WearScreen.Connecting,
        WearScreen.ConflictResolution,
        WearScreen.DeviceConfig -> WearScreen.DeviceList

        WearScreen.ProfileSelection,
        WearScreen.BoltModeSelection,
        WearScreen.LanguageSelection,
        WearScreen.BrightnessSelection,
        WearScreen.ScreenOffDelaySelection,
        WearScreen.ProfileEdit -> WearScreen.DeviceConfig

        WearScreen.ProfileFieldEdit,
        WearScreen.FireModeSelection,
        WearScreen.EyeModeSelection -> WearScreen.ProfileEdit
    }

/**
 * True for the screens that show or edit the open device: [WearScreen.DeviceConfig] and each screen
 * below it. A conflict for that device opens over these screens.
 */
internal val WearScreen.showsOpenDevice: Boolean
    get() = when (this) {
        WearScreen.DeviceList,
        WearScreen.Connecting,
        WearScreen.ConflictResolution -> false

        WearScreen.DeviceConfig,
        WearScreen.ProfileSelection,
        WearScreen.LanguageSelection,
        WearScreen.BrightnessSelection,
        WearScreen.ScreenOffDelaySelection,
        WearScreen.ProfileEdit,
        WearScreen.ProfileFieldEdit,
        WearScreen.FireModeSelection,
        WearScreen.BoltModeSelection,
        WearScreen.EyeModeSelection -> true
    }

/**
 * The screen to show in place of this screen when the data it needs is missing, or null when the
 * screen can show. A phone push can remove the device or change the active profile while a screen
 * is open, and a restored activity can come back to a screen whose data is gone. The fallback
 * moves up the back stack to the nearest screen that can show, not to a blank screen.
 */
internal fun WearScreen.fallback(
    hasConnectingDevice: Boolean,
    hasDevice: Boolean,
    hasConflict: Boolean,
    hasActiveProfile: Boolean,
    hasEditingField: Boolean,
): WearScreen? = when (this) {
    WearScreen.DeviceList -> null
    WearScreen.Connecting -> if (hasConnectingDevice) null else WearScreen.DeviceList
    WearScreen.ConflictResolution -> when {
        !hasDevice -> WearScreen.DeviceList
        !hasConflict -> WearScreen.DeviceConfig
        else -> null
    }

    WearScreen.DeviceConfig,
    WearScreen.ProfileSelection,
    WearScreen.BoltModeSelection,
    WearScreen.LanguageSelection,
    WearScreen.BrightnessSelection,
    WearScreen.ScreenOffDelaySelection -> if (hasDevice) null else WearScreen.DeviceList

    WearScreen.ProfileEdit,
    WearScreen.FireModeSelection,
    WearScreen.EyeModeSelection -> when {
        !hasDevice -> WearScreen.DeviceList
        !hasActiveProfile -> WearScreen.DeviceConfig
        else -> null
    }

    WearScreen.ProfileFieldEdit -> when {
        !hasDevice -> WearScreen.DeviceList
        !hasActiveProfile -> WearScreen.DeviceConfig
        !hasEditingField -> WearScreen.ProfileEdit
        else -> null
    }
}

/** Keeps the board under connection across an activity recreation. */
private val DiscoveredDeviceSaver: Saver<DiscoveredDevice?, Any> = listSaver<DiscoveredDevice?, String?>(
    save = { device ->
        if (device == null) emptyList() else listOf(device.id, device.name, device.boardType, device.fwVersion)
    },
    restore = restore@{ values ->
        val id = values.getOrNull(0) ?: return@restore null
        val name = values.getOrNull(1) ?: return@restore null
        DiscoveredDevice(id = id, name = name, boardType = values.getOrNull(2), fwVersion = values.getOrNull(3))
    },
)

/** Keeps the set of boards whose read result arrived across an activity recreation. */
private val UuidSetSaver: Saver<Set<Uuid>, Any> = listSaver<Set<Uuid>, String>(
    save = { ids -> ids.map { it.toString() } },
    restore = { values -> values.map { Uuid.parse(it) }.toSet() },
)

@Composable
fun WearApp(
    bluetoothManager: BluetoothManager,
    bluetoothStateManager: BluetoothStateManager,
    bluetoothRequestManager: BluetoothRequestManager,
    deviceStore: DeviceStore,
    deviceSyncDelegate: DeviceSyncDelegate,
    deviceReadResultBus: DeviceReadResultBus,
    deviceViewModelDelegate: DeviceViewModelDelegate,
    demoBoard: WearDemoBoard,
) {
    val coroutineScope = rememberCoroutineScope()

    // The navigation state is saveable, so a recreation of the activity keeps the open screen.
    // Each value is a plain type that a Bundle holds; the screens read the rest from the store.
    var currentScreen by rememberSaveable { mutableStateOf(WearScreen.DeviceList) }
    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var connectingDevice by rememberSaveable(stateSaver = DiscoveredDeviceSaver) {
        mutableStateOf<DiscoveredDevice?>(null)
    }
    // The field under edit on the stepper screen, by its name. A chip sets it when it opens the
    // stepper.
    var editingFieldName by rememberSaveable { mutableStateOf<String?>(null) }
    val editingField = MarkerProfileField.entries.firstOrNull { it.name == editingFieldName }

    val knownDevices by deviceStore.knownDevices.collectAsState()
    val bluetoothState by bluetoothStateManager.bluetoothState.collectAsState()
    val connectedDevices by bluetoothStateManager.connectedDevices.collectAsState()
    val deviceState by bluetoothManager.deviceState.collectAsState()
    val connectionProgress by bluetoothStateManager.connectionProgress.collectAsState()
    val pendingSyncs by deviceSyncDelegate.pendingSyncs.collectAsState()
    // A board that the scan found has only its scan key until the read or the 0xFFE1 advertisement
    // gives its board id (#127, #128).
    val boardIds by bluetoothStateManager.boardIds.collectAsState()
    val writeErrors by deviceViewModelDelegate.writeErrors.collectAsState()
    val writesInFlight by deviceViewModelDelegate.writesInFlight.collectAsState()

    // The result of the last save, shown as a short message over the open screen. It is not
    // saveable: after an activity recreation, the Save chip still shows a failure from writeErrors.
    var saveResult by remember { mutableStateOf<SaveResult?>(null) }
    LaunchedEffect(deviceViewModelDelegate) {
        deviceViewModelDelegate.saveResults.collect { saveResult = it }
    }

    // The boards whose read result arrived since their last connect started. The engine marks
    // step 4 complete a moment before it emits the result, and the store already holds a known
    // board before the read, so neither the progress nor the store can tell that the read landed.
    var readBoardIds by rememberSaveable(stateSaver = UuidSetSaver) { mutableStateOf(emptySet<Uuid>()) }
    LaunchedEffect(deviceReadResultBus) {
        deviceReadResultBus.results.collect { result -> readBoardIds = readBoardIds + result.device.id }
    }

    val discoveredDevices = remember(deviceState, boardIds, bluetoothState, demoBoard) {
        // Boards by the service UUID that the scan filters on, not by the name (#128). A board
        // whose advertisement gave its device_id shows under that id, so a known board matches.
        val realDevices = deviceState.boardScanKeys()
            .mapNotNull { key -> deviceState.devices[key]?.let { key to it } }
            .map { (key, peripheral) ->
                val adv = deviceState.advertisementData[key]
                DiscoveredDevice(
                    id = resolveBoardId(boardIds, key),
                    name = peripheral.name ?: BOARD_ADVERTISED_NAME,
                    boardType = adv?.boardType,
                    fwVersion = adv?.fwVersion
                )
            }
        // A debug build adds the demo board while it is on. The release binding adds nothing.
        demoBoard.withDemoDevice(realDevices, scanning = bluetoothState is BluetoothState.Scanning)
    }

    // A connect from the device screens can move the device to its board id (#127). Follow it, or
    // the screen loses its device and falls back to the list.
    val movedDeviceId = selectedDeviceId
        ?.takeIf { id -> knownDevices.none { it.id.toString() == id } }
        ?.let { id -> boardIds[id] }
        ?.takeIf { boardId -> knownDevices.any { it.id.toString() == boardId } }
    LaunchedEffect(movedDeviceId) {
        if (movedDeviceId != null) selectedDeviceId = movedDeviceId
    }
    val currentDevice = knownDevices.firstOrNull { it.id.toString() == (movedDeviceId ?: selectedDeviceId) }

    // The watch edits the active profile only. A phone push can change it while a profile screen
    // is open, so each profile screen reads it from the current device.
    val activeProfile = currentDevice?.let { device ->
        device.knownProfiles.firstOrNull { it.id == device.activeProfileId }
    }
    val currentConflict = currentDevice?.let { pendingSyncs[it.id] as? PendingSync.Conflict }

    // A screen whose data is gone moves up to the nearest screen that can show.
    val fallbackScreen = currentScreen.fallback(
        hasConnectingDevice = connectingDevice != null,
        hasDevice = currentDevice != null,
        hasConflict = currentConflict != null,
        hasActiveProfile = activeProfile != null,
        // A phone push can change the fire mode, the bolt mode or the eye mode while a stepper is
        // open. When the table then hides the field, the stepper closes: the board does not read a
        // field that the table hides for the mode, so the watch must not show it as adjustable.
        hasEditingField = editingField != null &&
            (activeProfile == null || editingField.isShownFor(
                activeProfile,
                currentDevice.boltMode ?: MarkerBoltMode.CLOSED,
            )),
    )
    LaunchedEffect(fallbackScreen) {
        if (fallbackScreen != null) currentScreen = fallbackScreen
    }

    // A Save finds a board edit made in the board menu, and the write does not start. The conflict
    // then opens over the edit screen, as the phone opens its dialog over any screen. The device
    // list and the connect flow open a conflict themselves.
    LaunchedEffect(currentConflict) {
        if (currentConflict != null && currentScreen.showsOpenDevice) {
            currentScreen = WearScreen.ConflictResolution
        }
    }

    // Cancel stops the attempt, so a board that the user left does not stay connected.
    fun cancelConnect() {
        connectingDevice?.let { device ->
            coroutineScope.launch { bluetoothRequestManager.send(BluetoothRequest.Disconnect(device.id)) }
        }
        connectingDevice = null
        currentScreen = WearScreen.DeviceList
    }

    fun goBack() {
        if (currentScreen == WearScreen.Connecting) {
            cancelConnect()
            return
        }
        val parent = currentScreen.parent ?: return
        if (currentScreen == WearScreen.ProfileFieldEdit) editingFieldName = null
        if (parent == WearScreen.DeviceList) selectedDeviceId = null
        currentScreen = parent
    }

    val screenContent: @Composable () -> Unit = content@{
        when (currentScreen) {
            WearScreen.DeviceList -> {
                DeviceListScreen(
                    knownDevices = knownDevices,
                    photoUri = deviceStore::photoUri,
                    bluetoothState = bluetoothState,
                    discoveredDevices = discoveredDevices,
                    onDeviceSelected = { device ->
                        selectedDeviceId = device.id.toString()
                        // A conflict that the user left open comes back before the device.
                        currentScreen = if (pendingSyncs[device.id] is PendingSync.Conflict) {
                            WearScreen.ConflictResolution
                        } else {
                            WearScreen.DeviceConfig
                        }
                    },
                    onDirectConnectSelected = { discDevice ->
                        connectingDevice = discDevice
                        readBoardIds = readBoardIds - Uuid.parse(resolveBoardId(boardIds, discDevice.id))
                        currentScreen = WearScreen.Connecting
                        coroutineScope.launch {
                            bluetoothRequestManager.send(BluetoothRequest.Connect(discDevice.id))
                        }
                    },
                    onStartScan = {
                        coroutineScope.launch {
                            bluetoothRequestManager.send(BluetoothRequest.StartScan)
                        }
                    },
                    onStopScan = {
                        coroutineScope.launch {
                            bluetoothRequestManager.send(BluetoothRequest.StopScan)
                        }
                    },
                    onGrantPermission = {
                        coroutineScope.launch {
                            bluetoothRequestManager.send(BluetoothRequest.RequestPermission)
                        }
                    },
                    onEnableBluetooth = {
                        coroutineScope.launch {
                            bluetoothRequestManager.send(BluetoothRequest.EnableBluetooth)
                        }
                    },
                    onEnableLocation = {
                        coroutineScope.launch {
                            bluetoothRequestManager.send(BluetoothRequest.EnableLocation)
                        }
                    }
                )
            }

            WearScreen.Connecting -> {
                val device = connectingDevice ?: return@content
                // A new board is ready when its read result sits in pendingSyncs, because that is
                // where Continue takes it from. A known board is ready when the bus delivered its
                // read result. Without the gate a tap right after step 4 opens the config screen
                // with no device, or leaves a conflict that no screen can then resolve.
                // The board id once the read gives it, because the store and pendingSyncs use it.
                val connectingUuid = remember(device.id, boardIds) { Uuid.parse(resolveBoardId(boardIds, device.id)) }
                val isKnown = knownDevices.any { it.id == connectingUuid }
                val syncReady = pendingSyncs.containsKey(connectingUuid) ||
                    (isKnown && connectingUuid in readBoardIds)
                val defaultName = stringResource(Res.string.device_default_name)
                ConnectingScreen(
                    device = device,
                    connectionProgress = connectionProgress,
                    syncReady = syncReady,
                    onContinue = {
                        val deviceUuid = connectingUuid
                        when (val pending = pendingSyncs[deviceUuid]) {
                            is PendingSync.NewDevice -> coroutineScope.launch {
                                // The same name as the phone gives a new device (see newDeviceName).
                                deviceSyncDelegate.confirmNewDevice(
                                    deviceUuid,
                                    newDeviceName(pending.device.name, defaultName),
                                    null,
                                )
                                selectedDeviceId = deviceUuid.toString()
                                connectingDevice = null
                                currentScreen = WearScreen.DeviceConfig
                            }

                            is PendingSync.Conflict -> {
                                // The user decides which side wins. A silent "take the board"
                                // would delete unsaved watch edits, and the sync would then
                                // delete them on the phone as well.
                                selectedDeviceId = deviceUuid.toString()
                                connectingDevice = null
                                currentScreen = WearScreen.ConflictResolution
                            }

                            null -> {
                                selectedDeviceId = deviceUuid.toString()
                                connectingDevice = null
                                currentScreen = WearScreen.DeviceConfig
                            }
                        }
                    },
                    onCancel = { cancelConnect() }
                )
            }

            WearScreen.ConflictResolution -> {
                val device = currentDevice ?: return@content
                val conflict = currentConflict ?: return@content
                fun resolve(choice: ConflictChoice) {
                    coroutineScope.launch {
                        deviceSyncDelegate.resolveConflict(device.id, choice)
                        currentScreen = WearScreen.DeviceConfig
                    }
                }
                ConflictResolutionScreen(
                    deviceName = conflict.local.name,
                    lockedOut = conflict.remote.lockoutEnabled,
                    onTakeDevice = { resolve(ConflictChoice.TakeDevice) },
                    onTakeLocal = { resolve(ConflictChoice.TakeLocal) },
                )
            }

            WearScreen.DeviceConfig -> {
                val device = currentDevice ?: return@content
                val isConnected = connectedDevices.contains(device.id.toString())

                DeviceConfigScreen(
                    device = device,
                    isConnected = isConnected,
                    saveInFlight = device.id in writesInFlight,
                    saveFailed = device.id in writeErrors,
                    photoUri = deviceStore::photoUri,
                    onSelectProfileTriggered = {
                        currentScreen = WearScreen.ProfileSelection
                    },
                    onTuneProfileTriggered = {
                        currentScreen = WearScreen.ProfileEdit
                    },
                    onSelectBoltModeTriggered = {
                        currentScreen = WearScreen.BoltModeSelection
                    },
                    onSelectLanguageTriggered = {
                        currentScreen = WearScreen.LanguageSelection
                    },
                    onSelectBrightnessTriggered = {
                        currentScreen = WearScreen.BrightnessSelection
                    },
                    onSelectScreenOffDelayTriggered = {
                        currentScreen = WearScreen.ScreenOffDelaySelection
                    },
                    onSaveToBoard = {
                        coroutineScope.launch {
                            deviceViewModelDelegate.saveChangesToBoard(device.id.toString())
                        }
                    },
                    onDiscard = {
                        deviceViewModelDelegate.discardPendingChanges(device.id.toString())
                    },
                    onDisconnect = {
                        coroutineScope.launch {
                            bluetoothRequestManager.send(BluetoothRequest.Disconnect(device.id.toString()))
                        }
                    },
                    onConnect = {
                        coroutineScope.launch {
                            bluetoothRequestManager.send(BluetoothRequest.Connect(device.id.toString()))
                        }
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.ProfileSelection -> {
                val device = currentDevice ?: return@content
                ProfileSelectionScreen(
                    device = device,
                    onProfileSelected = { profile ->
                        deviceViewModelDelegate.updateBoardSettings(device.id.toString()) {
                            it.copy(activeProfileId = profile.id)
                        }
                        goBack()
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.LanguageSelection -> {
                val device = currentDevice ?: return@content
                BoardSettingSelectionScreen(
                    title = stringResource(Res.string.configure_device_screen_language_label),
                    options = BoardLanguage.entries,
                    selected = device.language,
                    optionLabel = { boardLanguageLabel(it) },
                    onSelect = { language ->
                        // The choice stays on the watch. The board gets it on the next save.
                        deviceViewModelDelegate.updateBoardSettings(device.id.toString()) {
                            it.copy(language = language)
                        }
                        goBack()
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.BrightnessSelection -> {
                val device = currentDevice ?: return@content
                BoardSettingSelectionScreen(
                    title = stringResource(Res.string.configure_device_screen_brightness_label),
                    options = BoardScreenBrightness.entries,
                    selected = device.screenBrightness,
                    optionLabel = { boardScreenBrightnessLabel(it) },
                    onSelect = { brightness ->
                        deviceViewModelDelegate.updateBoardSettings(device.id.toString()) {
                            it.copy(screenBrightness = brightness)
                        }
                        goBack()
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.ScreenOffDelaySelection -> {
                val device = currentDevice ?: return@content
                BoardSettingSelectionScreen(
                    title = stringResource(Res.string.configure_device_screen_off_delay_label),
                    options = screenOffDelayOptions(device.screenOffDelay),
                    selected = device.screenOffDelay,
                    optionLabel = { stringResource(Res.string.board_screen_off_delay_seconds, it.toString()) },
                    onSelect = { delay ->
                        deviceViewModelDelegate.updateBoardSettings(device.id.toString()) {
                            it.copy(screenOffDelay = delay)
                        }
                        goBack()
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.ProfileEdit -> {
                val device = currentDevice ?: return@content
                val profile = activeProfile ?: return@content
                val isConnected = connectedDevices.contains(device.id.toString())
                ProfileEditScreen(
                    device = device,
                    profile = profile,
                    isConnected = isConnected,
                    saveInFlight = device.id in writesInFlight,
                    saveFailed = device.id in writeErrors,
                    onFieldSelected = { field ->
                        editingFieldName = field.name
                        currentScreen = WearScreen.ProfileFieldEdit
                    },
                    onSelectFireModeTriggered = {
                        currentScreen = WearScreen.FireModeSelection
                    },
                    onSelectEyeModeTriggered = {
                        currentScreen = WearScreen.EyeModeSelection
                    },
                    onSaveToBoard = {
                        coroutineScope.launch {
                            deviceViewModelDelegate.saveChangesToBoard(device.id.toString())
                        }
                    },
                    onDiscard = {
                        deviceViewModelDelegate.discardProfileChanges(device.id.toString(), profile.id)
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.ProfileFieldEdit -> {
                val device = currentDevice ?: return@content
                val profile = activeProfile ?: return@content
                val field = editingField ?: return@content
                ProfileFieldStepperScreen(
                    profile = profile,
                    field = field,
                    boltMode = device.boltMode ?: MarkerBoltMode.CLOSED,
                    onCommit = { value ->
                        deviceViewModelDelegate.updateProfileField(device.id.toString(), profile.id, field, value)
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.FireModeSelection -> {
                val device = currentDevice ?: return@content
                val profile = activeProfile ?: return@content
                BoardSettingSelectionScreen(
                    title = stringResource(Res.string.profile_label_fire_mode),
                    options = MarkerFireMode.selectable,
                    selected = profile.fireMode,
                    optionLabel = { markerFireModeLabel(it) },
                    onSelect = { fireMode ->
                        deviceViewModelDelegate.updateProfile(device.id.toString(), profile.id) {
                            it.copy(fireMode = fireMode)
                        }
                        goBack()
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.BoltModeSelection -> {
                val device = currentDevice ?: return@content
                BoardSettingSelectionScreen(
                    title = stringResource(Res.string.configure_device_screen_bolt_mode_label),
                    options = MarkerBoltMode.selectable,
                    selected = device.boltMode,
                    optionLabel = { it.displayName() },
                    onSelect = { boltMode ->
                        deviceViewModelDelegate.updateBoardSettings(device.id.toString()) {
                            it.copy(boltMode = boltMode)
                        }
                        goBack()
                    },
                    onBack = { goBack() }
                )
            }

            WearScreen.EyeModeSelection -> {
                val device = currentDevice ?: return@content
                val profile = activeProfile ?: return@content
                BoardSettingSelectionScreen(
                    title = stringResource(Res.string.profile_label_eye_mode),
                    options = MarkerEyeMode.selectable,
                    selected = profile.eyeMode,
                    optionLabel = { markerEyeModeLabel(it) },
                    onSelect = { eyeMode ->
                        deviceViewModelDelegate.updateProfile(device.id.toString(), profile.id) {
                            it.copy(eyeMode = eyeMode)
                        }
                        goBack()
                    },
                    onBack = { goBack() }
                )
            }
        }
    }

    MaterialTheme {
        val hasParent = currentScreen.parent != null

        // The stem button and the system back gesture go up one screen, as the swipe does.
        BackHandler(enabled = hasParent) { goBack() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // The E2E tests find elements by their test tags as resource ids.
                .semantics { testTagsAsResourceId = true }
        ) {
            if (hasParent) {
                // A swipe from the left edge goes up one screen. The root screen has no box, so
                // there the system swipe closes the app. A new key gives each screen a new state.
                key(currentScreen) {
                    SwipeToDismissBox(
                        onDismissed = { goBack() },
                        state = rememberSwipeToDismissBoxState(),
                    ) { isBackground ->
                        if (!isBackground) screenContent()
                    }
                }
            } else {
                screenContent()
            }

            SaveResultDialog(result = saveResult, onDismiss = { saveResult = null })
        }
    }
}
