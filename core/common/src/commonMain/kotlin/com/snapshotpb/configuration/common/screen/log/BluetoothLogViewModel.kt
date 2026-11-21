package com.snapshotpb.configuration.common.screen.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapshotpb.configuration.bluetooth.log.BluetoothLog
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogSnapshot
import com.snapshotpb.configuration.common.share.ShareRequest
import com.snapshotpb.configuration.common.share.ShareRequestManager
import com.snapshotpb.configuration.version.AppVersion
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private val log = Arbor.tag("BluetoothLogViewModel")

/** The view model of the Bluetooth log screen. */
class BluetoothLogViewModel(
    private val bluetoothLog: BluetoothLog,
    private val shareRequestManager: ShareRequestManager,
    private val clock: Clock = Clock.System,
    private val appVersion: String = AppVersion.NAME,
    private val formatDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** The log, as the store publishes it. */
    val state: StateFlow<BluetoothLogSnapshot> = bluetoothLog.snapshot

    /**
     * True when an app share handler collects [ShareRequestManager.bus].
     *
     * [ShareRequestManager.send] drops a request when no handler collects the bus, for example
     * in the Wear app. The screen shows no Export button when this value is false.
     */
    val canExport: StateFlow<Boolean> = shareRequestManager.subscriptionCount
        .map { it > 0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = shareRequestManager.subscriptionCount.value > 0,
        )

    private var exportJob: Job? = null

    /** Clears the Bluetooth log. */
    fun clear() {
        bluetoothLog.clear()
    }

    /** Builds the export text and sends a [ShareRequest.Text] with [title]. Ignores a call while an export runs. */
    fun export(title: String) {
        if (exportJob?.isActive == true) {
            return
        }
        exportJob = viewModelScope.launch {
            val snapshot = state.value
            val now = clock.now()
            val text = withContext(formatDispatcher) {
                BluetoothLogExport.format(snapshot, appVersion, now)
            }
            log.d { "Export Bluetooth log with ${snapshot.entries.size} entries" }
            shareRequestManager.send(
                ShareRequest.Text(
                    fileName = BluetoothLogExport.fileName(now),
                    title = title,
                    text = text,
                )
            )
        }
    }
}
