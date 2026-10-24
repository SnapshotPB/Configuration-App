package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.firmware.FirmwareHttp
import com.snapshotpb.configuration.firmware.KtorFirmwareHttp
import io.ktor.client.engine.js.Js
import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * The HTTP access of the firmware feed in the browser: the Ktor Js engine (`fetch`). The firmware
 * site answers with `access-control-allow-origin: *`, so the page can read it.
 */
internal val firmwareHttpModule = DI.Module("firmwareHttpModule") {
    bindSingleton<FirmwareHttp> { KtorFirmwareHttp(Js) }
}
