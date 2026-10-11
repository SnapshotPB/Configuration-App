package com.snapshotpb.configuration.di

import org.kodein.di.DI

val platformModule = DI.Module("platformModule") {
    import(bluetoothModule)
    import(mediaModule)
}