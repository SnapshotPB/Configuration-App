package com.snapshotpb.configuration

import com.snapshotpb.configuration.bluetooth.BluetoothDeviceStateSource
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.di.firmwareModule
import com.snapshotpb.configuration.firmware.FIRMWARE_SITE_URL
import com.snapshotpb.configuration.firmware.FirmwareUpdatePolicy
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

/** The command-line argument of a local firmware site: `--firmware-site-url=<url>` or `--firmware-site-url <url>`. */
internal const val FIRMWARE_SITE_URL_ARGUMENT = "--firmware-site-url"

/** The system property of a local firmware site, for a start with `-D` (for example from Gradle). */
internal const val FIRMWARE_SITE_URL_PROPERTY = "snapshotpb.firmwareSiteUrl"

/**
 * The URL of the local firmware site of a bench test (work item 12 of the firmware update plan),
 * from [args] or from the system property [FIRMWARE_SITE_URL_PROPERTY]. `null` means the public site.
 */
internal fun firmwareSiteUrlOf(args: Array<String>): String? {
    val index = args.indexOfFirst { it == FIRMWARE_SITE_URL_ARGUMENT || it.startsWith("$FIRMWARE_SITE_URL_ARGUMENT=") }
    val fromArgs = when {
        index < 0 -> null
        args[index] == FIRMWARE_SITE_URL_ARGUMENT -> args.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
        else -> args[index].substringAfter('=')
    }
    return (fromArgs ?: System.getProperty(FIRMWARE_SITE_URL_PROPERTY))?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Binds the firmware update of the desktop app. The desktop app has no debug build, so only a
 * bench site ([siteUrl]) opens the release gate (see [FirmwareUpdatePolicy]). Any URL that is not
 * the public site counts: the argument is for the bench, and a user who gives it chooses the site.
 */
internal fun firmwareUpdateModule(siteUrl: String?) = DI.Module("firmwareUpdateModule") {
    import(firmwareModule)
    bindSingleton { FirmwareUpdatePolicy(siteUrl = siteUrl ?: FIRMWARE_SITE_URL) }
    bindSingleton<BluetoothDeviceStateSource> {
        val bluetoothManager = instance<BluetoothManager>()
        BluetoothDeviceStateSource { bluetoothManager.deviceState }
    }
}
