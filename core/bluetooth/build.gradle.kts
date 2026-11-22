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
        commonMain {
            dependencies {
                implementation(libs.blue.falcon)
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.core.protocol)
                implementation(projects.core.model)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.kotlinx.serialization.cbor)
                implementation(libs.arbor)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // The EyeStatus golden test reads the JSON test data from the Configuration-Protocol
        // submodule.
        jvmTest {
            resources.srcDir(layout.projectDirectory.dir("../protocol/builder/Configuration-Protocol/testdata"))
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
