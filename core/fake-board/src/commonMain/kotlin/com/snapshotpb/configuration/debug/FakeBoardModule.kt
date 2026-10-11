package com.snapshotpb.configuration.debug

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

/**
 * Binds the simulated board over the [DebugBoard] seam.
 *
 * The module is what this project exposes rather than [FakeBoard] itself, so the board stays
 * `internal` and no caller can reach past the seam to it. Only a debug configuration depends on
 * this project at all — `debugImplementation` on Android, and the `iosDebug` source set on iOS —
 * so neither a release APK nor a release framework compiles a line of it.
 */
val fakeBoardModule = DI.Module("fakeBoardModule") {
    bindSingleton<DebugBoard> {
        FakeBoard(
            bluetoothStateManager = instance(),
            deviceReadResultBus = instance(),
            deviceWriteResultBus = instance(),
        )
    }
}
