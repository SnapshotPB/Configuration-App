package com.snapshotpb.configuration

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.common.App
import com.snapshotpb.configuration.di.platformModule
import com.snapshotpb.configuration.screen.screenModule
import io.github.kdroidfilter.nucleus.updater.NucleusUpdater
import io.github.kdroidfilter.nucleus.updater.UpdateResult
import io.github.kdroidfilter.nucleus.updater.UpdateInfo
import io.github.kdroidfilter.nucleus.updater.DownloadProgress
import io.github.kdroidfilter.nucleus.updater.provider.GitHubProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_value_percent
import com.snapshotpb.core.resources.updater_action_close
import com.snapshotpb.core.resources.updater_action_later
import com.snapshotpb.core.resources.updater_action_update_now
import com.snapshotpb.core.resources.updater_dialog_title
import com.snapshotpb.core.resources.updater_downloading
import com.snapshotpb.core.resources.updater_error
import com.snapshotpb.core.resources.updater_error_unknown
import com.snapshotpb.core.resources.updater_message
import com.snapshotpb.core.resources.updater_starting_download
import com.toxicbakery.logging.Arbor
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DI
import org.kodein.di.compose.withDI
import org.kodein.di.instance

/**
 * The desktop app.
 *
 * @param firmwareSiteUrl The URL of a local firmware site for a bench test, or `null` for the public
 *   site (see [firmwareSiteUrlOf]).
 */
class Application(private val firmwareSiteUrl: String? = null) : BaseApplication() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val isUpdateAvailable = MutableStateFlow<UpdateInfo?>(null)

    override val di: DI by DI.lazy {
        import(appModule)
        import(platformModule)
        import(screenModule)
        import(firmwareUpdateModule(firmwareSiteUrl))
    }

    private val bluetoothManager: BluetoothManager by instance()

    override suspend fun onBluetoothRequest(request: BluetoothRequest) {
        when (request) {
            BluetoothRequest.EnableBluetooth -> {
                log.d("Enable Bluetooth requested on desktop. This must be handled by the user in OS settings.")
            }
            BluetoothRequest.EnableLocation -> {
                log.d("Enable Location requested on desktop. Not applicable; desktop BLE scanning does not depend on the system Location toggle.")
            }
            BluetoothRequest.RequestPermission -> {
                log.d("Request Permission requested on desktop. No runtime permission dialog is needed on JVM desktop platforms.")
            }
            BluetoothRequest.StartScan -> bluetoothManager.startScanning()
            BluetoothRequest.StopScan -> bluetoothManager.stopScanning()
            is BluetoothRequest.Connect -> bluetoothManager.connect(request.deviceId)
            is BluetoothRequest.Disconnect -> bluetoothManager.disconnect(request.deviceId)
            is BluetoothRequest.WriteDevice ->
                bluetoothManager.writeDeviceConfiguration(request.deviceId, request.device, request.requestId)
            is BluetoothRequest.UpdateFirmware ->
                bluetoothManager.updateFirmware(request.deviceId, request.image, request.requestId)
            is BluetoothRequest.ConfirmFirmware ->
                bluetoothManager.confirmFirmware(request.deviceId, request.requestId)
        }
    }

    private fun checkForUpdates() {
        scope.launch {
            try {
                val updater = NucleusUpdater {
                    provider = GitHubProvider(owner = "SnapshotPB", repo = "Configuration-App")
                }
                when (val result = updater.checkForUpdates()) {
                    is UpdateResult.Available -> {
                        isUpdateAvailable.value = result.info
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                log.w(e) { "Update check failed" }
            }
        }
    }

    override fun createWindow() {
        if (DesktopConfig.ENABLE_AUTO_UPDATER) {
            checkForUpdates()
        }
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Snapshot PB",
            ) {
                withDI(di) {
                    AppTheme {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            App(
                                onExitRequested = { exitApplication() },
                                showFloatingBackButton = true,
                            )

                            val updateInfo = isUpdateAvailable.collectAsState().value
                            if (updateInfo != null) {
                                UpdatePromptDialog(
                                    updateInfo = updateInfo,
                                    onDismiss = { isUpdateAvailable.value = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val log = Arbor.tag("Application")
    }
}

@Composable
fun UpdatePromptDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    // Resolved here rather than inside the download lambda: stringResource is @Composable.
    val unknownError = stringResource(Res.string.updater_error_unknown)

    val updater = remember {
        NucleusUpdater {
            provider = GitHubProvider(owner = "SnapshotPB", repo = "Configuration-App")
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        title = { Text(text = stringResource(Res.string.updater_dialog_title)) },
        text = {
            Column {
                // Bound to a local: `errorMessage` is a delegated property, so it cannot smart-cast
                // to non-null for the vararg `stringResource` argument.
                val error = errorMessage
                if (error != null) {
                    Text(
                        text = stringResource(Res.string.updater_error, error),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (isDownloading) {
                    val progress = downloadProgress
                    if (progress != null) {
                        // Compose Resources does not change `%%` to one sign, so the percent
                        // text comes from the shared percent format of each locale.
                        val percent = stringResource(Res.string.profile_value_percent, (progress.percent * 100).toInt())
                        Text(text = stringResource(Res.string.updater_downloading, percent))
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress.percent.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(text = stringResource(Res.string.updater_starting_download))
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Text(text = stringResource(Res.string.updater_message, updateInfo.version))
                }
            }
        },
        confirmButton = {
            if (!isDownloading && errorMessage == null) {
                Button(
                    onClick = {
                        isDownloading = true
                        coroutineScope.launch {
                            try {
                                updater.downloadUpdate(updateInfo).collect { progress ->
                                    downloadProgress = progress
                                }
                                val finalProgress = downloadProgress
                                val finalFile = finalProgress?.file
                                if (finalFile != null && finalFile.exists()) {
                                    updater.installAndRestart(finalFile)
                                } else {
                                    throw IllegalStateException("Download completed but file is missing")
                                }
                            } catch (e: CancellationException) {
                                // The dialog left the composition; its state is gone with it.
                                throw e
                            } catch (e: Exception) {
                                isDownloading = false
                                errorMessage = e.message ?: unknownError
                            }
                        }
                    }
                ) {
                    Text(stringResource(Res.string.updater_action_update_now))
                }
            } else if (errorMessage != null) {
                Button(onClick = onDismiss) {
                    Text(stringResource(Res.string.updater_action_close))
                }
            }
        },
        dismissButton = {
            if (!isDownloading && errorMessage == null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.updater_action_later))
                }
            }
        }
    )
}

