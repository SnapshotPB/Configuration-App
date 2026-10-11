@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    configureOptIns()
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "snapshotPbBrowser.js"
            }
        }
        binaries.executable()
    }
    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(projects.core.common)
                implementation(projects.core.di)
                implementation(projects.core.bluetooth)
                implementation(projects.core.media)
                implementation(projects.core.model)
                implementation(projects.core.resources)
                implementation(projects.core.store)
                implementation(projects.platform.browser.bluetooth)
                implementation(projects.platform.browser.di)

                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.navigation3.ui)
                implementation(libs.androidx.navigation3.viewmodel)
                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.kodein)
                implementation(libs.kodein.compose)
                implementation(libs.kotlinx.browser)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
    }
}