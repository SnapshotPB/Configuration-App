package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.common.screen.firmware.FirmwareSectionViewModel
import com.snapshotpb.configuration.common.screen.firmware.FirmwareUpdateViewModel
import com.snapshotpb.configuration.firmware.FirmwareFeed
import com.snapshotpb.configuration.firmware.FirmwareFeedOverride
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinatorImpl
import com.snapshotpb.configuration.firmware.FirmwareUpdatePolicy
import org.kodein.di.DI
import org.kodein.di.bindFactory
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

/**
 * Binds the firmware feed, the firmware update coordinator, and the view models of the Firmware
 * section and of the firmware update screen. [coreDiModule] does not import this
 * module: the four apps that update firmware (Android, desktop, iOS, browser) import it, and the
 * Wear app does not.
 *
 * An app that imports it binds three values:
 *
 * - `FirmwareHttp`, with the Ktor engine of its platform: `platform:desktop:di`, `platform:ios:di`
 *   and `platform:browser:di` in their platform module, and `app:android` in its app module.
 * - [FirmwareUpdatePolicy]: the release gate and the firmware site of the build.
 * - `BluetoothDeviceStateSource`: the discovered devices of its Bluetooth manager.
 *
 * A debug build of Android and iOS can bind a [FirmwareFeedOverride] (`core:fake-board`). The
 * coordinator then reads the simulated site while the simulated board is active.
 *
 * Kodein makes each value only when a caller asks for it.
 */
val firmwareModule = DI.Module("firmwareModule") {
    bindSingleton { FirmwareFeed(http = instance(), siteUrl = instance<FirmwareUpdatePolicy>().siteUrl) }

    bindSingleton<FirmwareUpdateCoordinator> {
        val siteFeed = instance<FirmwareFeed>()
        val override = instanceOrNull<FirmwareFeedOverride>()
        FirmwareUpdateCoordinatorImpl(
            feed = { override?.feedOrNull() ?: siteFeed },
            policy = instance(),
            bluetoothRequestManager = instance(),
            bluetoothStateManager = instance(),
            deviceStateSource = instance(),
        )
    }

    // The view models are here and not in viewModelModule: the Wear app imports viewModelModule,
    // and its graph has no coordinator.
    bindFactory<String, FirmwareSectionViewModel> { deviceId: String ->
        FirmwareSectionViewModel(deviceId = deviceId, coordinator = instance())
    }
    bindFactory<String, FirmwareUpdateViewModel> { deviceId: String ->
        FirmwareUpdateViewModel(deviceId = deviceId, coordinator = instance(), deviceStore = instance())
    }
}
