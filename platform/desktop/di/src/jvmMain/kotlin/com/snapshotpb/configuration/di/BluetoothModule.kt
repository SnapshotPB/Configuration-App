package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.selectBlueFalconEngine
import dev.bluefalcon.core.BlueFalcon
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val bluetoothModule = DI.Module("bluetoothModule") {
    bindSingleton {
        BluetoothManager(
            blueFalcon = BlueFalcon(engine = selectBlueFalconEngine()),
            bluetoothStateManager = instance(),
            bleDebugLog = instance(),
            deviceReadResultBus = instance(),
            deviceWriteResultBus = instance()
        )
    }
}