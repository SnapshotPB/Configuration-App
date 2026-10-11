package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.store.StoreIOProvider
import com.snapshotpb.configuration.store.StoreIOProviderIos
import org.kodein.di.DI
import org.kodein.di.bindProviderOf

actual val storeIOProviderModule: DI.Module = DI.Module("storeIOProviderModule") {
    bindProviderOf<StoreIOProvider>(::StoreIOProviderIos)
}
