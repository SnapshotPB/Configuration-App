package com.snapshotpb.configuration.common.screen.device.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.snapshotpb.configuration.common.screen.delegate.PendingSync
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.device_pending_changes_lockout_to_save
import com.snapshotpb.core.resources.sync_conflict_dialog_message
import com.snapshotpb.core.resources.sync_conflict_dialog_take_device
import com.snapshotpb.core.resources.sync_conflict_dialog_take_local
import com.snapshotpb.core.resources.sync_conflict_dialog_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeviceSyncConflictDialog(
    conflict: PendingSync.Conflict,
    onTakeDevice: () -> Unit,
    onTakeLocal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* require an explicit choice */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(Res.string.sync_conflict_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.sync_conflict_dialog_message, conflict.local.name))
                // A board in lockout does not accept a write, so "Keep my values" keeps them as
                // unsaved edits. Tell the user before the choice.
                if (conflict.remote.lockoutEnabled) {
                    Text(
                        text = stringResource(Res.string.device_pending_changes_lockout_to_save),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTakeDevice) {
                    Text(stringResource(Res.string.sync_conflict_dialog_take_device))
                }
                OutlinedButton(onClick = onTakeLocal) {
                    Text(stringResource(Res.string.sync_conflict_dialog_take_local))
                }
            }
        },
    )
}
