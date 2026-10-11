package com.snapshotpb.configuration

import com.snapshotpb.configuration.debug.debugModule
import com.snapshotpb.configuration.di.coreDiModule
import com.snapshotpb.configuration.model.SystemConfig
import org.kodein.di.DI
import org.kodein.di.bindSingleton

val appModule = DI.Module("appModule") {
    import(coreDiModule)

    // Binds DebugBoard. The iosDebug source set imports the simulated board's module; iosRelease
    // binds an inert one. Which is compiled depends on the framework's configuration.
    import(debugModule)

    bindSingleton { SystemConfig() }
}
