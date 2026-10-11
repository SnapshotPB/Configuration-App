@file:Suppress("DEPRECATION")
package com.snapshotpb.configuration.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.model.DiscoveredDevice
import com.snapshotpb.configuration.bluetooth.toDeviceUuid
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.connecting_attempt
import com.snapshotpb.core.resources.device_default_name
import com.snapshotpb.core.resources.new_device_screen_cancel_button
import com.snapshotpb.core.resources.wear_action_back_to_list
import com.snapshotpb.core.resources.wear_action_continue
import com.snapshotpb.core.resources.wear_all_data_loaded
import com.snapshotpb.core.resources.wear_board_with_id
import com.snapshotpb.core.resources.wear_connected_title
import com.snapshotpb.core.resources.wear_connecting_title
import com.snapshotpb.core.resources.wear_connection_failed_title
import com.snapshotpb.core.resources.wear_step_connection
import com.snapshotpb.core.resources.wear_step_device_info
import com.snapshotpb.core.resources.wear_step_profiles
import com.snapshotpb.core.resources.wear_step_services
import com.snapshotpb.core.resources.wear_unable_to_communicate
import org.jetbrains.compose.resources.stringResource

/** The test tag of the Continue chip that shows when the board is read. */
const val CONNECTING_CONTINUE_TAG = "connecting_continue"

/**
 * The four-step connection progress, with a Continue chip once the board is read.
 *
 * [syncReady] tells whether the app has handled the read result of this board. The engine marks
 * step 4 complete a moment before it emits the read result, so a tap on Continue in that gap
 * would find no device to show. The chip waits for both.
 */
@Composable
fun ConnectingScreen(
    device: DiscoveredDevice,
    connectionProgress: ConnectionProgress,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    syncReady: Boolean = true,
) {
    val failed = connectionProgress.isFailed
    val completed = connectionProgress.isCompleted && syncReady
    val listState = rememberScalingLazyListState()
    // Shown under the step 2 row only while a retry is in progress. The first attempt shows no
    // number, because most connects need only one attempt.
    val attemptText = if (connectionProgress.isRetrying) {
        stringResource(Res.string.connecting_attempt, connectionProgress.attempt, connectionProgress.maxAttempts)
    } else {
        null
    }

    // Keyed on the gated flags, so the scroll to the Continue chip runs when the chip is there.
    val targetIndex = remember(connectionProgress, syncReady) {
        when {
            completed -> 5
            failed -> 5
            connectionProgress.step4 == StepStatus.IN_PROGRESS -> 6
            connectionProgress.step3 == StepStatus.IN_PROGRESS -> 5
            connectionProgress.step2 == StepStatus.IN_PROGRESS -> 4
            connectionProgress.step1 == StepStatus.IN_PROGRESS -> 3
            else -> 0
        }
    }

    LaunchedEffect(targetIndex) {
        if (targetIndex > 0) {
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
                    text = when {
                        completed -> stringResource(Res.string.wear_connected_title)
                        failed -> stringResource(Res.string.wear_connection_failed_title)
                        else -> stringResource(Res.string.wear_connecting_title)
                    },
                    style = MaterialTheme.typography.title3,
                    color = when {
                        completed -> Color(0xFF4CAF50)
                        failed -> MaterialTheme.colors.error
                        else -> MaterialTheme.colors.onBackground
                    }
                )
            }
        }

        item {
            val shortId = device.id.toDeviceUuid().toString().takeLast(4).uppercase()
            Text(
                text = stringResource(
                    Res.string.wear_board_with_id,
                    device.boardType ?: stringResource(Res.string.device_default_name),
                    shortId,
                ),
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        if (!completed && !failed) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                StepRow(text = stringResource(Res.string.wear_step_connection), status = connectionProgress.step1)
            }
            item {
                // The detail line stays in the step 2 item, so the item indexes of the scroll target do not change.
                StepRow(
                    text = stringResource(Res.string.wear_step_services),
                    status = connectionProgress.step2,
                    detail = attemptText,
                )
            }
            item {
                StepRow(text = stringResource(Res.string.wear_step_device_info), status = connectionProgress.step3)
            }
            item {
                StepRow(text = stringResource(Res.string.wear_step_profiles), status = connectionProgress.step4)
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                CompactChip(
                    onClick = onCancel,
                    label = { Text(stringResource(Res.string.new_device_screen_cancel_button)) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        } else if (completed) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
            item {
                Text(
                    stringResource(Res.string.wear_all_data_loaded),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
            item {
                Chip(
                    onClick = onContinue,
                    label = { Text(stringResource(Res.string.wear_action_continue)) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag(CONNECTING_CONTINUE_TAG)
                )
            }
        } else { // failed
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
            item {
                Text(
                    stringResource(Res.string.wear_unable_to_communicate),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
            item {
                Chip(
                    onClick = onCancel,
                    label = { Text(stringResource(Res.string.wear_action_back_to_list)) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun StepRow(
    text: String,
    status: StepStatus,
    detail: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        StepStatusRow(text = text, status = status)
        if (detail != null) {
            // The start padding (row padding 24 dp + icon column 20 dp) aligns it with the step text.
            Text(
                text = detail,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 44.dp, end = 24.dp),
            )
        }
    }
}

@Composable
private fun StepStatusRow(
    text: String,
    status: StepStatus,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        when (status) {
            StepStatus.PENDING -> {
                Text("○", color = Color.Gray, modifier = Modifier.width(20.dp))
            }
            StepStatus.IN_PROGRESS -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            StepStatus.COMPLETED -> {
                Text("✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            }
            StepStatus.FAILED -> {
                Text("✗", color = MaterialTheme.colors.error, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.caption2,
            color = when (status) {
                StepStatus.COMPLETED -> Color(0xFF4CAF50)
                StepStatus.FAILED -> MaterialTheme.colors.error
                StepStatus.IN_PROGRESS -> MaterialTheme.colors.onBackground
                StepStatus.PENDING -> Color.Gray
            }
        )
    }
}
