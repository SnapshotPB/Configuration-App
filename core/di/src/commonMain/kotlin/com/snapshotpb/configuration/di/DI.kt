package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.store.appJson
import org.kodein.di.DI
import org.kodein.di.bindSingleton

val coreDiModule = DI.Module("coreDiModule") {
    import(bluetoothCoreModule)
    import(firmwareModule)
    import(mediaCoreModule)
    import(storeIOProviderModule)
    import(storeModule)
    import(viewModelModule)

    // App-wide Json singleton. Its configuration (notably coerceInputValues, which keeps a
    // removed enum constant in stored data from failing the whole load) lives with the models
    // it serializes; see com.snapshotpb.configuration.store.appJson.
    bindSingleton { appJson }
}
