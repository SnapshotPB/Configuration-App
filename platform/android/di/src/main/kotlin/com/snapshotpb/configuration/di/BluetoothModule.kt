package com.snapshotpb.configuration.di

import android.app.Application
import com.snapshotpb.configuration.bluetooth.BlueFalconLogger
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.android.AndroidEngine
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val bluetoothModule = DI.Module("bluetoothModule") {
    bindSingleton {
        BluetoothManager(
            blueFalcon = BlueFalcon(
                engine = AndroidEngine(
                    instance<Application>(),
                    logger = BlueFalconLogger(),
                    autoDiscoverAllServicesAndCharacteristics = true
                ),
            ),
            bluetoothStateManager = instance(),
            bleDebugLog = instance(),
            deviceReadResultBus = instance(),
            deviceWriteResultBus = instance()
        )
    }
}
