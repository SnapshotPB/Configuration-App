import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.snapshotpb.configuration.bluetooth"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    configureOptIns()
}

dependencies {
    implementation(projects.core.bluetooth)
    implementation(projects.core.protocol)
    implementation(projects.core.model)
    implementation(libs.kotlinx.datetime)

    implementation(libs.blue.falcon)
    implementation(libs.blue.falcon.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.arbor)
}
