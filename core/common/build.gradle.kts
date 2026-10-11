@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    configureOptIns()
    android {
        namespace = "com.snapshotpb.configuration.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs { browser() }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.core.appVersion)
                implementation(projects.core.bluetooth)
                implementation(projects.core.model)
                implementation(projects.core.resources)
                implementation(projects.core.store)
                implementation(projects.core.util)

                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.androidx.navigation3.ui)
                implementation(libs.androidx.navigation3.viewmodel)
                implementation(libs.arbor)
                implementation(libs.coil)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.kodein.compose)
                implementation(libs.kotlinx.datetime)
            }
        }
        // imagepickerkmp publishes a wasmJs variant, but it is a stub without the
        // ImagePickerLauncher / PhotoResult API this module uses, so it can only live on the
        // non-web targets. Photo capture is implemented here and stubbed out on wasm via the
        // expect/actual `MediaSelection`.
        val nonWasmMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.image.picker)
            }
        }
        // Every non-wasm leaf target must depend on nonWasmMain so it gets the real (imagepickerkmp)
        // MediaSelection actual. Wired per-leaf rather than via the default `iosMain` intermediate
        // because that intermediate isn't resolvable here at configuration time. NOTE: any new
        // non-web target (e.g. iosX64, a macOS target) must be added to this list or its build will
        // fail with a missing `MediaSelection` actual.
        getByName("jvmMain").dependsOn(nonWasmMain)
        getByName("androidMain").dependsOn(nonWasmMain)
        getByName("iosArm64Main").dependsOn(nonWasmMain)
        getByName("iosSimulatorArm64Main").dependsOn(nonWasmMain)
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // The in-memory StoreIOProvider used by these tests implements an interface whose
                // signatures are kotlinx-io types, and it seeds the store with JSON; both are
                // `implementation` details of core:store, so they are not on this classpath by
                // default.
                implementation(libs.kotlinx.io)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

// DefaultDeviceNameTest reads the strings.xml files of core:resources. Declare them as an input,
// or the build cache keeps an old result after a change to a string.
tasks.named<Test>("jvmTest") {
    inputs.dir(layout.projectDirectory.dir("../resources/src/commonMain/composeResources"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("composeResources")
}
