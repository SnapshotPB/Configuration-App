package com.snapshotpb.configuration.di

import com.snapshotpb.configuration.firmware.FirmwareHttp
import com.snapshotpb.configuration.firmware.KtorFirmwareHttp
import io.ktor.client.engine.cio.CIO
import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * The HTTP access of the firmware feed on the desktop: the Ktor CIO engine.
 *
 * Nobody has read the site from an installed desktop app yet. The installers hold a reduced JDK
 * runtime, so the bench test of the firmware update must also do a download with an installed app.
 */
internal val firmwareHttpModule = DI.Module("firmwareHttpModule") {
    bindSingleton<FirmwareHttp> { KtorFirmwareHttp(CIO) }
}
