@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    configureOptIns(serialization = false)
    wasmJs { browser() }
    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(projects.platform.browser.bluetooth)
                implementation(projects.core.bluetooth)

                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.blue.falcon.js)
                implementation(libs.kodein)
            }
        }
    }
}