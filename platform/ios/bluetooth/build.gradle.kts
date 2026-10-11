import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    configureOptIns()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        iosMain {
            dependencies {
                implementation(projects.core.bluetooth)
                implementation(projects.core.protocol)
                implementation(projects.core.model)
                implementation(libs.kotlinx.datetime)

                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.blue.falcon.ios)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
            }
        }
    }
}
