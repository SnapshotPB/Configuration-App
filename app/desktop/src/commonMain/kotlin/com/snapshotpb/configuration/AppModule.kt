package com.snapshotpb.configuration

import com.snapshotpb.configuration.camera.cameraModule
import com.snapshotpb.configuration.di.coreDiModule
import com.snapshotpb.configuration.model.SystemConfig
import org.kodein.di.DI
import org.kodein.di.bindSingleton

val appModule = DI.Module("appModule") {
    import(coreDiModule)
    import(cameraModule)

    bindSingleton { SystemConfig() }
}
