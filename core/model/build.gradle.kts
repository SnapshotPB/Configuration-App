@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    configureOptIns()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs { browser() }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.core.util)

                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        // The parity tests of MarkerProfileField and MarkerProfile read the JSON fixtures in
        // src/jvmTest/resources.
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
