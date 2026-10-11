package com.snapshotpb.configuration.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.snapshotpb.configuration.common.composable.markerEyeModeLabel
import com.snapshotpb.configuration.common.composable.markerFireModeLabel
import com.snapshotpb.configuration.common.composable.markerProfileFieldLabel
import com.snapshotpb.configuration.common.composable.markerProfileFieldValueLabel
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.device_pending_changes_discard
import com.snapshotpb.core.resources.device_pending_changes_lockout_short
import com.snapshotpb.core.resources.device_pending_changes_save_to_board
import com.snapshotpb.core.resources.navigation_back
import com.snapshotpb.core.resources.profile_badge_unsaved
import com.snapshotpb.core.resources.profile_label_eye_mode
import com.snapshotpb.core.resources.profile_label_fire_mode
import com.snapshotpb.core.resources.profile_marker_max_bps
import com.snapshotpb.core.resources.wear_save_failed
import org.jetbrains.compose.resources.stringResource

/** The test tag prefix of a field chip. The field name follows it. */
const val PROFILE_FIELD_CHIP_TAG_PREFIX = "profile_field_chip_"

/**
 * The editor of one [profile] of [device], the active one on the watch.
 *
 * The list shows the name and the max BPS first, then the fire mode, the eye mode and one chip
 * per visible numeric field. A chip shows an unsaved mark when its value differs from the copy in
 * the board state. The Save chip shows only when the watch is connected and the profile differs,
 * and it is disabled while the board reports lockout or while a write runs.
 * The Discard chip restores this profile alone, so it cannot undo an unsaved board setting.
 */
@Composable
fun ProfileEditScreen(
    device: Device,
    profile: MarkerProfile,
    isConnected: Boolean,
    saveInFlight: Boolean,
    saveFailed: Boolean,
    onFieldSelected: (MarkerProfileField) -> Unit,
    onSelectFireModeTriggered: () -> Unit,
    onSelectEyeModeTriggered: () -> Unit,
    onSaveToBoard: () -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
) {
    val boardProfile = device.boardState?.knownProfiles?.firstOrNull { it.id == profile.id }
    val isDiverged = device.isProfileDiverged(profile.id)
    val visibleFields = MarkerProfileField.entries.filter { it.isShownFor(profile) }

    ScalingLazyColumn(
        // The E2E test finds a chip by its tag as a resource id.
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            ListHeader {
                Text(profile.name, style = MaterialTheme.typography.title3)
            }
        }

        item {
            Text(
                text = stringResource(Res.string.profile_marker_max_bps, profile.maxBpsForTiming()),
                style = MaterialTheme.typography.caption2,
            )
        }

        item {
            ProfileValueChip(
                label = stringResource(Res.string.profile_label_fire_mode),
                value = markerFireModeLabel(profile.fireMode),
                isUnsaved = boardProfile != null && boardProfile.fireMode != profile.fireMode,
                onClick = onSelectFireModeTriggered,
            )
        }

        item {
            ProfileValueChip(
                label = stringResource(Res.string.profile_label_eye_mode),
                value = markerEyeModeLabel(profile.eyeMode),
                isUnsaved = boardProfile != null && boardProfile.eyeMode != profile.eyeMode,
                onClick = onSelectEyeModeTriggered,
            )
        }

        items(visibleFields, key = { it.name }) { field ->
            val value = field.get(profile)
            ProfileValueChip(
                label = markerProfileFieldLabel(field),
                value = markerProfileFieldValueLabel(field, value),
                isUnsaved = boardProfile != null && field.get(boardProfile) != value,
                onClick = { onFieldSelected(field) },
                modifier = Modifier.testTag(PROFILE_FIELD_CHIP_TAG_PREFIX + field.name),
            )
        }

        if (isConnected && isDiverged) {
            item {
                // A board in lockout does not accept a change, so the chip is disabled and tells
                // the user why. The chip is also disabled while a write runs, and a failed save
                // shows until the next save or edit.
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
                        else -> null
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        if (isDiverged) {
            item {
                Chip(
                    onClick = onDiscard,
                    label = { Text(stringResource(Res.string.device_pending_changes_discard)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) {
                Text(stringResource(Res.string.navigation_back))
            }
        }
    }
}

/**
 * A chip that shows the [label] of a profile setting over its [value]. An unsaved mark follows
 * the value when the setting differs from the board copy.
 */
@Composable
private fun ProfileValueChip(
    label: String,
    value: String,
    isUnsaved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Chip(
        onClick = onClick,
        label = { Text(label) },
        secondaryLabel = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.caption3)
                if (isUnsaved) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(Res.string.profile_badge_unsaved),
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.primary,
                    )
                }
            }
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
