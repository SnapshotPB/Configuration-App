package com.snapshotpb.configuration.debug

import com.snapshotpb.configuration.firmware.FirmwareUpdatePolicy
import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * Release bindings. The simulated board lives in `core:fake-board`, which a Release configuration
 * never puts on the compile classpath, so this build binds the inert [NoDebugBoard] and compiles
 * none of it.
 *
 * The release gate of the firmware update stays closed in this build (see [FirmwareUpdatePolicy]).
 */
internal val debugModule = DI.Module("debugModule") {
    bindSingleton<DebugBoard> { NoDebugBoard }
    bindSingleton { FirmwareUpdatePolicy() }
}
