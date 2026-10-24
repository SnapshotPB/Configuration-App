package com.snapshotpb.configuration.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.snapshotpb.configuration.common.navigation.BrowserHistoryEffect
import com.snapshotpb.configuration.common.navigation.NavigationController
import com.snapshotpb.configuration.common.navigation.ScreenKeyConnectingDevice
import com.snapshotpb.configuration.common.navigation.ScreenKeyDeviceBrowser
import com.snapshotpb.configuration.common.navigation.ScreenKeyDeviceConfigure
import com.snapshotpb.configuration.common.navigation.ScreenKeyFirmwareUpdate
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfile
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfileAdd
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfiles
import com.snapshotpb.configuration.common.navigation.navConfig
import com.snapshotpb.configuration.common.screen.delegate.ConflictChoice
import com.snapshotpb.configuration.common.screen.delegate.DeviceSyncDelegate
import com.snapshotpb.configuration.common.screen.delegate.PendingSync
import com.snapshotpb.configuration.common.screen.device.ConfigureDeviceScreen
import com.snapshotpb.configuration.common.screen.device.ConnectingDeviceScreen
import com.snapshotpb.configuration.common.screen.device.DeviceBrowserScreen
import com.snapshotpb.configuration.common.screen.device.composable.DeviceSyncConflictDialog
import com.snapshotpb.configuration.common.screen.firmware.FirmwareUpdateScreen
import com.snapshotpb.configuration.common.screen.profile.ProfileAddScreen
import com.snapshotpb.configuration.common.screen.profile.ProfileScreen
import com.snapshotpb.configuration.common.screen.profile.ProfilesScreen
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.arrow_back
import com.snapshotpb.core.resources.navigation_back
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

private val log = Arbor.tag("App")

@Composable
@Preview
fun App(
    onExitRequested: () -> Unit = {},
    showFloatingBackButton: Boolean = false,
) {
    val backStack = rememberNavBackStack(
        configuration = navConfig,
        ScreenKeyDeviceBrowser
    )
    val navigationController = remember(backStack) { NavigationController(backStack) }
    BrowserHistoryEffect(backStack)
    val deviceStore: DeviceStore by rememberInstance()
    val deviceSyncDelegate: DeviceSyncDelegate by rememberInstance()
    val knownDevices by deviceStore.knownDevices.collectAsState()
    val pendingSyncs by deviceSyncDelegate.pendingSyncs.collectAsState()
    val syncScope = rememberCoroutineScope()

    // Prime the store
    LaunchedEffect(knownDevices) {
        log.d { "Found ${knownDevices.size} known devices" }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            onBack = {
                if (backStack.size == 1) onExitRequested()
                else backStack.removeLastOrNull()
            },
            entryProvider = entryProvider {
                entry<ScreenKeyDeviceBrowser> {
                    DeviceBrowserScreen(navigationController)
                }
                entry<ScreenKeyProfiles> { screenKeyProfile ->
                    ProfilesScreen(
                        navigationController = navigationController,
                        deviceId = screenKeyProfile.deviceId
                    )
                }
                entry<ScreenKeyProfile> { screenKeyProfile ->
                    ProfileScreen(
                        navigationController = navigationController,
                        deviceId = screenKeyProfile.deviceId,
                        profileId = screenKeyProfile.profileId
                    )
                }
                entry<ScreenKeyProfileAdd> { screenKeyProfile ->
                    ProfileAddScreen(
                        navigationController = navigationController,
                        deviceId = screenKeyProfile.deviceId
                    )
                }
                entry<ScreenKeyDeviceConfigure> { screenKeyDeviceConfigure ->
                    ConfigureDeviceScreen(
                        navigationController = navigationController,
                        deviceId = screenKeyDeviceConfigure.deviceId,
                    )
                }
                entry<ScreenKeyFirmwareUpdate> { screenKeyFirmwareUpdate ->
                    FirmwareUpdateScreen(
                        navigationController = navigationController,
                        deviceId = screenKeyFirmwareUpdate.deviceId,
                    )
                }
                entry<ScreenKeyConnectingDevice> { screenKeyConnecting ->
                    ConnectingDeviceScreen(
                        navigationController = navigationController,
                        deviceId = screenKeyConnecting.deviceId,
                    )
                }
            }
        )

        val firstConflict = pendingSyncs.values.filterIsInstance<PendingSync.Conflict>().firstOrNull()
        if (firstConflict != null) {
            DeviceSyncConflictDialog(
                conflict = firstConflict,
                onTakeDevice = {
                    syncScope.launch {
                        deviceSyncDelegate.resolveConflict(firstConflict.deviceId, ConflictChoice.TakeDevice)
                    }
                },
                onTakeLocal = {
                    syncScope.launch {
                        deviceSyncDelegate.resolveConflict(firstConflict.deviceId, ConflictChoice.TakeLocal)
                    }
                },
            )
        }

        val currentScreen = backStack.lastOrNull()
        if (showFloatingBackButton && currentScreen != null && currentScreen != ScreenKeyDeviceBrowser) {
            FloatingActionButton(
                onClick = { navigationController.goBack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.arrow_back),
                    contentDescription = stringResource(Res.string.navigation_back),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
}
