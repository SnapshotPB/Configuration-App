package com.snapshotpb.configuration.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import coil3.compose.AsyncImage
import com.snapshotpb.configuration.common.composable.boardLanguageLabel
import com.snapshotpb.configuration.common.composable.boardScreenBrightnessLabel
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.configure_device_screen_active_profile_label
import com.snapshotpb.core.resources.board_screen_off_delay_seconds
import com.snapshotpb.core.resources.configure_device_screen_brightness_label
import com.snapshotpb.core.resources.configure_device_screen_language_label
import com.snapshotpb.core.resources.configure_device_screen_lockout_label
import com.snapshotpb.core.resources.configure_device_screen_lockout_locked
import com.snapshotpb.core.resources.configure_device_screen_lockout_unlocked
import com.snapshotpb.core.resources.configure_device_screen_off_delay_label
import com.snapshotpb.core.resources.device_pending_changes_discard
import com.snapshotpb.core.resources.device_pending_changes_lockout_short
import com.snapshotpb.core.resources.device_pending_changes_save_to_board
import com.snapshotpb.core.resources.navigation_back
import com.snapshotpb.core.resources.profile_badge_unsaved
import com.snapshotpb.core.resources.profile_tune_title
import com.snapshotpb.core.resources.wear_ble_disconnected
import com.snapshotpb.core.resources.wear_connected_via_ble
import com.snapshotpb.core.resources.wear_device_photo_description
import com.snapshotpb.core.resources.wear_no_profile
import com.snapshotpb.core.resources.wear_save_failed
import com.snapshotpb.core.resources.wear_tap_to_connect_directly
import com.snapshotpb.core.resources.wear_tap_to_disconnect
import org.jetbrains.compose.resources.stringResource

/** The test tag of the Active Profile card. */
const val DEVICE_CONFIG_ACTIVE_PROFILE_TAG = "device_config_active_profile"

/** The test tag of the Tune Profile card. */
const val DEVICE_CONFIG_TUNE_PROFILE_TAG = "device_config_tune_profile"

@Composable
fun DeviceConfigScreen(
    device: Device,
    isConnected: Boolean,
    saveInFlight: Boolean,
    saveFailed: Boolean,
    /** Gives the URI that the image loader opens for a stored photo path. See DeviceStore.photoUri. */
    photoUri: (String) -> String,
    onSelectProfileTriggered: () -> Unit,
    onTuneProfileTriggered: () -> Unit,
    onSelectLanguageTriggered: () -> Unit,
    onSelectBrightnessTriggered: () -> Unit,
    onSelectScreenOffDelayTriggered: () -> Unit,
    onSaveToBoard: () -> Unit,
    onDiscard: () -> Unit,
    onDisconnect: () -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    val activeProfile = device.knownProfiles.firstOrNull { it.id == device.activeProfileId }
    val activeProfileName = activeProfile?.name ?: stringResource(Res.string.wear_no_profile)
    val hasDiverged = device.hasDiverged()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (device.photoPath.isNotEmpty()) {
                        AsyncImage(
                            model = photoUri(device.photoPath),
                            contentDescription = stringResource(Res.string.wear_device_photo_description),
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    Text(device.name, style = MaterialTheme.typography.title3)
                }
            }
        }

        // Active Profile Selection Card
        item {
            TitleCard(
                onClick = onSelectProfileTriggered,
                title = { Text(stringResource(Res.string.configure_device_screen_active_profile_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag(DEVICE_CONFIG_ACTIVE_PROFILE_TAG)
            ) {
                Text(activeProfileName, style = MaterialTheme.typography.body1)
            }
        }

        // Tune Profile card. The watch edits the active profile only, so the card needs one.
        if (activeProfile != null) {
            item {
                TitleCard(
                    onClick = onTuneProfileTriggered,
                    title = { Text(stringResource(Res.string.profile_tune_title)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag(DEVICE_CONFIG_TUNE_PROFILE_TAG)
                ) {
                    Text(activeProfile.name, style = MaterialTheme.typography.body1)
                }
            }
        }

        // Board Language Card. Only a board that reported a language shows it: firmware from
        // before the field reports none and ignores a choice.
        device.language?.let { language ->
            item {
                TitleCard(
                    onClick = onSelectLanguageTriggered,
                    title = { Text(stringResource(Res.string.configure_device_screen_language_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(boardLanguageLabel(language), style = MaterialTheme.typography.body1)
                }
            }
        }

        // Screen settings. Each card shows only when the board reported a value for it:
        // firmware from before these fields reports none and ignores a choice.
        device.screenBrightness?.let { brightness ->
            item {
                TitleCard(
                    onClick = onSelectBrightnessTriggered,
                    title = { Text(stringResource(Res.string.configure_device_screen_brightness_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(boardScreenBrightnessLabel(brightness), style = MaterialTheme.typography.body1)
                }
            }
        }

        device.screenOffDelay?.let { delay ->
            item {
                TitleCard(
                    onClick = onSelectScreenOffDelayTriggered,
                    title = { Text(stringResource(Res.string.configure_device_screen_off_delay_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        stringResource(Res.string.board_screen_off_delay_seconds, delay.toString()),
                        style = MaterialTheme.typography.body1,
                    )
                }
            }
        }

        // Lockout card. The lockout is a board-owned fact with no writable field, so the card
        // only shows it.
        item {
            TitleCard(
                onClick = {},
                enabled = false,
                title = { Text(stringResource(Res.string.configure_device_screen_lockout_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    if (device.lockoutEnabled) {
                        stringResource(Res.string.configure_device_screen_lockout_locked)
                    } else {
                        stringResource(Res.string.configure_device_screen_lockout_unlocked)
                    },
                    style = MaterialTheme.typography.body1,
                )
            }
        }

        // Connection status and action card
        item {
            TitleCard(
                onClick = { if (isConnected) onDisconnect() else onConnect() },
                title = {
                    Text(
                        if (isConnected) stringResource(Res.string.wear_connected_via_ble)
                        else stringResource(Res.string.wear_ble_disconnected)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    if (isConnected) stringResource(Res.string.wear_tap_to_disconnect)
                    else stringResource(Res.string.wear_tap_to_connect_directly),
                    style = MaterialTheme.typography.caption2
                )
            }
        }

        // Write configuration changes to BLE board. The unsaved mark shows when any local value
        // differs from the board copy. A board in lockout does not accept a change, so the chip
        // is disabled and tells the user why. A failed save shows until the next save or edit, and
        // the chip is disabled while a write runs, so a second tap sends no second write.
        if (isConnected) {
            item {
                Chip(
                    onClick = onSaveToBoard,
                    enabled = !device.lockoutEnabled && !saveInFlight,
                    label = { Text(stringResource(Res.string.device_pending_changes_save_to_board)) },
                    secondaryLabel = when {
                        device.lockoutEnabled -> {
                            { Text(stringResource(Res.string.device_pending_changes_lockout_short)) }
                        }
                        saveFailed -> {
                            { Text(stringResource(Res.string.wear_save_failed)) }
                        }
                        hasDiverged -> {
                            { Text(stringResource(Res.string.profile_badge_unsaved)) }
                        }
                        else -> null
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }

        // Restore every local value from the board copy.
        if (hasDiverged) {
            item {
                Chip(
                    onClick = onDiscard,
                    label = { Text(stringResource(Res.string.device_pending_changes_discard)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Back button
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
            ) {
                Text(stringResource(Res.string.navigation_back))
            }
        }
    }
}
