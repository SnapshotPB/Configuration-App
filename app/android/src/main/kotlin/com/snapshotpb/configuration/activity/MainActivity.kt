package com.snapshotpb.configuration.activity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
import android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.snapshotpb.configuration.AppTheme
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothManager.Companion.hasBluetoothPermission
import com.snapshotpb.configuration.bluetooth.BluetoothManager.Companion.locationEnabledForScan
import com.snapshotpb.configuration.bluetooth.BluetoothManager.Companion.requestBluetoothPermissions
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.common.App
import com.snapshotpb.configuration.debug.DebugBoard
import com.snapshotpb.configuration.media.MediaManager
import com.snapshotpb.configuration.media.MediaManager.Companion.hasMediaPermission
import com.snapshotpb.configuration.media.MediaManager.Companion.requestMediaPermissions
import com.snapshotpb.configuration.media.MediaRequest
import com.snapshotpb.configuration.media.MediaRequestManager
import com.snapshotpb.configuration.sync.WearSyncManager
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.bluetooth_error_settings_menu
import com.snapshotpb.core.resources.location_error_settings_menu
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.compose.withDI
import org.kodein.di.instance

class MainActivity : ComponentActivity(), DIAware {

    private val bluetoothRequestManager: BluetoothRequestManager by instance()
    private val bluetoothManager: BluetoothManager by instance()
    private val bluetoothStateManager: BluetoothStateManager by instance()
    private val debugBoard: DebugBoard by instance()
    private val mediaManager: MediaManager by instance()
    private val mediaRequestManager: MediaRequestManager by instance()
    private val wearSyncManager: WearSyncManager by instance()

    override val di: DI by closestDI()

