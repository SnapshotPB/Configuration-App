package com.snapshotpb.configuration

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceStateSource
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.di.coreDiModule
import com.snapshotpb.configuration.di.firmwareModule
import com.snapshotpb.configuration.firmware.FirmwareUpdatePolicy
import com.snapshotpb.configuration.model.SystemConfig
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

val appModule = DI.Module("appModule") {
    import(coreDiModule)

    // The firmware update. FirmwareHttp comes from the browser platform module. The web engine has
    // no write without response with a result yet, so the board reports an unsupported platform and
    // the status offers no update.
    import(firmwareModule)

    bindSingleton { SystemConfig() }

    bindSingleton { FirmwareUpdatePolicy() }

    bindSingleton<BluetoothDeviceStateSource> {
        val bluetoothManager = instance<BluetoothManager>()
        BluetoothDeviceStateSource { bluetoothManager.deviceState }
    }
}
