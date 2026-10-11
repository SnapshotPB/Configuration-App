package com.snapshotpb.configuration.demo

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

/**
 * Debug bindings. The `release` source set holds a file of the same name that binds
 * [NoWearDemoBoard] instead, so [WearFakeBoard] exists only in a debug APK.
 */
internal val demoModule = DI.Module("demoModule") {
    bindSingleton<WearDemoBoard> {
        WearFakeBoard(
            bluetoothStateManager = instance(),
            deviceReadResultBus = instance(),
            deviceWriteResultBus = instance(),
        )
    }
}
