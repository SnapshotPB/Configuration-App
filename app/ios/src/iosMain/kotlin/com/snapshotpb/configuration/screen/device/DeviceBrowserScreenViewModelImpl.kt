package com.snapshotpb.configuration.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceState
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothState
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.common.screen.device.DeviceBrowserScreenViewModel
import com.snapshotpb.configuration.debug.DebugBoard
import com.snapshotpb.configuration.model.SystemConfig
import com.snapshotpb.configuration.store.DeviceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.instance

val deviceScreenViewModelModule = DI.Module("deviceScreenViewModelModule") {
    bindProvider<DeviceBrowserScreenViewModel> {
        DeviceBrowserScreenViewModelImpl(
            // Lazy provider: invoking it is what first resolves (and therefore constructs) the
            // BluetoothManager, so CoreBluetooth — and its permission prompt — only starts when the
            // view model actually needs the radio, not when the browser screen is built at launch.
            bluetoothManagerProvider = { instance() },
            bluetoothStateManager = instance(),
            bluetoothRequestManager = instance(),
            debugBoard = instance(),
            deviceStore = instance(),
            systemConfig = instance(),
        )
    }
}

/**
 * iOS binding of the shared device-browser view model.
 *
 * The discovered-device derivation itself lives in [DeviceBrowserScreenViewModel]; what stays here
 * is the one genuinely iOS-specific concern: **when it is safe to touch the radio at all.**
 *
 * The browser screen collects `discoveredDevices` at launch. Subscribing to the manager's
 * `deviceState` resolves (and therefore constructs) the [BluetoothManager], which starts
 * CoreBluetooth and triggers the iOS permission prompt. So the flow handed to the base gates on
 * [BluetoothState]: while NeedsPermission / Disabled / PermissionDenied it emits an empty
 * [BluetoothDeviceState] and never invokes the provider. The provider is called only inside the
 * [flatMapLatest] lambda — i.e. only once the user has actually engaged Bluetooth — preserving the
 * deferral that `MainViewController.startOnce()` depends on to keep the prompt off the launch path.
 *
 * A [DebugBoard] that is active gates the radio off for a second reason: it holds the Bluetooth
 * state at Ready, which would otherwise carry the gate straight past its checks and construct
 * CoreBluetooth. The simulator has no CoreBluetooth to construct, so the board's own scan results —
 * added by [DebugBoard.deviceState] — are the only ones there are.
 */
private class DeviceBrowserScreenViewModelImpl(
    bluetoothManagerProvider: () -> BluetoothManager,
    bluetoothStateManager: BluetoothStateManager,
    bluetoothRequestManager: BluetoothRequestManager,
    private val debugBoard: DebugBoard,
    deviceStore: DeviceStore,
    systemConfig: SystemConfig,
) : DeviceBrowserScreenViewModel(
    bluetoothRequestManager = bluetoothRequestManager,
    bluetoothStateManager = bluetoothStateManager,
    deviceStore = deviceStore,
    systemConfig = systemConfig,
    deviceState = debugBoard.deviceState(
        gatedDeviceState(bluetoothStateManager, bluetoothManagerProvider, debugBoard)
    ),
) {
    override fun onDemoDeviceAdded() = debugBoard.applySwitch(true)
}

/**
 * The device state of the [BluetoothManager], gated so that no subscription constructs the manager
 * before the user engages Bluetooth (see [DeviceBrowserScreenViewModelImpl]). The firmware update
 * reads the advertisements through the same gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun gatedDeviceState(
    bluetoothStateManager: BluetoothStateManager,
    bluetoothManagerProvider: () -> BluetoothManager,
    debugBoard: DebugBoard,
): Flow<BluetoothDeviceState> =
    bluetoothStateManager.bluetoothState.flatMapLatest { state ->
        if (debugBoard.active ||
            state == BluetoothState.NeedsPermission ||
            state == BluetoothState.Disabled ||
            state == BluetoothState.PermissionDenied
        ) {
            flowOf(BluetoothDeviceState())
        } else {
            bluetoothManagerProvider().deviceState
        }
    }
