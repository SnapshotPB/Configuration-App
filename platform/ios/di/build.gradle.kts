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
                implementation(projects.platform.ios.bluetooth)
                implementation(projects.core.bluetooth)
                implementation(projects.core.firmware)

                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.blue.falcon.ios)
                implementation(libs.kodein)
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}
