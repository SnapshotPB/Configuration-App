package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.store.StoreIOProvider
import com.snapshotpb.configuration.store.StoreIOProviderWasm
import org.kodein.di.DI
import org.kodein.di.bindProvider

actual val storeIOProviderModule: DI.Module = DI.Module("storeIOProviderModule") {
    // A lambda, not `bindProviderOf(::StoreIOProviderWasm)`: the constructor has a parameter with a
    // default value (the photo store of the page), and Kodein will look for it in the bindings.
    bindProvider<StoreIOProvider> { StoreIOProviderWasm() }
}
