package com.snapshotpb.configuration.common.screen.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.rotate
import org.jetbrains.compose.resources.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.common.composable.ClickableCard
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.navigation.NavigationController
import com.snapshotpb.configuration.common.navigation.ScreenKeyConnectingDevice
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfiles
import com.snapshotpb.configuration.common.screen.device.composable.DeviceListTitle
import com.snapshotpb.configuration.common.screen.device.composable.KnownDeviceCard
import com.snapshotpb.configuration.common.screen.device.composable.KnownDeviceList
import com.snapshotpb.configuration.common.screen.device.composable.bluetooth.BluetoothDisabled
import com.snapshotpb.configuration.common.screen.device.composable.bluetooth.BluetoothPermissionDenied
import com.snapshotpb.configuration.common.screen.device.composable.bluetooth.ConnectingToDevice
import com.snapshotpb.configuration.common.screen.device.composable.bluetooth.LocationDisabled
import com.snapshotpb.configuration.common.screen.device.composable.bluetooth.NeedsPermission
import com.snapshotpb.configuration.common.screen.device.composable.bluetooth.PairBoard
import com.snapshotpb.configuration.common.screen.device.composable.bluetooth.ScanForDevices
import com.snapshotpb.configuration.common.screen.device.composable.bluetooth.ScanningForDevices
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.version.AppVersion
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.app_version_label
import com.snapshotpb.core.resources.arrow_back
import com.snapshotpb.core.resources.bluetooth_icon
import com.snapshotpb.core.resources.device_default_name
import com.snapshotpb.configuration.common.composable.ConfirmDeleteDialog
import com.snapshotpb.core.resources.delete_device_dialog_message
import com.snapshotpb.core.resources.delete_device_dialog_title
import com.snapshotpb.core.resources.device_discovered_card_info
import com.snapshotpb.core.resources.device_fallback_name
import com.snapshotpb.core.resources.device_screen_bluetooth_options
import com.snapshotpb.core.resources.device_screen_list_discovered_devices
import com.snapshotpb.core.resources.device_screen_no_saved_devices
import com.snapshotpb.core.resources.device_screen_saved_configurations
import com.snapshotpb.core.resources.device_screen_title
import com.snapshotpb.core.resources.device_tap_to_connect
import com.snapshotpb.core.resources.firmware_version_unknown
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

private val log = Arbor.tag("DeviceScreen")

