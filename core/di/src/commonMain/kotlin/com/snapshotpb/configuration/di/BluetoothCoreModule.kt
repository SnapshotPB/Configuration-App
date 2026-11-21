package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.bluetooth.BleDebugLog
import com.snapshotpb.configuration.bluetooth.BluetoothRequestManager
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.log.BluetoothLog
import com.snapshotpb.configuration.bluetooth.log.DisabledBluetoothLog
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.bindSingletonOf

val bluetoothCoreModule = DI.Module("bluetoothCoreModule") {
    bindSingletonOf(::BluetoothStateManager)
    bindSingletonOf(::BluetoothRequestManager)
    bindSingletonOf(::BleDebugLog)
    bindSingleton<BluetoothLog> { DisabledBluetoothLog }
    bindSingletonOf(::DeviceReadResultBus)
    bindSingletonOf(::DeviceWriteResultBus)
}
