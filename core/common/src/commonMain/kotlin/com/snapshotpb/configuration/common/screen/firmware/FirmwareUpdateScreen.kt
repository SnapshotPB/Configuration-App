package com.snapshotpb.configuration.common.screen.firmware

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.common.composable.StepRow
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.navigation.NavigationController
import com.snapshotpb.configuration.firmware.FirmwareStatus
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.button_cancel
import com.snapshotpb.core.resources.firmware_update_cancel_button
import com.snapshotpb.core.resources.firmware_update_cancel_confirm
import com.snapshotpb.core.resources.firmware_update_cancel_keep
import com.snapshotpb.core.resources.firmware_update_cancel_stops_update
import com.snapshotpb.core.resources.firmware_update_cancel_stops_wait
import com.snapshotpb.core.resources.firmware_update_cancel_title
import com.snapshotpb.core.resources.firmware_update_close_button
import com.snapshotpb.core.resources.firmware_update_done_body
import com.snapshotpb.core.resources.firmware_update_done_body_unknown
import com.snapshotpb.core.resources.firmware_update_done_title
import com.snapshotpb.core.resources.firmware_update_failed_detail
import com.snapshotpb.core.resources.firmware_update_failed_title
import com.snapshotpb.core.resources.firmware_update_retry_button
import com.snapshotpb.core.resources.firmware_update_start_button
import com.snapshotpb.core.resources.firmware_update_swap_battery
import com.snapshotpb.core.resources.firmware_update_swap_radio
import com.snapshotpb.core.resources.firmware_update_swap_time
import com.snapshotpb.core.resources.firmware_update_title
import com.snapshotpb.core.resources.firmware_update_versions
import com.snapshotpb.core.resources.firmware_update_warning_battery
import com.snapshotpb.core.resources.firmware_update_warning_leave
import com.snapshotpb.core.resources.firmware_update_warning_no_fire
import com.snapshotpb.core.resources.firmware_update_warning_radio
import com.snapshotpb.core.resources.firmware_update_warning_restart
import com.snapshotpb.core.resources.firmware_update_warning_still
import com.snapshotpb.core.resources.firmware_update_warnings_title
import com.snapshotpb.core.resources.profile_value_percent
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel
import kotlin.math.roundToInt

/**
 * The firmware update screen of the board [deviceId]: the warnings before the start, the steps
 * during the update, and the result.
 *
 * A back navigation during the update does not stop it. The update runs in the coordinator, not in
 * the screen, and the Firmware section of the configuration screen opens this screen again. A stop
 * of the update needs the "Stop Update" button and a confirmation. The screen stays on while the
 * update runs.
 */
@Composable
fun FirmwareUpdateScreen(
    navigationController: NavigationController,
    deviceId: String,
) {
    val viewModel: FirmwareUpdateViewModel by rememberViewModel(arg = deviceId)
    val state by viewModel.state.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState(initial = null)

    FirmwareUpdateContent(
        state = state,
        deviceName = deviceName,
        onStart = viewModel::start,
        onRetry = viewModel::retry,
        onCancel = viewModel::cancel,
        onClose = { if (viewModel.close()) navigationController.goBack() },
    )
}

/** The screen for [state], with no view model, so a test or a preview can show each state. */
@Composable
internal fun FirmwareUpdateContent(
    state: FirmwareUpdateScreenState,
    deviceName: String?,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    var askCancel by rememberSaveable { mutableStateOf(false) }
    val phase = state.phase
    val running = phase as? FirmwareUpdatePhase.Running
    val cancelEffect = running?.cancelEffect

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(if (state.isRunning) Modifier.keepScreenOn() else Modifier),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("firmwareUpdate:${phaseTag(phase)}")) {
            if (fitsTwoPanes(maxWidth, maxHeight)) {
                Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Header(state.status, deviceName)
                        Space(16.dp)
                        PhaseDetails(phase)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 16.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        StepList(phase)
                        Space(16.dp)
                        Actions(phase, onStart, onRetry, onAskCancel = { askCancel = true }, onClose)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Header(state.status, deviceName)
                    Space(16.dp)
                    if (phase is FirmwareUpdatePhase.Confirm) {
                        PhaseDetails(phase)
                        StepList(phase)
                    } else {
                        StepList(phase)
                        Space(16.dp)
                        PhaseDetails(phase)
                    }
                    Space(16.dp)
                    Actions(phase, onStart, onRetry, onAskCancel = { askCancel = true }, onClose)
                }
            }
        }
    }

    // When the step changes to one with no stop while the dialog is open, the dialog closes.
    LaunchedEffect(cancelEffect) {
        if (cancelEffect == null) askCancel = false
    }
    if (askCancel && cancelEffect != null) {
        CancelDialog(
            effect = cancelEffect,
            onConfirm = {
                askCancel = false
                onCancel()
            },
            onDismiss = { askCancel = false },
        )
    }
}

private fun phaseTag(phase: FirmwareUpdatePhase): String = when (phase) {
    is FirmwareUpdatePhase.Confirm -> "confirm"
    is FirmwareUpdatePhase.Running -> "running-${phase.step.name.lowercase()}"
    is FirmwareUpdatePhase.Done -> "done"
    is FirmwareUpdatePhase.Failed -> "failed-${phase.reason.name.lowercase()}"
}

