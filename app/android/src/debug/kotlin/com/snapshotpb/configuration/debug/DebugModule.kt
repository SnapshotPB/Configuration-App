package com.snapshotpb.configuration.debug

import android.app.Application
import com.snapshotpb.configuration.firmware.FIRMWARE_SITE_URL
import com.snapshotpb.configuration.firmware.FirmwareUpdatePolicy
import com.toxicbakery.logging.Arbor
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import java.io.File
import java.io.IOException

/**
 * The name of the file, in the files directory of the app, that holds the URL of a local firmware
 * site for a bench test (`docs/firmware-update-plan.md`, work item 12). Write it with adb:
 *
 * ```
 * adb reverse tcp:8765 tcp:8765
 * adb shell "run-as com.snapshotpb.configuration sh -c 'mkdir -p files && echo http://localhost:8765 > files/firmware-site-url'"
 * ```
 *
 * Then stop the app and start it again. Remove the file to use the public site again. Only a debug
 * build reads the file, and only a debug build permits plain HTTP to localhost
 * (`res/xml/network_security_config.xml` of the debug source set).
 */
private const val FIRMWARE_SITE_URL_FILE = "firmware-site-url"

private val log = Arbor.tag("DebugModule")

/**
 * Debug bindings. The `release` source set holds a file of the same name that binds [NoDebugBoard]
 * instead, and the benchmark build type compiles that same release copy. The simulated board itself
 * lives in `core:fake-board`, which only `debugImplementation` puts on the compile classpath, so no
 * part of it exists in a release APK.
 *
 * A debug build offers the firmware update before its release (see [FirmwareUpdatePolicy]). It reads
 * the firmware site from [FIRMWARE_SITE_URL_FILE] when that file exists.
 */
internal val debugModule = DI.Module("debugModule") {
    import(fakeBoardModule)
    bindSingleton {
        FirmwareUpdatePolicy(
            debugBuild = true,
            siteUrl = benchSiteUrl(instance<Application>()) ?: FIRMWARE_SITE_URL,
        )
    }
}

/** The URL in [FIRMWARE_SITE_URL_FILE], or null when the file does not exist or is empty. */
private fun benchSiteUrl(application: Application): String? {
    val file = File(application.filesDir, FIRMWARE_SITE_URL_FILE)
    val url = try {
        if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (e: IOException) {
        log.e(e) { "Cannot read $file" }
        null
    }
    if (url != null) log.i { "The firmware site is the bench site $url" }
    return url
}
