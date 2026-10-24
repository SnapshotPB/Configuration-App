import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    configureOptIns(serialization = false)
    jvm()
    sourceSets {
        jvmMain {
            dependencies {
                implementation(projects.core.bluetooth)
                implementation(projects.core.firmware)
                implementation(projects.core.store)
                implementation(projects.platform.desktop.bluetooth)

                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.blue.falcon.macos.jvm)
                implementation(libs.blue.falcon.linux)
                implementation(libs.blue.falcon.windows)
                implementation(libs.kodein)
                implementation(libs.ktor.client.cio)
            }
        }
    }
}