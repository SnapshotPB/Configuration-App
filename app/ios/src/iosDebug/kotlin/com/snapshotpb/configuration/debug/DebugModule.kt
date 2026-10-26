package com.snapshotpb.configuration.debug

import com.snapshotpb.configuration.firmware.FirmwareUpdatePolicy
import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * Debug bindings. The `iosRelease` source set holds a file of the same name that binds
 * [NoDebugBoard] instead; the framework's configuration decides which of the two is compiled (see
 * `simulatedBoard` in this module's build script). The simulated board itself lives in
 * `core:fake-board`, which only this side depends on, so no part of it exists in a release
 * framework.
 *
 * A debug build offers the firmware update before its release (see [FirmwareUpdatePolicy]).
 */
internal val debugModule = DI.Module("debugModule") {
    import(fakeBoardModule)
    bindSingleton { FirmwareUpdatePolicy(debugBuild = true) }
}
