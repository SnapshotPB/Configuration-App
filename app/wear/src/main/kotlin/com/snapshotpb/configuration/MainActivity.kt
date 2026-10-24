package com.snapshotpb.configuration

import android.content.Intent
import android.os.Bundle
import android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
import android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothManager.Companion.hasBluetoothPermission
import com.snapshotpb.configuration.bluetooth.BluetoothManager.Companion.locationEnabledForScan
import com.snapshotpb.configuration.bluetooth.BluetoothManager.Companion.requestBluetoothPermissions
import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.common.screen.delegate.DeviceSyncDelegate
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegate
import com.snapshotpb.configuration.demo.WearDemoBoard
import com.snapshotpb.configuration.store.DeviceStore
import com.snapshotpb.configuration.sync.WearWatchSyncManager
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.wear_bluetooth_settings_error
import com.snapshotpb.core.resources.wear_location_settings_error
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

class MainActivity : ComponentActivity(), DIAware {

    override val di: DI by closestDI()
    private val bluetoothManager: BluetoothManager by instance()
    private val bluetoothRequestManager: BluetoothRequestManager by instance()
    private val bluetoothStateManager: BluetoothStateManager by instance()
    private val deviceStore: DeviceStore by instance()
    private val deviceSyncDelegate: DeviceSyncDelegate by instance()
    private val deviceReadResultBus: DeviceReadResultBus by instance()

    // The watch saves through the shared delegate rather than the request bus directly. Its
    // singleton observes DeviceWriteResultBus for the whole process, so it reconciles the store
    // with what the board actually stored -- including a language the board refused to draw.
    private val deviceViewModelDelegate: DeviceViewModelDelegate by instance()
    private val wearWatchSyncManager: WearWatchSyncManager by instance()

    // A debug build binds a demo board for the E2E tests. The release binding is inert.
    private val demoBoard: WearDemoBoard by instance()
    private val log = Arbor.tag("WearMainActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The E2E tests turn the demo board on with an intent extra. A release build binds a
        // seam that ignores the switch, so a release app does not accept it from an intent.
        demoBoard.applySwitch(intent?.getBooleanExtra(EXTRA_MOCK_DEVICES, false) ?: false)

        checkBluetoothPermissions()
        monitorBluetoothRequestManager()

        // Start the watch-to-phone sync. The demo board must not reach the paired phone, so a run
        // with the demo board on does not sync.
        if (demoBoard.active) {
            log.d { "Demo board is on; watch-to-phone sync stays off" }
        } else {
            wearWatchSyncManager.startSync()
        }

        setContent {
            WearApp(
                bluetoothManager = bluetoothManager,
                bluetoothStateManager = bluetoothStateManager,
                bluetoothRequestManager = bluetoothRequestManager,
                deviceStore = deviceStore,
                deviceSyncDelegate = deviceSyncDelegate,
                deviceReadResultBus = deviceReadResultBus,
                deviceViewModelDelegate = deviceViewModelDelegate,
                demoBoard = demoBoard,
            )
        }
    }

    private suspend fun enableBluetoothRequest() {
        try {
            startActivity(Intent(ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                getString(Res.string.wear_bluetooth_settings_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun enableLocationRequest() {
        try {
            startActivity(Intent(ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                getString(Res.string.wear_location_settings_error),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkBluetoothPermissions() {
        log.d { "Checking Bluetooth permissions" }
        if (hasBluetoothPermission()) {
            log.d { "App has Bluetooth permissions" }
            bluetoothManager.permissionGranted()
        } else {
            log.d { "App lacks Bluetooth permissions" }
            bluetoothManager.needsPermissions()
        }

        // Wear OS 3/4 (minSdk 30) is pre-S, so BLE scanning is gated behind the Location toggle.
        if (locationEnabledForScan()) {
            bluetoothManager.locationServicesEnabled()
        } else {
            log.d { "Location services off; BLE scanning is blocked on this API level" }
            bluetoothManager.locationServicesDisabled()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync after returning from a settings deep-link; there is no system callback for the
        // Location toggle, so re-check it here.
        checkBluetoothPermissions()
    }

    private fun monitorBluetoothRequestManager() {
        lifecycleScope.launch {
            bluetoothRequestManager.bus.collect { request ->
                log.d { "BluetoothRequestManager bus request: $request" }
                // Isolate per-request failures: an uncaught throw here cancels the collector and
                // permanently kills the bridge, so every later scan/connect/write silently no-ops.
                // Cancellation still propagates so lifecycleScope can tear the collector down.
                runCatching { onBluetoothRequest(request) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.e(error) { "Handling Bluetooth request $request failed" }
                    }
            }
        }
    }

    private suspend fun onBluetoothRequest(request: BluetoothRequest) {
        // A debug build can answer with the demo board instead of the radio. The release binding
        // claims nothing, so every request goes to the real manager below.
        if (demoBoard.handle(request)) return
        when (request) {
            BluetoothRequest.EnableBluetooth ->
                enableBluetoothRequest()

            BluetoothRequest.EnableLocation ->
                enableLocationRequest()

            BluetoothRequest.RequestPermission ->
                requestBluetoothPermissions(BLUETOOTH_PERMISSION_REQUEST_ID)

            BluetoothRequest.StartScan -> {
                if (hasBluetoothPermission()) {
                    bluetoothManager.startScanning(this@MainActivity)
                } else {
                    requestBluetoothPermissions(BLUETOOTH_PERMISSION_REQUEST_ID)
                }
            }

            BluetoothRequest.StopScan -> bluetoothManager.stopScanning()

            is BluetoothRequest.Connect ->
                bluetoothManager.connect(request.deviceId)

            is BluetoothRequest.Disconnect ->
                bluetoothManager.disconnect(request.deviceId)

            is BluetoothRequest.WriteDevice ->
                bluetoothManager.writeDeviceConfiguration(request.deviceId, request.device, request.requestId)

            // The watch does not update firmware, and nothing on the watch sends these requests.
            is BluetoothRequest.UpdateFirmware,
            is BluetoothRequest.ConfirmFirmware ->
                log.d { "The watch does not update firmware; it ignores $request" }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkBluetoothPermissions()
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            // Stop a scan that found nothing too, or it runs on until the user comes back.
            if (bluetoothStateManager.bluetoothState.value == BluetoothState.Scanning) {
                bluetoothManager.stopScanning()
            }
        }
    }

    companion object {
        private const val BLUETOOTH_PERMISSION_REQUEST_ID = 1

        /** Debug-only intent extra that turns the demo board on. The E2E tests set it. */
        const val EXTRA_MOCK_DEVICES = "MOCK_DEVICES"
    }
}
