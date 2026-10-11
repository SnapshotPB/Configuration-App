package com.snapshotpb.configuration.debug

import org.kodein.di.DI

/**
 * Debug bindings. The `release` source set holds a file of the same name that binds [NoDebugBoard]
 * instead, and the benchmark build type compiles that same release copy. The simulated board itself
 * lives in `core:fake-board`, which only `debugImplementation` puts on the compile classpath, so no
 * part of it exists in a release APK.
 */
internal val debugModule = DI.Module("debugModule") {
    import(fakeBoardModule)
}
