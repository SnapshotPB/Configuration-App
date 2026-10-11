@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    configureOptIns()
    wasmJs { browser() }
    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(projects.core.bluetooth)
                implementation(projects.core.protocol)
                implementation(projects.core.model)
                implementation(libs.kotlinx.datetime)

                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.blue.falcon.js)
                implementation(libs.kotlinx.browser)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        val wasmJsTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}