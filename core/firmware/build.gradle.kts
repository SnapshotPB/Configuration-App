@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The firmware feed: it reads the firmware site and downloads and checks an update image. The
// Ktor client core is here only. Each platform gives the engine (see KtorFirmwareHttp). The update
// coordinator sends the Bluetooth requests of the update, so the module uses core:bluetooth.
kotlin {
    configureOptIns()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs { browser() }
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.core.bluetooth)
                api(projects.core.model)

                implementation(libs.arbor)
                implementation(libs.kotlincrypto.hash.sha2)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}
