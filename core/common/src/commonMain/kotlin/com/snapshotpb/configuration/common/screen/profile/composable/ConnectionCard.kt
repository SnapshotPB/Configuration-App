package com.snapshotpb.configuration.common.screen.profile.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.screen.profile.ConnectionUiState
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.connecting_step_device_info
import com.snapshotpb.core.resources.connecting_step_profiles
import com.snapshotpb.core.resources.connecting_step_services
import com.snapshotpb.core.resources.connection_card_cancel
import com.snapshotpb.core.resources.connection_card_connect
import com.snapshotpb.core.resources.connection_card_disconnect
import com.snapshotpb.core.resources.connection_card_disconnected_hint
import com.snapshotpb.core.resources.connection_card_failed_hint
import com.snapshotpb.core.resources.connection_card_retry
import com.snapshotpb.core.resources.connection_card_status_connected
import com.snapshotpb.core.resources.connection_card_status_connecting
import com.snapshotpb.core.resources.connection_card_status_disconnected
import com.snapshotpb.core.resources.connection_card_status_failed
import com.snapshotpb.core.resources.connection_card_title
import com.snapshotpb.core.resources.connection_step_connect
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectionCard(
    state: ConnectionUiState,
    progress: ConnectionProgress,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    ActionContainer {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(Res.string.connection_card_title),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            Space(8.dp)
            Text(
                text = stringResource(state.statusLabel()),
                style = MaterialTheme.typography.labelLarge,
                color = state.statusColor(),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            when (state) {
                ConnectionUiState.Disconnected -> {
                    Text(
                        stringResource(Res.string.connection_card_disconnected_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Space(8.dp)
                    Button(
                        modifier = Modifier.align(Alignment.End).testTag("btConnectionConnect"),
                        onClick = onConnect
                    ) {
                        Text(stringResource(Res.string.connection_card_connect))
                    }
                }

                ConnectionUiState.Connecting -> {
                    ConnectionSteps(progress)
                    Space(8.dp)
                    OutlinedButton(modifier = Modifier.align(Alignment.End), onClick = onDisconnect) {
                        Text(stringResource(Res.string.connection_card_cancel))
                    }
                }

                ConnectionUiState.Connected -> {
                    OutlinedButton(
                        modifier = Modifier.align(Alignment.End).testTag("btConnectionDisconnect"),
                        onClick = onDisconnect
                    ) {
                        Text(stringResource(Res.string.connection_card_disconnect))
                    }
                }

                ConnectionUiState.Failed -> {
                    ConnectionSteps(progress)
                    Space(8.dp)
                    Text(
                        stringResource(Res.string.connection_card_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Space(8.dp)
                    Button(
                        modifier = Modifier.align(Alignment.End).testTag("btConnectionRetry"),
                        onClick = onConnect
                    ) {
                        Text(stringResource(Res.string.connection_card_retry))
                    }
                }
            }
            Space(8.dp)
        }
    }
}

@Composable
private fun ConnectionSteps(progress: ConnectionProgress) {
    Column(
        modifier = Modifier.testTag(
            "cardConnProgress:${progress.step1}-${progress.step2}-${progress.step3}-${progress.step4}"
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ConnectionStepRow(stringResource(Res.string.connection_step_connect), progress.step1)
        ConnectionStepRow(stringResource(Res.string.connecting_step_services), progress.step2)
        ConnectionStepRow(stringResource(Res.string.connecting_step_device_info), progress.step3)
        ConnectionStepRow(stringResource(Res.string.connecting_step_profiles), progress.step4)
    }
}

@Composable
private fun ConnectionStepRow(label: String, status: StepStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepIndicator(status)
        Space(8.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status == StepStatus.PENDING) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun StepIndicator(status: StepStatus) {
    Box(
        modifier = Modifier.size(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            StepStatus.IN_PROGRESS -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )

            else -> {
                val color = when (status) {
                    StepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                    StepStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Box(
                    modifier = Modifier
                        .size(if (status == StepStatus.PENDING) 8.dp else 16.dp)
                        .background(color = color, shape = CircleShape)
                )
            }
        }
    }
}

private fun ConnectionUiState.statusLabel() = when (this) {
    ConnectionUiState.Disconnected -> Res.string.connection_card_status_disconnected
    ConnectionUiState.Connecting -> Res.string.connection_card_status_connecting
    ConnectionUiState.Connected -> Res.string.connection_card_status_connected
    ConnectionUiState.Failed -> Res.string.connection_card_status_failed
}

@Composable
private fun ConnectionUiState.statusColor(): Color = when (this) {
    ConnectionUiState.Connected -> MaterialTheme.colorScheme.primary
    ConnectionUiState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
