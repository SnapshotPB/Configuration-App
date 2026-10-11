package com.snapshotpb.configuration.common.composable

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.button_cancel
import com.snapshotpb.core.resources.button_delete
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation for a destructive, non-undoable action.
 *
 * Deleting a device or a profile discards locally-held configuration with no undo, and both were
 * previously wired straight to a single tap on a small icon button — easy to hit by accident on the
 * device list, where the delete icon sits next to the tap target that opens the device.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(Res.string.button_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.button_cancel))
            }
        },
    )
}
