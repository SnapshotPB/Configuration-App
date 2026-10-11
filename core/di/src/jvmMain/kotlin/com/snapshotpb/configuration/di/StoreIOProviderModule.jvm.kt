package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.store.StoreIOProvider
import com.snapshotpb.configuration.store.StoreIOProviderJvm
import org.kodein.di.DI
import org.kodein.di.bindProvider

actual val storeIOProviderModule: DI.Module = DI.Module("storeIOProviderModule") {
    // A lambda, not `bindProviderOf(::StoreIOProviderJvm)`: the constructor has parameters with
    // defaults (the data directory, for tests), and a constructor reference would make Kodein look
    // up a File binding for each one. The Android and Wear apps override this binding.
    bindProvider<StoreIOProvider> { StoreIOProviderJvm() }
}