    // The toast text comes from Compose Resources, not from R.string: only Compose Resources
    // holds the translations, so an Android-side read would show English on a German or
    // French phone. Reading one is a suspend call, which is why both functions suspend.
    private suspend fun enableBluetoothRequest() {
        try {
            startActivity(Intent(ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                /* context = */ this,
                /* text = */ getString(Res.string.bluetooth_error_settings_menu),
                /* duration = */ Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun enableLocationRequest() {
        try {
            startActivity(Intent(ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                /* context = */ this,
                /* text = */ getString(Res.string.location_error_settings_menu),
                /* duration = */ Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun monitorBluetoothRequestManager() {
        lifecycleScope.launch {
            bluetoothRequestManager.bus.collect { request ->
                log.d { "BluetoothRequestManager bus request: $request" }
                // Isolate per-request failures: an uncaught throw here cancels the collector and
                // permanently kills the bridge, so every later scan/connect/write silently no-ops
                // with nothing surfaced to the user. startScanning() throws outright when the
                // permission check and the actual grant disagree. Cancellation must still
                // propagate so lifecycleScope can tear the collector down.
                runCatching { onBluetoothRequest(request) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.e(error) { "Handling Bluetooth request $request failed" }
                    }
            }
        }
    }

    private suspend fun onBluetoothRequest(request: BluetoothRequest) {
        // A debug build can answer with a simulated board instead of the radio. The release
        // binding claims nothing, so every request goes to the real manager below.
        if (debugBoard.handle(request)) return
        when (request) {
            BluetoothRequest.EnableBluetooth ->
                enableBluetoothRequest()

            BluetoothRequest.EnableLocation ->
                enableLocationRequest()

            BluetoothRequest.RequestPermission ->
                requestBluetoothPermissions(BLUETOOTH_PERMISSION_REQUEST_ID)

            BluetoothRequest.StartScan ->
                if (hasBluetoothPermission()) {
                    bluetoothManager.startScanning(this@MainActivity)
                } else {
                    requestBluetoothPermissions(BLUETOOTH_PERMISSION_REQUEST_ID)
                }

            BluetoothRequest.StopScan -> bluetoothManager.stopScanning()

            is BluetoothRequest.Connect ->
                bluetoothManager.connect(request.deviceId)

            is BluetoothRequest.Disconnect ->
                bluetoothManager.disconnect(request.deviceId)

            is BluetoothRequest.WriteDevice ->
                bluetoothManager.writeDeviceConfiguration(request.deviceId, request.device, request.requestId)
        }
    }

    /**
     * Reads the debug fake-board switch out of a launch intent. Only the Demo launcher entry of a
     * debug build carries the extra, so every other launch turns the simulated board off, and the
     * entry the user taps decides the mode. A release build binds a seam that ignores the answer.
     *
     * A null intent means "leave the mode alone": that is a restored activity, not a fresh launch.
     */
    private fun fakeBoardSwitch(intent: Intent?): Boolean? {
        if (intent == null) return null
        val extras = intent.extras ?: return false
        return extras.containsKey(EXTRA_FAKE_BOARD) && extras.getBoolean(EXTRA_FAKE_BOARD)
    }

    private fun monitorMediaRequestManager() {
        lifecycleScope.launch {
            mediaRequestManager.bus.collect { request ->
                log.d { "MediaRequestManager bus request: $request" }
                // The same isolation as the Bluetooth collector: one failed request must not
                // cancel the collector, or every later media request is lost. Cancellation still
                // propagates so lifecycleScope can stop the collector.
                runCatching { onMediaRequest(request) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.e(error) { "Handling media request $request failed" }
                    }
            }
        }
    }

    private fun onMediaRequest(request: MediaRequest) {
        when (request) {
            MediaRequest.RequestPermission ->
                requestMediaPermissions(MEDIA_PERMISSION_REQUEST_ID)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        debugBoard.applySwitch(fakeBoardSwitch(intent))
        checkBluetoothPermissions()
        checkMediaPermissions()

        monitorBluetoothRequestManager()
        monitorMediaRequestManager()
        wearSyncManager.startSync()

        setContent {
            withDI(di) {
                AppTheme {
                    Box(Modifier.safeDrawingPadding()) {
                        App(
                            onExitRequested = { finish() }
                        )
                    }
                }
            }
        }
    }

    /**
     * A launcher tap on the running app arrives here, because the activity is `singleTop`. The
     * entry that was tapped carries the debug fake-board switch, so re-apply it, then report the
     * radio the mode now asks for.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugBoard.applySwitch(fakeBoardSwitch(intent))
        checkBluetoothPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkBluetoothPermissions()
        checkMediaPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-sync after returning from a settings deep-link (Bluetooth radio, permissions, or the
        // Location toggle) — there is no system callback for the Location toggle, so re-check here.
        checkBluetoothPermissions()
        checkMediaPermissions()
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            if (bluetoothStateManager.bluetoothState.value != BluetoothState.Scanning) return@launch
            if (debugBoard.handle(BluetoothRequest.StopScan)) return@launch
            bluetoothManager.stopScanning()
        }
    }

    private fun checkBluetoothPermissions() {
        // The simulated board owns the Bluetooth state while it is on: the emulator it runs on
        // reports no radio, which would otherwise leave the UI stuck on "turn Bluetooth on".
        if (debugBoard.active) return
        log.d { "Checking Bluetooth permissions" }
        if (hasBluetoothPermission()) {
            log.d { "App has Bluetooth permissions" }
            bluetoothManager.permissionGranted()
        } else {
            log.d { "App lacks Bluetooth permissions" }
            bluetoothManager.needsPermissions()
        }

        if (locationEnabledForScan()) {
            bluetoothManager.locationServicesEnabled()
        } else {
            log.d { "Location services off; BLE scanning is blocked on this API level" }
            bluetoothManager.locationServicesDisabled()
        }
    }

    private fun checkMediaPermissions() {
        log.d { "Checking Media permissions" }
        if (hasMediaPermission()) {
            log.d { "App has Media permissions" }
            mediaManager.permissionGranted()
        } else {
            log.d { "App lacks Media permissions" }
            mediaManager.needsPermission()
        }
    }

    companion object {
        /**
         * Debug-only intent extra that turns the simulated board on. The Demo launcher entry sets
         * it; `adb shell am start -n <package>/.activity.DemoActivity` does the same from a shell.
         */
        internal const val EXTRA_FAKE_BOARD = "fake_board"

        private const val BLUETOOTH_PERMISSION_REQUEST_ID = 1
        private const val MEDIA_PERMISSION_REQUEST_ID = 2

        private val log = Arbor.tag("MainActivity")
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
