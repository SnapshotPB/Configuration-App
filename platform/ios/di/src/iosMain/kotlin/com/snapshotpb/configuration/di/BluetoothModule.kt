package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.bluetooth.BluetoothManager
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.ios.IosEngine
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val bluetoothModule = DI.Module("bluetoothModule") {
    bindSingleton {
        BluetoothManager(
            blueFalcon = BlueFalcon(engine = IosEngine()),
            bluetoothStateManager = instance(),
            bleDebugLog = instance(),
            deviceReadResultBus = instance(),
            deviceWriteResultBus = instance()
        )
    }
}
