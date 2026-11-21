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
        commonMain {
            dependencies {
                api(projects.core.debugBoard)
                implementation(projects.core.bluetooth)
                implementation(projects.core.common)
                implementation(projects.core.firmware)
                implementation(projects.core.model)
                implementation(projects.core.protocol)
                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.kodein)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
