package com.snapshotpb.configuration

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceStateSource
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.camera.cameraModule
import com.snapshotpb.configuration.debug.DebugBoard
import com.snapshotpb.configuration.debug.debugModule
import com.snapshotpb.configuration.di.coreDiModule
import com.snapshotpb.configuration.di.firmwareModule
import com.snapshotpb.configuration.di.platformModule
import com.snapshotpb.configuration.firmware.FirmwareHttp
import com.snapshotpb.configuration.firmware.KtorFirmwareHttp
import com.snapshotpb.configuration.model.SystemConfig
import com.snapshotpb.configuration.screen.screenModule
import com.snapshotpb.configuration.sync.WearSyncManager
import io.ktor.client.engine.android.Android
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

internal val appModule = DI.Module("appModule") {
    import(coreDiModule)
    import(platformModule)
    import(cameraModule)
    import(screenModule)
    // Binds DebugBoard and FirmwareUpdatePolicy. The debug source set binds the simulated board;
    // release binds an inert one, so a release APK holds none of it.
    import(debugModule)
    // The firmware update. The Wear app shares coreDiModule and platformModule, but it does not
    // update firmware, so it does not import this module.
    import(firmwareModule)

    bindSingleton {
        SystemConfig(
            supportsBluetoothEnableLinking = true
        )
    }

    // The HTTP access of the firmware feed, on the Ktor Android engine (HttpURLConnection). The
    // OkHttp engine of Ktor 3.6.0 needs compileSdk 37. The Wear app shares platformModule but does
    // not update firmware, so this binding is here.
    bindSingleton<FirmwareHttp> { KtorFirmwareHttp(Android) }

    // The advertisements for the firmware update, with the simulated board of a debug build.
    bindSingleton<BluetoothDeviceStateSource> {
        val bluetoothManager = instance<BluetoothManager>()
        val debugBoard = instance<DebugBoard>()
        BluetoothDeviceStateSource { debugBoard.deviceState(bluetoothManager.deviceState) }
    }

    bindSingleton {
        WearSyncManager(
            context = instance<Application>(),
            deviceStore = instance(),
            json = instance()
        )
    }
}
