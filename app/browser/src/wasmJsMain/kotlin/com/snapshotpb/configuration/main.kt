@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.snapshotpb.configuration

import androidx.compose.ui.window.ComposeViewport
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.emulator.EmulatorEngine
import com.snapshotpb.configuration.common.App
import com.snapshotpb.configuration.common.media.installWebPhotoLoader
import com.snapshotpb.configuration.di.platformModule
import com.snapshotpb.configuration.media.MediaState
import com.snapshotpb.configuration.media.MediaStateManager
import com.snapshotpb.configuration.screen.screenModule
import com.toxicbakery.logging.Arbor
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.compose.withDI
import org.kodein.di.direct
import org.kodein.di.instance

private val log = Arbor.tag("BrowserMain")

/**
 * Root DI graph for the web app, assembled exactly like the iOS/desktop entry points: the shared
 * [appModule] (which imports the core DI graph), the browser [platformModule] (Web Bluetooth-backed
 * BlueFalcon) from `platform:browser:di`, and the browser [screenModule] of platform view models.
 */
private val di = DI {
    import(appModule)
    import(platformModule)
    import(screenModule)
}

private val appScope = CoroutineScope(Dispatchers.Default)

fun main() {
    // Arbor has no console Seedling in its wasm variant, so logging is a no-op on web for now;
    // Arbor.tag(...) calls stay harmless. Browser-console logging is a later refinement.
    val webBluetoothSupported = isWebBluetoothSupported()
    // In the 3D viewer of the board, the app talks to the model of the board through the bridge of
    // the viewer, and it needs no Web Bluetooth (see EmulatorEngine).
    val emulatedBoard = EmulatorEngine.isAvailable()
    val bluetoothSupported = webBluetoothSupported || emulatedBoard
    log.d { "Web Bluetooth supported: $webBluetoothSupported, emulated board: $emulatedBoard" }

    // Only wire Bluetooth when the browser actually has the API or the viewer bridge — otherwise the
    // app is a hard block (`UnsupportedBrowserScreen`) and nothing should resolve the BlueFalcon-backed manager.
    if (bluetoothSupported) {
        startBluetoothBridge()
    }

    // The image loader must know the stored device photos before the first badge loads.
    installWebPhotoLoader()
    removeLoadingOverlay()
    ComposeViewport(document.body!!) {
        AppTheme {
            if (bluetoothSupported) {
                withDI(di) {
                    // The browser back and forward buttons move the nav stack (BrowserHistoryEffect).
                    // The floating back button stays, for the 3D viewer iframe and for an installed
                    // web app with no browser controls.
                    App(showFloatingBackButton = true)
                }
            } else {
                UnsupportedBrowserScreen()
            }
        }
    }
}

/**
 * Bridges the [BluetoothRequestManager] bus to the [BluetoothManager], exactly like the desktop /
 * iOS entry points. The shared view models only ever `send` onto the bus (a zero-buffer SharedFlow);
 * without a collector every scan/connect/write would suspend forever. Resolving the manager here
 * also starts its BlueFalcon collectors, which settle [com.snapshotpb.configuration.bluetooth.BluetoothState]
 * to Ready (the Web Bluetooth engine reports its manager state as Ready immediately).
 */
private fun startBluetoothBridge() {
    val mediaStateManager: MediaStateManager = di.direct.instance()
    mediaStateManager.update(MediaState.Ready)

    val bluetoothManager: BluetoothManager = di.direct.instance()
    val bluetoothRequestManager: BluetoothRequestManager = di.direct.instance()
    appScope.launch {
        bluetoothRequestManager.bus.collect { request ->
            log.d { "BluetoothRequestManager bus request: $request" }
            // Isolate per-request failures: an uncaught throw here would cancel the collector and
            // permanently kill the bridge (every later scan/connect/write would silently no-op).
            // Cancellation must still propagate so the collector can be torn down cleanly.
            runCatching { onBluetoothRequest(bluetoothManager, request) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.e(error) { "Handling Bluetooth request $request failed" }
                }
        }
    }
}

private suspend fun onBluetoothRequest(bluetoothManager: BluetoothManager, request: BluetoothRequest) {
    when (request) {
        BluetoothRequest.EnableBluetooth ->
            log.d { "Enable Bluetooth requested on web; the browser manages the radio." }
        BluetoothRequest.EnableLocation ->
            log.d { "Enable Location requested on web; not applicable — the browser manages scanning." }
        BluetoothRequest.RequestPermission ->
            log.d { "Permission requested on web; the Web Bluetooth chooser is the permission gate." }
        BluetoothRequest.StartScan -> bluetoothManager.startScanning()
        BluetoothRequest.StopScan -> bluetoothManager.stopScanning()
        is BluetoothRequest.Connect -> bluetoothManager.connect(request.deviceId)
        is BluetoothRequest.Disconnect -> bluetoothManager.disconnect(request.deviceId)
        is BluetoothRequest.WriteDevice ->
            bluetoothManager.writeDeviceConfiguration(request.deviceId, request.device, request.requestId)
    }
}

/** Feature-detects the Web Bluetooth API. Present only on Chromium browsers. */
private fun isWebBluetoothSupported(): Boolean =
    js("(typeof navigator !== 'undefined' && 'bluetooth' in navigator)")

/** Removes the static HTML loading placeholder once the wasm bundle has booted. */
private fun removeLoadingOverlay(): Unit =
    js("document.getElementById('loading')?.remove()")
