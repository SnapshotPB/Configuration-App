package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.firmware.FirmwareHttp
import com.snapshotpb.configuration.firmware.KtorFirmwareHttp
import io.ktor.client.engine.darwin.Darwin
import org.kodein.di.DI
import org.kodein.di.bindSingleton

/** The HTTP access of the firmware feed on iOS: the Ktor Darwin engine (`NSURLSession`). */
internal val firmwareHttpModule = DI.Module("firmwareHttpModule") {
    bindSingleton<FirmwareHttp> { KtorFirmwareHttp(Darwin) }
}
