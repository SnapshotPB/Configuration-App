package com.snapshotpb.configuration

import com.snapshotpb.configuration.demo.demoModule
import com.snapshotpb.configuration.di.coreDiModule
import com.snapshotpb.configuration.di.platformModule
import com.snapshotpb.configuration.model.SystemConfig
import com.snapshotpb.configuration.sync.WearWatchSyncManager
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val appModule = DI.Module("appModule") {
    import(coreDiModule)
    import(platformModule)
    // Binds WearDemoBoard. The debug source set binds the demo board; release binds an inert one.
    import(demoModule)

    bindSingleton {
        SystemConfig(
            supportsBluetoothEnableLinking = true
        )
    }

    bindSingleton {
        WearWatchSyncManager(
            context = instance<Application>(),
            deviceStore = instance(),
            json = instance()
        )
    }
}
