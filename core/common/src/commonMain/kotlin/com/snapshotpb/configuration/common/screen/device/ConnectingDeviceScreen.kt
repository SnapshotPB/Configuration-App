package com.snapshotpb.configuration.common.screen.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.ConnectionProgress
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.configuration.common.composable.StepRow
import com.snapshotpb.configuration.common.composable.fitsTwoPanes
import com.snapshotpb.configuration.common.navigation.NavigationController
import com.snapshotpb.configuration.common.navigation.ScreenKeyDeviceConfigure
import com.snapshotpb.configuration.common.navigation.ScreenKeyProfiles
import com.snapshotpb.configuration.common.screen.delegate.PendingSync
import com.snapshotpb.configuration.model.newDeviceName
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.connecting_attempt
import com.snapshotpb.core.resources.connecting_button_finish
import com.snapshotpb.core.resources.connecting_explanation
import com.snapshotpb.core.resources.connecting_screen_back_button
import com.snapshotpb.core.resources.connecting_screen_failed_body
import com.snapshotpb.core.resources.connecting_screen_failed_title
import com.snapshotpb.core.resources.connecting_step_connection
import com.snapshotpb.core.resources.connecting_step_device_info
import com.snapshotpb.core.resources.connecting_step_profiles
import com.snapshotpb.core.resources.connecting_step_services
import com.snapshotpb.core.resources.connecting_title_complete
import com.snapshotpb.core.resources.connecting_title_setup
import com.snapshotpb.core.resources.device_default_name
import com.snapshotpb.core.resources.device_fallback_name
import com.snapshotpb.core.resources.firmware_version_unknown
import com.snapshotpb.core.resources.logo_content_description
import com.snapshotpb.core.resources.new_device_screen_cancel_button
import com.snapshotpb.core.resources.snapshot_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

@Composable
fun ConnectingDeviceScreen(
    navigationController: NavigationController,
    deviceId: String,
) {
    // rememberViewModel (not rememberFactory + remember): a hand-constructed ViewModel is never
    // handed to the ViewModelStore, so onCleared() never runs and its viewModelScope leaks on every
    // visit. This also matches how every other screen resolves its ViewModel.
    val viewModel: ConnectingDeviceViewModel by rememberViewModel(arg = deviceId)
    // The board id once the read gives it (see ConnectingDeviceViewModel.boardId), else deviceId.
    val deviceUuid by viewModel.boardId.collectAsState()
    val boardDeviceId = deviceUuid.toString()

    val pendingSyncs by viewModel.pendingSyncs.collectAsState()
    val knownDevice by viewModel.knownDevice.collectAsState(initial = null)
    val bluetoothState by viewModel.bluetoothState.collectAsState()
    val connectionProgress by viewModel.connectionProgress.collectAsState()
    val pending = pendingSyncs[deviceUuid]

    LaunchedEffect(deviceId) { viewModel.startConnect() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (pending) {
            is PendingSync.Conflict -> {
                // Known device with divergence; the global DeviceSyncConflictDialog will surface
                // the prompt. Drop down to the profiles screen so the user has somewhere to land.
                LaunchedEffect(Unit) {
                    navigationController.replaceWith(ScreenKeyProfiles(boardDeviceId))
                }
            }
            else -> {
                val isCompleted = pending is PendingSync.NewDevice || (pending == null && knownDevice != null && connectionProgress.isCompleted)
                val defaultName = stringResource(Res.string.device_default_name)
                // The name that Finish Configuration stores. It must fit the name limit, or the
                // configuration screen shows a cut name and a Save stores it.
                val newName = (pending as? PendingSync.NewDevice)
                    ?.let { newDeviceName(it.device.name, defaultName) }
                val displayName = newName ?: knownDevice?.name ?: stringResource(
                    Res.string.device_fallback_name,
                    defaultName,
                    deviceId.takeLast(4).uppercase(),
                )

                val fwVersion = if (pending is PendingSync.NewDevice) {
                    pending.device.advertisementData?.readableFwVersion
                } else {
                    knownDevice?.advertisementData?.readableFwVersion
                    // No fabricated version — advertisementData is unset on most paths.
                } ?: stringResource(Res.string.firmware_version_unknown)

                ConnectingProgress(
                    deviceName = displayName,
                    fwVersion = fwVersion,
                    bluetoothState = bluetoothState,
                    connectionProgress = connectionProgress,
                    isCompleted = isCompleted,
                    onFinish = {
                        if (newName != null) {
                            viewModel.confirmNewDevice(newName, null)
                        }
                        navigationController.replaceWith(ScreenKeyDeviceConfigure(boardDeviceId))
                    },
                    onBack = { navigationController.goBack() }
                )
            }
        }
    }
}

