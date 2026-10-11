import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.snapshotpb.configuration.bluetooth.di"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    configureOptIns(serialization = false)
}

dependencies {
    implementation(projects.core.bluetooth)
    implementation(projects.core.media)
    implementation(projects.core.store)
    implementation(projects.platform.android.bluetooth)
    implementation(projects.platform.android.media)

    implementation(libs.blue.falcon)
    implementation(libs.blue.falcon.android)
    implementation(libs.blue.falcon.logging)
    implementation(libs.kodein)
}
