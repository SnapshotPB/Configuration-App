package com.snapshotpb.configuration

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceStateSource
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.debug.DebugBoard
import com.snapshotpb.configuration.debug.debugModule
import com.snapshotpb.configuration.di.coreDiModule
import com.snapshotpb.configuration.di.firmwareModule
import com.snapshotpb.configuration.model.SystemConfig
import com.snapshotpb.configuration.screen.device.gatedDeviceState
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

val appModule = DI.Module("appModule") {
    import(coreDiModule)

    // Binds DebugBoard and FirmwareUpdatePolicy. The iosDebug source set imports the simulated
    // board's module; iosRelease binds an inert one. Which is compiled depends on the framework's
    // configuration.
    import(debugModule)

    // The firmware update. FirmwareHttp comes from the iOS platform module.
    import(firmwareModule)

    bindSingleton { SystemConfig() }

    // The advertisements for the firmware update. The gate keeps CoreBluetooth (and its permission
    // prompt) off until the user engages Bluetooth, as on the device browser.
    bindSingleton<BluetoothDeviceStateSource> {
        val debugBoard = instance<DebugBoard>()
        val bluetoothStateManager = instance<BluetoothStateManager>()
        val bluetoothManagerProvider: () -> BluetoothManager = { instance() }
        BluetoothDeviceStateSource {
            debugBoard.deviceState(gatedDeviceState(bluetoothStateManager, bluetoothManagerProvider, debugBoard))
        }
    }
}
