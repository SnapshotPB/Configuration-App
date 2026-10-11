@file:Suppress("DEPRECATION")
package com.snapshotpb.configuration.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import coil3.compose.AsyncImage
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.DiscoveredDevice
import com.snapshotpb.configuration.bluetooth.toDeviceUuid
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.app_name
import com.snapshotpb.core.resources.wear_action_enable_bluetooth
import com.snapshotpb.core.resources.wear_action_grant_permissions
import com.snapshotpb.core.resources.wear_action_scan_for_boards
import com.snapshotpb.core.resources.wear_action_stop_scanning
import com.snapshotpb.core.resources.wear_action_turn_on_location
import com.snapshotpb.core.resources.wear_device_photo_description
import com.snapshotpb.core.resources.wear_discovered_board_secondary
import com.snapshotpb.core.resources.wear_no_profile
import com.snapshotpb.core.resources.wear_no_synced_devices
import com.snapshotpb.core.resources.wear_profile_label
import com.snapshotpb.core.resources.wear_searching
import com.snapshotpb.core.resources.wear_section_discovered_boards
import com.snapshotpb.core.resources.wear_section_synced_devices
import org.jetbrains.compose.resources.stringResource

/** The test tag of the header of the device list. */
const val DEVICE_LIST_HEADER_TAG = "device_list_header"

/** The test tag of the chip that starts or stops a scan, or asks for what a scan needs. */
const val DEVICE_LIST_SCAN_TAG = "device_list_scan"

/** The prefix of the test tag of a discovered board chip. The board id follows it. */
const val DISCOVERED_DEVICE_TAG_PREFIX = "discovered_device_"

@Composable
fun DeviceListScreen(
    knownDevices: List<Device>,
    /** Gives the URI that the image loader opens for a stored photo path. See DeviceStore.photoUri. */
    photoUri: (String) -> String,
    bluetoothState: BluetoothState,
    discoveredDevices: List<DiscoveredDevice>,
    onDeviceSelected: (Device) -> Unit,
    onDirectConnectSelected: (DiscoveredDevice) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onGrantPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onEnableLocation: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    LaunchedEffect(bluetoothState) {
        if (bluetoothState is BluetoothState.Scanning) {
            val targetIndex = 4 + knownDevices.size
            listState.animateScrollToItem(targetIndex)
        }
    }

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Text(
                    stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.title2,
                    modifier = Modifier.testTag(DEVICE_LIST_HEADER_TAG),
                )
            }
        }

        // Section: Synced Devices
        if (knownDevices.isNotEmpty()) {
            item {
                Text(
                    stringResource(Res.string.wear_section_synced_devices),
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(knownDevices) { device ->
                TitleCard(
                    onClick = { onDeviceSelected(device) },
                    title = { Text(device.name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (device.photoPath.isNotEmpty()) {
                            AsyncImage(
                                model = photoUri(device.photoPath),
                                contentDescription = stringResource(Res.string.wear_device_photo_description),
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(end = 8.dp)
                            )
                        }
                        Column {
                            val activeProfileName = device.knownProfiles
                                .firstOrNull { it.id == device.activeProfileId }?.name
                                ?: stringResource(Res.string.wear_no_profile)
                            Text(
                                stringResource(Res.string.wear_profile_label, activeProfileName),
                                style = MaterialTheme.typography.caption2
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    stringResource(Res.string.wear_no_synced_devices),
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Section: Direct Board Scan/Connect
        item {
            val isScanning = bluetoothState is BluetoothState.Scanning
            val needsPermission = bluetoothState is BluetoothState.NeedsPermission
            val isDisabled = bluetoothState is BluetoothState.Disabled
            val isLocationDisabled = bluetoothState is BluetoothState.LocationDisabled
            Chip(
                onClick = {
                    when {
                        needsPermission -> onGrantPermission()
                        isDisabled -> onEnableBluetooth()
                        isLocationDisabled -> onEnableLocation()
                        isScanning -> onStopScan()
                        else -> onStartScan()
                    }
                },
                label = {
                    Text(
                        when {
                            needsPermission -> stringResource(Res.string.wear_action_grant_permissions)
                            isDisabled -> stringResource(Res.string.wear_action_enable_bluetooth)
                            isLocationDisabled -> stringResource(Res.string.wear_action_turn_on_location)
                            isScanning -> stringResource(Res.string.wear_action_stop_scanning)
                            else -> stringResource(Res.string.wear_action_scan_for_boards)
                        }
                    )
                },
                icon = {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag(DEVICE_LIST_SCAN_TAG)
            )
        }

        if (bluetoothState is BluetoothState.Scanning) {
            item {
                Text(
                    stringResource(Res.string.wear_section_discovered_boards),
                    style = MaterialTheme.typography.caption1,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            if (discoveredDevices.isEmpty()) {
                item {
                    Text(
                        stringResource(Res.string.wear_searching),
                        style = MaterialTheme.typography.caption2,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(discoveredDevices) { device ->
                    val shortId = device.id.toDeviceUuid().toString().takeLast(4).uppercase()
                    val boardTypeLabel = device.boardType ?: stringResource(Res.string.app_name)
                    Chip(
                        onClick = { onDirectConnectSelected(device) },
                        label = { Text(device.name) },
                        secondaryLabel = {
                            Text(stringResource(Res.string.wear_discovered_board_secondary, boardTypeLabel, shortId))
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .testTag(DISCOVERED_DEVICE_TAG_PREFIX + device.id)
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}
