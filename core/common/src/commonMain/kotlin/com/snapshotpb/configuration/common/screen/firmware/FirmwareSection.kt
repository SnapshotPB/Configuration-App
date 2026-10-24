package com.snapshotpb.configuration.common.screen.firmware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.firmware.FirmwareUnavailableReason
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.firmware_section_board_version
import com.snapshotpb.core.resources.firmware_section_check_again_button
import com.snapshotpb.core.resources.firmware_section_details
import com.snapshotpb.core.resources.firmware_section_in_progress
import com.snapshotpb.core.resources.firmware_section_label
import com.snapshotpb.core.resources.firmware_section_latest
import com.snapshotpb.core.resources.firmware_section_release_note
import com.snapshotpb.core.resources.firmware_section_show_update_button
import com.snapshotpb.core.resources.firmware_section_target
import com.snapshotpb.core.resources.firmware_section_update_button
import com.snapshotpb.core.resources.firmware_target_unknown
import com.snapshotpb.core.resources.firmware_update_percent
import com.snapshotpb.core.resources.firmware_version_unknown
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel
import kotlin.math.roundToInt

/**
 * The Firmware section of the configuration screen of the board [deviceId]. [onOpenUpdate] opens
 * the update screen. Only the apps that bind the firmware update show this section: the Wear app
 * does not show the configuration screen.
 */
@Composable
internal fun FirmwareSection(
    deviceId: String,
    onOpenUpdate: () -> Unit,
) {
    val viewModel: FirmwareSectionViewModel by rememberViewModel(arg = deviceId)
    val state by viewModel.state.collectAsState()
    FirmwareSectionContent(
        state = state,
        onOpenUpdate = onOpenUpdate,
        onCheckAgain = viewModel::refresh,
    )
}

@Composable
internal fun FirmwareSectionContent(
    state: FirmwareSectionState,
    onOpenUpdate: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    val status = state.status
    val unknown = stringResource(Res.string.firmware_version_unknown)
    ActionContainer {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp).testTag("firmwareSection")) {
            Text(
                text = stringResource(Res.string.firmware_section_label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.firmware_section_details),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Space(8.dp)

            InfoLine(
                stringResource(
                    Res.string.firmware_section_board_version,
                    status?.boardVersion?.toString() ?: unknown,
                )
            )
            InfoLine(
                stringResource(
                    Res.string.firmware_section_target,
                    status?.target?.toString() ?: stringResource(Res.string.firmware_target_unknown),
                )
            )
            status?.latest?.let { latest ->
                InfoLine(stringResource(Res.string.firmware_section_latest, latest.version))
                latest.warning?.takeIf { it.isNotBlank() }?.let { warning ->
                    Text(
                        text = stringResource(Res.string.firmware_section_release_note, warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            state.activeStep?.let { step ->
                Space(8.dp)
                val stepText = firmwareStepLabel(step)
                val fraction = state.activeFraction
                Text(
                    text = stringResource(
                        Res.string.firmware_section_in_progress,
                        if (fraction == null) {
                            stepText
                        } else {
                            "$stepText " + stringResource(Res.string.firmware_update_percent, (fraction * 100).roundToInt())
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (fraction != null) {
                    Space(4.dp)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }

            state.reason?.let { reason ->
                Text(
                    text = stringResource(firmwareReasonText(reason)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.reason == FirmwareUnavailableReason.CHECK_FAILED) {
                    OutlinedButton(onClick = onCheckAgain) {
                        Text(stringResource(Res.string.firmware_section_check_again_button))
                    }
                }
                Button(
                    onClick = onOpenUpdate,
                    enabled = state.action != FirmwareSectionAction.NONE,
                    modifier = Modifier.testTag("firmwareUpdateButton"),
                ) {
                    Text(
                        stringResource(
                            if (state.action == FirmwareSectionAction.SHOW_UPDATE) {
                                Res.string.firmware_section_show_update_button
                            } else {
                                Res.string.firmware_section_update_button
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