@Composable
fun DeviceBrowserScreen(
    navigationController: NavigationController,
) {
    val deviceScreenViewModel: DeviceBrowserScreenViewModel by rememberInstance()
    val knownDevices: List<Device> by deviceScreenViewModel.knownDevices.collectAsState()
    val bluetoothState: BluetoothState by deviceScreenViewModel.bluetoothState.collectAsState()
    val scannedDevices: List<Device> by deviceScreenViewModel.discoveredDevices.collectAsState(
        emptyList()
    )
    val scope = rememberCoroutineScope()
    var shouldScan: Boolean by remember { mutableStateOf(false) }
    // On the web a scan is one choice in the browser device list. The click handler opens the list
    // directly, because the browser opens it only during the user activation of the click.
    val pairsWithDeviceChooser = deviceScreenViewModel.pairsWithDeviceChooser
    val onPairBoard: () -> Unit = {
        log.d { "Pair a board requested." }
        deviceScreenViewModel.pairBoard()
    }

    val onRequestPermission: () -> Unit = {
        log.d { "Bluetooth permissions requested." }
        scope.launch { deviceScreenViewModel.requestPermission() }
    }
    val onRequestEnableBluetooth: () -> Unit = {
        log.d { "Enable Bluetooth requested." }
        scope.launch { deviceScreenViewModel.requestEnableBluetooth() }
    }
    val onRequestEnableLocation: () -> Unit = {
        log.d { "Enable location services requested." }
        scope.launch { deviceScreenViewModel.requestEnableLocation() }
    }
    val onDeviceSelected: (Device) -> Unit = { device ->
        log.d { "Device selected: $device" }
        shouldScan = false
        // Connection is now an explicit action on the Profiles screen, so just open it.
        navigationController.goTo(ScreenKeyProfiles(device.id.toString()))
    }
    val onDeviceConnect: (Device) -> Unit = { device ->
        log.d { "Device connect requested: $device" }
        shouldScan = false
        navigationController.goTo(ScreenKeyConnectingDevice(device.id.toString()))
    }
    // Deleting is destructive and has no undo, so it goes through a confirmation rather than
    // firing on a single tap of an icon button sitting next to the "open device" target.
    var devicePendingDelete: Device? by remember { mutableStateOf(null) }
    val onDeviceDeleted: (Device) -> Unit = { device -> devicePendingDelete = device }

    devicePendingDelete?.let { pending ->
        ConfirmDeleteDialog(
            title = stringResource(Res.string.delete_device_dialog_title),
            message = stringResource(
                Res.string.delete_device_dialog_message,
                pending.name.ifEmpty { stringResource(Res.string.device_default_name) },
            ),
            onConfirm = {
                log.d { "Device deleted: $pending" }
                deviceScreenViewModel.deleteDevice(pending)
                devicePendingDelete = null
            },
            onDismiss = { devicePendingDelete = null },
        )
    }

    DisposableEffect(shouldScan) {
        val scanJob = scope.launch {
            log.d { "Launching scan job." }
            while (shouldScan) {
                if (bluetoothState == BluetoothState.Ready) {
                    log.d { "Device not scanning, starting scan." }
                    deviceScreenViewModel.scanStart()
                }
                delay(1_000)
            }
        }
        onDispose {
            scanJob.cancel()
            if (bluetoothState == BluetoothState.Scanning) {
                log.d { "Device scanning, stopping scan." }
                // Tear down on a scope that outlives this composition. The
                // remembered `scope` is cancelled in the same forget cycle, so a
                // launch on it can be dropped before scanStop() emits StopScan,
                // leaking an active BLE scan on platforms without an onPause
                // backstop (desktop/iOS). viewModelScope survives navigation.
                deviceScreenViewModel.viewModelScope.launch {
                    deviceScreenViewModel.scanStop()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Text(
                stringResource(Res.string.device_screen_title),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // The app's version surface (docs/versioning-and-release-plan.md §4).
            // This screen is the shared entry point on every platform, so one line here
            // covers Android, desktop, iOS and web without a per-platform About screen.
            // Dev builds show the `-dev` marker. Build with
            // `-Psnapshotpb.stampBuildMetadata=true` to also show the `+<sha>` of the commit.
            Text(
                stringResource(Res.string.app_version_label, AppVersion.NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTwoPane = fitsTwoPanes(maxWidth, maxHeight)
            if (isTwoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Pane: Known/Saved Devices
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        item { Space(16.dp) }
                        item {
                            DeviceListTitle(stringResource(Res.string.device_screen_saved_configurations))
                        }
                        if (knownDevices.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.device_screen_no_saved_devices),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            itemsIndexed(
                                knownDevices,
                                key = { _, device -> device.id.toString() }
                            ) { index, device ->
                                KnownDeviceCard(
                                    first = index == 0,
                                    device = device,
                                    onDeviceSelected = onDeviceSelected,
                                    onDeviceDeleted = onDeviceDeleted,
                                )
                            }
                        }
                    }

                    // Right Pane: Active Scanning/Connecting State
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        item { Space(16.dp) }
                        item {
                            DeviceListTitle(stringResource(Res.string.device_screen_bluetooth_options))
                        }

                        when (val state = bluetoothState) {
                            BluetoothState.NeedsPermission -> item {
                                NeedsPermission(onRequestPermission)
                            }

                            BluetoothState.Ready -> if (pairsWithDeviceChooser) {
                                discoveredDevices(scannedDevices, onDeviceConnect)
                                item { PairBoard(onClick = onPairBoard) }
                            } else {
                                item {
                                    ScanForDevices(onClick = {
                                        log.d { "Scanning requested." }
                                        shouldScan = true
                                    })
                                }
                            }

                            BluetoothState.Scanning -> {
                                item {
                                    if (scannedDevices.isNotEmpty()) {
                                        DeviceListTitle(stringResource(Res.string.device_screen_list_discovered_devices))
                                    }
                                }
                                itemsIndexed(
                                    scannedDevices,
                                    key = { _, device -> device.id.toString() }
                                ) { index, device ->
                                    Column {
                                        if (index > 0) Space(8.dp)
                                        DiscoveredDeviceCard(
                                            device = device,
                                            onClick = { onDeviceConnect(device) }
                                        )
                                    }
                                }
                                item { Space(8.dp) }
                                item { ScanningForDevices(onClick = {
                                    log.d { "Stop scanning requested." }
                                    shouldScan = false
                                }) }
                            }

                            is BluetoothState.Connecting -> item {
                                val device = (knownDevices + scannedDevices)
                                    .find { device: Device -> device.id.toString() == state.deviceId }
                                val formattedName = device?.name?.takeIf { it.isNotEmpty() }
                                    ?: stringResource(
                                        Res.string.device_fallback_name,
                                        stringResource(Res.string.device_default_name),
                                        state.deviceId.takeLast(4).uppercase(),
                                    )
                                // Never invent a version: advertisementData is only populated by the
                                // Android scan seam, so every other target (and any saved device) has
                                // none. Showing a made-up "1.0.0" is worse than saying so.
                                val fwVersion = device?.advertisementData?.readableFwVersion
                                    ?: stringResource(Res.string.firmware_version_unknown)
                                ConnectingToDevice(deviceName = formattedName, fwVersion = fwVersion)
                            }

                            BluetoothState.Disabled -> item {
                                BluetoothDisabled(
                                    systemSupportsBluetoothEnable = deviceScreenViewModel.systemConfig
                                        .supportsBluetoothEnableLinking,
                                    onClick = onRequestEnableBluetooth
                                )
                            }

                            BluetoothState.PermissionDenied -> item {
                                BluetoothPermissionDenied(onClick = onRequestPermission)
                            }

                            BluetoothState.LocationDisabled -> item {
                                LocationDisabled(onClick = onRequestEnableLocation)
                            }
                        }
                    }
                }
            } else {
                LazyColumn {
                    item {
                        Space(16.dp)
                    }

                    when (val state = bluetoothState) {
                        BluetoothState.NeedsPermission -> needsPermission(
                            onDeviceSelected = onDeviceSelected,
                            onDeviceDeleted = onDeviceDeleted,
                            onRequestPermission = onRequestPermission,
                            devices = knownDevices,
                        )

                        BluetoothState.Ready -> ready(
                            onDeviceSelected = onDeviceSelected,
                            onStartScanning = {
                                log.d { "Scanning requested." }
                                shouldScan = true
                            },
                            onDeviceDeleted = onDeviceDeleted,
                            devices = knownDevices,
                            pairsWithDeviceChooser = pairsWithDeviceChooser,
                            scannedDevices = scannedDevices,
                            onDeviceConnect = onDeviceConnect,
                            onPairBoard = onPairBoard,
                        )

                        BluetoothState.Scanning -> scanning(
                            onDeviceSelected = onDeviceSelected,
                            onDeviceConnect = onDeviceConnect,
                            onDeviceDeleted = onDeviceDeleted,
                            onStopScanning = {
                                log.d { "Stop scanning requested." }
                                shouldScan = false
                            },
                            knownDevices = knownDevices,
                            scannedDevices = scannedDevices,
                        )

                        is BluetoothState.Connecting -> connecting(
                            deviceId = state.deviceId,
                            onDeviceSelected = onDeviceSelected,
                            onDeviceDeleted = onDeviceDeleted,
                            knownDevices = knownDevices,
                            scannedDevices = scannedDevices,
                        )

                        BluetoothState.Disabled -> item {
                            BluetoothDisabled(
                                systemSupportsBluetoothEnable = deviceScreenViewModel.systemConfig
                                    .supportsBluetoothEnableLinking,
                                onClick = onRequestEnableBluetooth
                            )
                        }

                        BluetoothState.PermissionDenied -> permissionDenied(
                            onDeviceSelected = onDeviceSelected,
                            onDeviceDeleted = onDeviceDeleted,
                            onOpenSettings = onRequestPermission,
                            devices = knownDevices,
                        )

                        BluetoothState.LocationDisabled -> item {
                            LocationDisabled(onClick = onRequestEnableLocation)
                        }
                    }
                }
            }
        }
    }
}

fun LazyListScope.needsPermission(
    onDeviceSelected: (device: Device) -> Unit,
    onDeviceDeleted: (device: Device) -> Unit,
    onRequestPermission: () -> Unit,
    devices: List<Device>,
) = KnownDeviceList(
    onDeviceSelected = onDeviceSelected,
    onDeviceDeleted = onDeviceDeleted,
    knownDevices = devices,
    additionalItems = {
        item { Space(8.dp) }
        item {
            NeedsPermission(onRequestPermission)
        }
    }
)

fun LazyListScope.permissionDenied(
    onDeviceSelected: (device: Device) -> Unit,
    onDeviceDeleted: (device: Device) -> Unit,
    onOpenSettings: () -> Unit,
    devices: List<Device>,
) = KnownDeviceList(
    onDeviceSelected = onDeviceSelected,
    onDeviceDeleted = onDeviceDeleted,
    knownDevices = devices,
    additionalItems = {
        item { Space(8.dp) }
        item {
            BluetoothPermissionDenied(onOpenSettings)
        }
    }
)

fun LazyListScope.ready(
    onDeviceSelected: (device: Device) -> Unit,
    onDeviceDeleted: (device: Device) -> Unit,
    onStartScanning: () -> Unit,
    devices: List<Device>,
    pairsWithDeviceChooser: Boolean = false,
    scannedDevices: List<Device> = emptyList(),
    onDeviceConnect: (device: Device) -> Unit = {},
    onPairBoard: () -> Unit = {},
) = KnownDeviceList(
    onDeviceSelected = onDeviceSelected,
    onDeviceDeleted = onDeviceDeleted,
    knownDevices = devices,
    additionalItems = {
        item { Space(8.dp) }
        if (pairsWithDeviceChooser) {
            // Prefix the keys: a board in both lists must not repeat a key of the known list.
            discoveredDevices(scannedDevices, onDeviceConnect, keyPrefix = "scanned:")
            item { PairBoard(onClick = onPairBoard) }
        } else {
            item { ScanForDevices(onClick = onStartScanning) }
        }
    }
)

/**
 * The boards that the browser device list returned, with a title. On the web the list stays in
 * the Ready state, because a scan there is one choice and has no Scanning state.
 */
private fun LazyListScope.discoveredDevices(
    scannedDevices: List<Device>,
    onDeviceConnect: (device: Device) -> Unit,
    keyPrefix: String = "",
) {
    if (scannedDevices.isEmpty()) return
    item { DeviceListTitle(stringResource(Res.string.device_screen_list_discovered_devices)) }
    itemsIndexed(
        scannedDevices,
        key = { _: Int, device: Device -> "$keyPrefix${device.id}" }
    ) { index: Int, device: Device ->
        Column {
            if (index > 0) Space(8.dp)
            DiscoveredDeviceCard(
                device = device,
                onClick = { onDeviceConnect(device) }
            )
        }
    }
    item { Space(8.dp) }
}

fun LazyListScope.connecting(
    deviceId: String,
    knownDevices: List<Device>,
    scannedDevices: List<Device>,
    onDeviceSelected: (device: Device) -> Unit,
    onDeviceDeleted: (device: Device) -> Unit,
) = KnownDeviceList(
    onDeviceSelected = onDeviceSelected,
    onDeviceDeleted = onDeviceDeleted,
    knownDevices = knownDevices,
    additionalItems = {
        item { Space(8.dp) }
        item {
            val device = (knownDevices + scannedDevices)
                .find { device: Device -> device.id.toString() == deviceId }
            val formattedName = device?.name?.takeIf { it.isNotEmpty() }
                ?: stringResource(
                    Res.string.device_fallback_name,
                    stringResource(Res.string.device_default_name),
                    deviceId.takeLast(4).uppercase(),
                )
            // See the note on the Connecting branch above: no fabricated version.
            val fwVersion = device?.advertisementData?.readableFwVersion
                ?: stringResource(Res.string.firmware_version_unknown)
            ConnectingToDevice(deviceName = formattedName, fwVersion = fwVersion)
        }
    }
)

fun LazyListScope.scanning(
    knownDevices: List<Device>,
    scannedDevices: List<Device>,
    onDeviceSelected: (device: Device) -> Unit,
    onDeviceConnect: (device: Device) -> Unit,
    onDeviceDeleted: (device: Device) -> Unit,
    onStopScanning: () -> Unit,
) {
    itemsIndexed(
        knownDevices,
        // Prefix the key: in portrait this single LazyColumn renders both the
        // known and scanned lists, and a saved board that is also currently
        // discovered would otherwise collide on device.id (duplicate-key crash).
        key = { _, device: Device -> "known:${device.id}" }
    ) { index: Int, device: Device ->
        KnownDeviceCard(
            first = index == 0,
            device = device,
            onDeviceSelected = onDeviceSelected,
            onDeviceDeleted = onDeviceDeleted,
        )
    }
    if (knownDevices.isNotEmpty() && scannedDevices.isNotEmpty()) {
        item {
            Space(8.dp)
        }
    }
    item {
        if (scannedDevices.isNotEmpty()) {
            DeviceListTitle(stringResource(Res.string.device_screen_list_discovered_devices))
        }
    }
    itemsIndexed(
        scannedDevices,
        key = { _: Int, device: Device -> "scanned:${device.id}" }
    ) { index: Int, device: Device ->
        Column {
            if (index > 0) Space(8.dp)
            DiscoveredDeviceCard(
                device = device,
                onClick = { onDeviceConnect(device) }
            )
        }
    }
    item { Space(8.dp) }
    item { ScanningForDevices(onClick = onStopScanning) }
}

@Composable
private fun DiscoveredDeviceCard(
    device: Device,
    onClick: () -> Unit,
) {
    ClickableCard(
        onClick = onClick,
        modifier = Modifier.testTag("discoveredDevice:${device.name.ifEmpty { device.id.toString() }}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(Res.drawable.bluetooth_icon),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Space(16.dp)

            Column(
                modifier = Modifier.weight(1f)
            ) {
                val displayName = if (device.name.isEmpty()) {
                    stringResource(Res.string.device_default_name)
                } else {
                    device.name
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val adv = device.advertisementData
                if (adv != null) {
                    Text(
                        text = stringResource(
                            Res.string.device_discovered_card_info,
                            adv.boardType,
                            adv.readableFwVersion ?: stringResource(Res.string.firmware_version_unknown)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val shortId = device.id.toString().takeLast(4).uppercase()
                Text(
                    text = stringResource(Res.string.device_tap_to_connect, shortId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                painter = painterResource(Res.drawable.arrow_back),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).rotate(180f)
            )
        }
    }
}

