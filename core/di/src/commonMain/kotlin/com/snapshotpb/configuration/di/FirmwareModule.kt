package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.firmware.FirmwareFeed
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

/**
 * Binds the firmware feed. The feed needs a `FirmwareHttp`, which each app binds with the Ktor
 * engine of its platform: `platform:desktop:di`, `platform:ios:di` and `platform:browser:di` in
 * their platform module, and `app:android` in its app module. The Wear app binds none, because it
 * does not update firmware. Kodein makes the feed only when a caller asks for it.
 */
internal val firmwareModule = DI.Module("firmwareModule") {
    bindSingleton { FirmwareFeed(http = instance()) }
}
