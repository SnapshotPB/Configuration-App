package com.snapshotpb.configuration.screen.device

import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.common.screen.device.DeviceBrowserScreenViewModel
import com.snapshotpb.configuration.model.SystemConfig
import com.snapshotpb.configuration.store.DeviceStore
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.new

val deviceScreenViewModelModule = DI.Module("deviceScreenViewModelModule") {
    bindProvider<DeviceBrowserScreenViewModel> { new(::DeviceBrowserScreenViewModelImpl) }
}

/**
 * Desktop binding of the shared device-browser view model.
 *
 * The discovered-device derivation itself lives in [DeviceBrowserScreenViewModel]; this class exists
 * only to inject the platform's own [BluetoothManager] (each target has a distinct class of that
 * name, with no common supertype) and hand its [BluetoothManager.deviceState] to the base.
 */
private class DeviceBrowserScreenViewModelImpl(
    bluetoothManager: BluetoothManager,
    bluetoothStateManager: BluetoothStateManager,
    bluetoothRequestManager: BluetoothRequestManager,
    deviceStore: DeviceStore,
    systemConfig: SystemConfig,
) : DeviceBrowserScreenViewModel(
    bluetoothRequestManager = bluetoothRequestManager,
    bluetoothStateManager = bluetoothStateManager,
    deviceStore = deviceStore,
    systemConfig = systemConfig,
    deviceState = bluetoothManager.deviceState,
)
