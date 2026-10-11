package com.snapshotpb.configuration.screen

import com.snapshotpb.configuration.screen.device.deviceScreenViewModelModule
import org.kodein.di.DI

val screenModule = DI.Module("screenModule") {
    import(deviceScreenViewModelModule)
}
