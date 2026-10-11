import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.snapshotpb.configuration.media"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

kotlin {
    configureOptIns()
}

dependencies {
    implementation(projects.core.media)

    implementation(libs.androidx.activity.compose)
    implementation(libs.arbor)
}
