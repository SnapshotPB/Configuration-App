package com.snapshotpb.configuration.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Confirmation
import androidx.wear.compose.material.dialog.Dialog
import androidx.wear.compose.material.dialog.DialogDefaults
import com.snapshotpb.configuration.common.screen.delegate.SaveResult
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.connection_card_failed_hint
import com.snapshotpb.core.resources.wear_save_failed
import com.snapshotpb.core.resources.wear_save_succeeded
import org.jetbrains.compose.resources.stringResource

/**
 * A short message after a "save to board" write. The message closes itself: a success after a
 * short time, and a failure after a longer time, because it also tells the user what to do. The
 * Save chip keeps the failure until the next save or edit, so the user can still see it after the
 * message closes.
 */
@Composable
fun SaveResultDialog(result: SaveResult?, onDismiss: () -> Unit) {
    // The dialog keeps its content on screen while it closes, and the result is null then. The
    // last result keeps the text on screen until the dialog is gone.
    var lastResult by remember { mutableStateOf(result) }
    if (result != null) lastResult = result
    // The dialog and its content share one scroll state, so the position indicator follows the
    // content.
    val scrollState = rememberScalingLazyListState()

    Dialog(
        showDialog = result != null,
        onDismissRequest = onDismiss,
        scrollState = scrollState,
    ) {
        val shown = lastResult ?: return@Dialog
        Confirmation(
            onTimeout = onDismiss,
            scrollState = scrollState,
            durationMillis = if (shown.succeeded) {
                DialogDefaults.ShortDurationMillis
            } else {
                DialogDefaults.LongDurationMillis
            },
        ) {
            Text(
                text = stringResource(
                    if (shown.succeeded) Res.string.wear_save_succeeded else Res.string.wear_save_failed
                ),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
            if (!shown.succeeded) {
                Text(
                    text = stringResource(Res.string.connection_card_failed_hint),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