@Composable
private fun ConnectingProgress(
    deviceName: String,
    fwVersion: String,
    bluetoothState: BluetoothState,
    connectionProgress: ConnectionProgress,
    isCompleted: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    // A radio/permission/location problem that surfaces mid-connect must break the spinner and
    // route the user to the same failure/back affordance as an engine-reported step failure —
    // otherwise the connect flow spins forever with no way out.
    val bluetoothUnavailable = when (bluetoothState) {
        BluetoothState.Disabled,
        BluetoothState.PermissionDenied,
        BluetoothState.LocationDisabled -> true
        else -> false
    }
    val failed = connectionProgress.isFailed || (bluetoothUnavailable && !isCompleted)
    // Shown under the step 2 row only while a retry is in progress. The first attempt shows no
    // number, because most connects need only one attempt.
    val attemptText = if (connectionProgress.isRetrying && !failed) {
        stringResource(Res.string.connecting_attempt, connectionProgress.attempt, connectionProgress.maxAttempts)
    } else {
        null
    }

    // The iOS UI tests read this tag. On iOS a test tag is the accessibility identifier, also in a
    // release build, so err= holds only the stable failure code, never an exception message.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag(
                "connProgress:${connectionProgress.step1}-${connectionProgress.step2}-" +
                    "${connectionProgress.step3}-${connectionProgress.step4}" +
                    "|err=${connectionProgress.failureCode ?: ""}"
            )
    ) {
        val isTwoPane = fitsTwoPanes(maxWidth, maxHeight)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Text(
                    text = if (isCompleted) {
                        stringResource(Res.string.connecting_title_complete)
                    } else if (failed) {
                        stringResource(Res.string.connecting_screen_failed_title)
                    } else {
                        stringResource(Res.string.connecting_title_setup)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Space(8.dp)
                Text(
                    text = stringResource(Res.string.connecting_explanation, deviceName, fwVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isTwoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // Left Side: Logo & Spinner
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(160.dp)
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.snapshot_logo),
                            contentDescription = stringResource(Res.string.logo_content_description),
                            modifier = Modifier
                                .size(90.dp)
                                .background(Color.Transparent)
                        )
                        if (!isCompleted && !failed) {
                            CircularProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 4.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (isCompleted) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    color = Color(0xFF4CAF50),
                                    radius = size.minDimension / 2 - 2.dp.toPx(),
                                    style = Stroke(width = 4.dp.toPx())
                                )
                            }
                        } else {
                            val errorColor = MaterialTheme.colorScheme.error
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    color = errorColor,
                                    radius = size.minDimension / 2 - 2.dp.toPx(),
                                    style = Stroke(width = 4.dp.toPx())
                                )
                            }
                        }
                    }

                    Space(48.dp)

                    // Right Side: Checklist
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(16.dp)
                    ) {
                        StepRow(
                            text = stringResource(Res.string.connecting_step_connection, deviceName),
                            status = connectionProgress.step1
                        )
                        Space(12.dp)
                        StepRow(
                            text = stringResource(Res.string.connecting_step_services),
                            status = connectionProgress.step2,
                            detail = attemptText,
                        )
                        Space(12.dp)
                        StepRow(
                            text = stringResource(Res.string.connecting_step_device_info),
                            status = connectionProgress.step3
                        )
                        Space(12.dp)
                        StepRow(
                            text = stringResource(Res.string.connecting_step_profiles),
                            status = connectionProgress.step4
                        )
                    }
                }
            } else {
                // Mobile layout
                // Center section: Logo and Spinner
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(180.dp)
                ) {
                    Image(
                        painter = painterResource(Res.drawable.snapshot_logo),
                        contentDescription = stringResource(Res.string.logo_content_description),
                        modifier = Modifier
                            .size(110.dp)
                            .background(Color.Transparent)
                    )
                    if (!isCompleted && !failed) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 4.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (isCompleted) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = Color(0xFF4CAF50),
                                radius = size.minDimension / 2 - 2.dp.toPx(),
                                style = Stroke(width = 4.dp.toPx())
                            )
                        }
                    } else {
                        val errorColor = MaterialTheme.colorScheme.error
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = errorColor,
                                radius = size.minDimension / 2 - 2.dp.toPx(),
                                style = Stroke(width = 4.dp.toPx())
                            )
                        }
                    }
                }

                // Checklist section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp)
                ) {
                    StepRow(
                        text = stringResource(Res.string.connecting_step_connection, deviceName),
                        status = connectionProgress.step1
                    )
                    Space(12.dp)
                    StepRow(
                        text = stringResource(Res.string.connecting_step_services),
                        status = connectionProgress.step2,
                        detail = attemptText,
                    )
                    Space(12.dp)
                    StepRow(
                        text = stringResource(Res.string.connecting_step_device_info),
                        status = connectionProgress.step3
                    )
                    Space(12.dp)
                    StepRow(
                        text = stringResource(Res.string.connecting_step_profiles),
                        status = connectionProgress.step4
                    )
                }
            }

            // Bottom action button section
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isCompleted) {
                    Button(
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth().testTag("btFinishConfiguration"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            stringResource(Res.string.connecting_button_finish),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                } else if (failed) {
                    Text(
                        stringResource(Res.string.connecting_screen_failed_body, deviceName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().testTag("connectionFailed"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            stringResource(Res.string.connecting_screen_back_button),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(Res.string.new_device_screen_cancel_button),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
