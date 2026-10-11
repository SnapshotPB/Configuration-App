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
            kotlin.srcDir(
                project(projects.core.protocol.builder.path)
                    .layout
                    .buildDirectory
                    .dir("generated/sources/proto/main/kotlinx-protobuf-gen")
            )
            dependencies {
                implementation(projects.core.util)

                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.protobuf)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// The `commonMain` srcDir above points into :core:protocol:builder's build dir but a bare
// `buildDirectory.dir(...)` provider carries no producer-task association, so nothing tells the
// Kotlin compilations that they read generated proto sources; the iOS/wasm/metadata compilations of
// the shared srcDir would otherwise race the codegen under parallel/configuration-cache builds.
// Make every Kotlin compilation here depend on the builder's `generateProto` task explicitly.
//
// This ordering is the ONLY thing the builder project is needed for here. There used to also be a
// `commonMainCompileOnly(projects.core.protocol.builder)` for the same purpose, but it contributed
// nothing to any compile classpath — the builder is a `java-library` with the `java` protoc builtin
// removed and no Kotlin plugin, so its jar holds none of the protocol types (those arrive as
// *sources* through the srcDir above). Meanwhile the Kotlin plugin reports `compileOnly` as
// unsupported on Kotlin/Native and Kotlin/Wasm ("compileOnly dependencies must be present during
// the compilation of projects that depend on this project"), which this module targets. Dropping it
// removes that unsupported usage and changes nothing about what is compiled.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(":core:protocol:builder:generateProto")
}
