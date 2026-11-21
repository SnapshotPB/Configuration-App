@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // The module has no Compose code. The wasm test binary links core:common, which imports the
    // skiko runtime, and only the Compose plugin puts skiko.mjs next to the test binary. Without
    // it, wasmJsBrowserTest cannot load the bundle and stops after the Karma time limit.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    configureOptIns()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs {
        browser()
        // The Compose plugin (1.12 and later) fails the wasm tests of a module whose test
        // classpath holds Skiko, unless the target declares an executable binary. The binary
        // lets webpack bundle the tests with the Skiko runtime (CMP-4906). The module stays a
        // library: no app uses this binary.
        binaries.executable()
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.core.bluetooth)
                implementation(projects.core.common)
                implementation(projects.core.firmware)
                implementation(projects.core.media)
                implementation(projects.core.store)

                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.kodein)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
