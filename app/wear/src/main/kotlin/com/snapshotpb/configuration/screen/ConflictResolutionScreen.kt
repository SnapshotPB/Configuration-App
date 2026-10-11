package com.snapshotpb.configuration.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.device_pending_changes_lockout_to_save
import com.snapshotpb.core.resources.sync_conflict_dialog_message
import com.snapshotpb.core.resources.sync_conflict_dialog_take_device
import com.snapshotpb.core.resources.sync_conflict_dialog_take_local
import com.snapshotpb.core.resources.sync_conflict_dialog_title
import org.jetbrains.compose.resources.stringResource

/** The test tag of the chip that keeps the board values. */
const val CONFLICT_TAKE_DEVICE_TAG = "conflict_take_device"

/** The test tag of the chip that writes the watch values to the board. */
const val CONFLICT_TAKE_LOCAL_TAG = "conflict_take_local"

/**
 * The choice between the board values and the values on the watch, when a reconnected board
 * holds values that differ from the stored device. The phone shows the same choice in a dialog
 * and uses the same strings.
 *
 * [onTakeDevice] replaces the stored values with the board values. [onTakeLocal] keeps the stored
 * values and writes them to the board. Back leaves the conflict open, and neither side changes.
 * When [lockedOut] is true, the board does not accept a write, so the screen tells the user that
 * [onTakeLocal] keeps the values as unsaved edits.
 */
@Composable
fun ConflictResolutionScreen(
    deviceName: String,
    lockedOut: Boolean,
    onTakeDevice: () -> Unit,
    onTakeLocal: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            ListHeader {
                Text(
                    stringResource(Res.string.sync_conflict_dialog_title),
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                )
            }
        }

        item {
            Text(
                stringResource(Res.string.sync_conflict_dialog_message, deviceName),
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (lockedOut) {
            item {
                Text(
                    stringResource(Res.string.device_pending_changes_lockout_to_save),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        item {
            Chip(
                onClick = onTakeDevice,
                label = { Text(stringResource(Res.string.sync_conflict_dialog_take_device)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag(CONFLICT_TAKE_DEVICE_TAG),
            )
        }

        item {
            Chip(
                onClick = onTakeLocal,
                label = { Text(stringResource(Res.string.sync_conflict_dialog_take_local)) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag(CONFLICT_TAKE_LOCAL_TAG),
            )
        }
    }
}
