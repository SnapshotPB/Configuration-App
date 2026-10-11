package com.snapshotpb.configuration

import com.snapshotpb.configuration.debug.debugModule
import com.snapshotpb.configuration.di.coreDiModule
import com.snapshotpb.configuration.di.platformModule
import com.snapshotpb.configuration.model.SystemConfig
import com.snapshotpb.configuration.screen.screenModule
import com.snapshotpb.configuration.sync.WearSyncManager
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val appModule = DI.Module("appModule") {
    import(coreDiModule)
    import(platformModule)
    import(screenModule)
    // Binds DebugBoard. The debug source set binds the simulated board; release binds an
    // inert one, so a release APK holds none of it.
    import(debugModule)

    bindSingleton {
        SystemConfig(
            supportsBluetoothEnableLinking = true
        )
    }

    bindSingleton {
        WearSyncManager(
            context = instance<Application>(),
            deviceStore = instance(),
            json = instance()
        )
    }
}
