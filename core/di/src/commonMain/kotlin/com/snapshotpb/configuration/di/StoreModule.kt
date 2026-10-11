package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.store.DeviceStore
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

expect val storeIOProviderModule: DI.Module

internal val storeModule = DI.Module("storeModule") {
    bindSingleton {
        // Explicit constructor to avoid lookup for default parameter
        DeviceStore(
            storeIOProvider = instance(),
            json = instance(),
        )
    }
}
