package com.snapshotpb.configuration.demo

import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * Release bindings. The demo board lives in the `debug` source set, so this build binds the inert
 * [NoWearDemoBoard] and compiles none of it.
 */
internal val demoModule = DI.Module("demoModule") {
    bindSingleton<WearDemoBoard> { NoWearDemoBoard }
}
