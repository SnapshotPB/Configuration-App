package com.snapshotpb.configuration

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.common.share.ShareRequest
import com.snapshotpb.configuration.common.share.ShareRequestManager
import com.snapshotpb.configuration.media.MediaState
import com.snapshotpb.configuration.media.MediaStateManager
import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.Seedling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kodein.di.DIAware
import org.kodein.di.instance

abstract class BaseApplication : DIAware {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val bluetoothRequestManager: BluetoothRequestManager by instance()
    private val mediaStateManager: MediaStateManager by instance()
    private val shareRequestManager: ShareRequestManager by instance()

    init {
        Arbor.sow(Seedling())
    }

    abstract fun createWindow()

    fun start() {
        log.d("Application started")

        // Desktop has no runtime camera permission gate; mark media as ready so
        // mobile-only permission prompts (e.g. on ProfilesScreen) don't appear.
        mediaStateManager.update(MediaState.Ready)

        monitorBluetoothRequestManager()
        monitorShareRequestManager()

        createWindow()
    }

    protected open suspend fun onBluetoothRequest(request: BluetoothRequest) = Unit

    protected open suspend fun onShareRequest(request: ShareRequest) = Unit

    private fun monitorBluetoothRequestManager() {
        scope.launch {
            bluetoothRequestManager.bus.collect { request ->
                log.d { "BluetoothRequestManager bus request: $request" }
                // Isolate per-request failures: an uncaught throw here cancels the collector and
                // permanently kills the bridge, so every later scan/connect/write silently no-ops.
                // writeDeviceConfiguration throws when not connected and BlueFalcon calls can throw,
                // so swallow-and-log. Cancellation still propagates so the scope can be torn down.
                runCatching { onBluetoothRequest(request) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.e(error) { "Handling Bluetooth request $request failed" }
                    }
            }
        }
    }

    private fun monitorShareRequestManager() {
        scope.launch {
            shareRequestManager.bus.collect { request ->
                val summary = request.describe()
                log.d { "ShareRequestManager bus request: $summary" }
                runCatching { onShareRequest(request) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.e(error) { "Share request $summary failed" }
                    }
            }
        }
    }

    private fun ShareRequest.describe(): String = when (this) {
        is ShareRequest.Text -> "$fileName (${text.length} characters)"
    }

    companion object {
        private val log = Arbor.tag("Application")
    }
}
