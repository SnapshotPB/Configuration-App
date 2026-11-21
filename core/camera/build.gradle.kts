import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    configureOptIns(serialization = false)
    android {
        namespace = "com.snapshotpb.configuration.camera"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.core.common)
                implementation(projects.core.model)
                implementation(projects.core.resources)

                implementation(libs.arbor)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.runtime)
                implementation(libs.image.picker)
                implementation(libs.kodein)
            }
        }
    }
}
