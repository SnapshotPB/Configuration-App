package com.snapshotpb.configuration.debug

import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * Release bindings. The simulated board lives in the `debug` source set, so this build binds the
 * inert [NoDebugBoard] and compiles none of it. The benchmark build type compiles this file too
 * (see the `sourceSets` block in the module's build script).
 */
internal val debugModule = DI.Module("debugModule") {
    bindSingleton<DebugBoard> { NoDebugBoard }
}
