package com.snapshotpb.configuration

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
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

        createWindow()
    }

    protected open suspend fun onBluetoothRequest(request: BluetoothRequest) = Unit

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

    companion object {
        private val log = Arbor.tag("Application")
    }
}
