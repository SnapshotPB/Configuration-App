import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    configureOptIns(serialization = false)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        val commonMain by getting {
            dependencies {
                // The seam's own signatures carry BluetoothRequest and BluetoothDeviceState, so
                // every consumer sees them: api, not implementation.
                api(projects.core.bluetooth)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
