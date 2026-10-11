import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.snapshotpb.configuration.store"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    configureOptIns(serialization = false)
}

dependencies {
    implementation(projects.core.store)
    implementation(libs.kotlinx.io)
    implementation(libs.arbor)
}