@Composable
private fun Header(status: FirmwareStatus?, deviceName: String?) {
    Text(
        text = stringResource(Res.string.firmware_update_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    if (deviceName != null) {
        Text(
            text = deviceName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val from = status?.boardVersion
    val to = status?.latest?.version
    if (from != null && to != null) {
        Space(4.dp)
        Text(
            text = stringResource(Res.string.firmware_update_versions, from.toString(), to),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The warnings before the start, the swap texts during the update, or the result. */
@Composable
private fun ColumnScope.PhaseDetails(phase: FirmwareUpdatePhase) {
    when (phase) {
        is FirmwareUpdatePhase.Confirm -> {
            ActionContainer(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(Res.string.firmware_update_warnings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Space(8.dp)
                    Warning(stringResource(Res.string.firmware_update_warning_still))
                    Warning(stringResource(Res.string.firmware_update_warning_no_fire))
                    Warning(stringResource(Res.string.firmware_update_warning_battery))
                    Warning(
                        stringResource(
                            Res.string.firmware_update_warning_restart,
                            formatMinutesSeconds(FirmwareUpdateCoordinator.EXPECTED_SWAP_MILLIS),
                        )
                    )
                    Warning(stringResource(Res.string.firmware_update_warning_radio))
                    Warning(stringResource(Res.string.firmware_update_warning_leave))
                }
            }
            phase.reason?.let { reason ->
                Text(
                    text = stringResource(firmwareReasonText(reason)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        is FirmwareUpdatePhase.Running -> {
            if (phase.step >= FirmwareStep.RESET) {
                ActionContainer(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = stringResource(Res.string.firmware_update_swap_battery),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Space(4.dp)
                        Text(
                            text = stringResource(Res.string.firmware_update_swap_radio),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Text(
                text = stringResource(Res.string.firmware_update_warning_leave),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is FirmwareUpdatePhase.Done -> {
            Text(
                text = stringResource(Res.string.firmware_update_done_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Space(4.dp)
            Text(
                text = phase.version?.let { stringResource(Res.string.firmware_update_done_body, it.toString()) }
                    ?: stringResource(Res.string.firmware_update_done_body_unknown),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        is FirmwareUpdatePhase.Failed -> {
            Text(
                text = stringResource(Res.string.firmware_update_failed_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            Space(4.dp)
            Text(
                text = stringResource(firmwareFailureText(phase.reason, phase.detail)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            firmwareFailureDetail(phase.detail)?.let { detail ->
                Space(4.dp)
                Text(
                    text = stringResource(Res.string.firmware_update_failed_detail, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Space(8.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** The steps of §2.7, with a progress bar under a step that has a fraction. */
@Composable
private fun StepList(phase: FirmwareUpdatePhase) {
    if (phase is FirmwareUpdatePhase.Failed && phase.step == null) return
    val running = phase as? FirmwareUpdatePhase.Running
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FirmwareStep.entries.forEach { step ->
            val current = running?.takeIf { it.step == step }
            StepRow(
                text = firmwareStepLabel(step),
                status = firmwareStepStatus(phase, step),
                detail = current?.let { stepDetail(it) },
            )
            val fraction = current?.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                )
            }
        }
    }
}

/** The percent of a download or an upload, or the time of the swap. */
@Composable
private fun stepDetail(running: FirmwareUpdatePhase.Running): String? {
    val fraction = running.fraction
    val elapsed = running.elapsedMillis
    val expected = running.expectedMillis
    return when {
        fraction != null -> stringResource(Res.string.profile_value_percent, (fraction * 100).roundToInt())
        elapsed != null && expected != null -> stringResource(
            Res.string.firmware_update_swap_time,
            formatMinutesSeconds(elapsed),
            formatMinutesSeconds(expected),
        )
        elapsed != null -> formatMinutesSeconds(elapsed)
        else -> null
    }
}

@Composable
private fun ColumnScope.Actions(
    phase: FirmwareUpdatePhase,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onAskCancel: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .align(Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (phase) {
            is FirmwareUpdatePhase.Confirm -> {
                Button(
                    onClick = onStart,
                    enabled = phase.canStart,
                    modifier = Modifier.fillMaxWidth().testTag("firmwareStart"),
                ) {
                    Text(stringResource(Res.string.firmware_update_start_button))
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.button_cancel))
                }
            }

            is FirmwareUpdatePhase.Running -> if (phase.cancelEffect != null) {
                OutlinedButton(
                    onClick = onAskCancel,
                    modifier = Modifier.fillMaxWidth().testTag("firmwareCancel"),
                ) {
                    Text(stringResource(Res.string.firmware_update_cancel_button))
                }
            }

            is FirmwareUpdatePhase.Done -> {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("firmwareClose")) {
                    Text(stringResource(Res.string.firmware_update_close_button))
                }
            }

            is FirmwareUpdatePhase.Failed -> {
                if (phase.canRetry) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("firmwareRetry")) {
                        Text(stringResource(Res.string.firmware_update_retry_button))
                    }
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("firmwareClose")) {
                    Text(stringResource(Res.string.firmware_update_close_button))
                }
            }
        }
    }
}

@Composable
private fun CancelDialog(
    effect: FirmwareCancelEffect,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.firmware_update_cancel_title)) },
        text = {
            Text(
                stringResource(
                    when (effect) {
                        FirmwareCancelEffect.STOPS_UPDATE -> Res.string.firmware_update_cancel_stops_update
                        FirmwareCancelEffect.STOPS_WAIT -> Res.string.firmware_update_cancel_stops_wait
                    }
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(Res.string.firmware_update_cancel_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.firmware_update_cancel_keep))
            }
        },
    )
}
