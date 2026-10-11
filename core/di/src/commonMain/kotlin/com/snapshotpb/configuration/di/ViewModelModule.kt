package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.common.screen.delegate.DeviceSyncDelegate
import com.snapshotpb.configuration.common.screen.delegate.DeviceSyncDelegateImpl
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegate
import com.snapshotpb.configuration.common.screen.delegate.DeviceViewModelDelegateImpl
import com.snapshotpb.configuration.common.screen.device.ConnectingDeviceViewModel
import com.snapshotpb.configuration.common.screen.device.ConfigureDeviceViewModel
import com.snapshotpb.configuration.common.screen.profile.ProfileAddViewModel
import com.snapshotpb.configuration.common.screen.profile.ProfileViewModel
import com.snapshotpb.configuration.common.screen.profile.ProfilesScreenViewModel
import org.kodein.di.DI
import org.kodein.di.bindFactory
import org.kodein.di.bindSingleton
import org.kodein.di.instance

val viewModelModule = DI.Module("viewModelModule") {
    bindFactory<String, ProfilesScreenViewModel> { deviceId: String ->
        ProfilesScreenViewModel(
            deviceId = deviceId,
            deviceStore = instance(),
            bluetoothStateManager = instance(),
            deviceViewModelDelegate = instance()
        )
    }
    bindFactory<String, ProfileAddViewModel> { deviceId: String ->
        ProfileAddViewModel(
            deviceId = deviceId,
            deviceStore = instance()
        )
    }
    bindFactory<ProfileViewModel.Args, ProfileViewModel> { args ->
        ProfileViewModel(
            args = args,
            deviceStore = instance(),
            deviceViewModelDelegate = instance(),
        )
    }
    bindFactory<String, ConfigureDeviceViewModel> { deviceId: String ->
        ConfigureDeviceViewModel(
            deviceId = deviceId,
            deviceStore = instance(),
            deviceViewModelDelegate = instance(),
        )
    }
    bindFactory<String, ConnectingDeviceViewModel> { deviceId: String ->
        ConnectingDeviceViewModel(
            deviceId = deviceId,
            deviceSyncDelegate = instance(),
            bluetoothStateManager = instance(),
            deviceStore = instance(),
            deviceViewModelDelegate = instance(),
        )
    }
    bindSingleton<DeviceViewModelDelegate> {
        DeviceViewModelDelegateImpl(
            deviceStore = instance(),
            bluetoothRequestManager = instance(),
            deviceWriteResultBus = instance(),
        )
    }
    bindSingleton<DeviceSyncDelegate> {
        DeviceSyncDelegateImpl(
            deviceStore = instance(),
            deviceReadResultBus = instance(),
            bluetoothRequestManager = instance(),
        )
    }
}
