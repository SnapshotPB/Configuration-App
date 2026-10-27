import com.snapshotpb.composite.util.configureOptIns
import com.snapshotpb.composite.util.wearVersionCode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.snapshotpb.configuration"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Matches :app:android so Google Play Services' Wearable Data Layer (DataClient /
        // WearableListenerService) delivers phone <-> watch sync events, and both AABs
        // publish under the single com.snapshotpb.configuration Play listing.
        applicationId = "com.snapshotpb.configuration"
        minSdk = 30 // Wear OS 3+ requires API 30 (Android 11)
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Driven from the resolved root project version with the Wear form-factor offset
        // (docs/versioning-and-release-plan.md §3) so phone and watch AABs do not collide
        // on versionCode in the shared Play listing.
        versionCode = wearVersionCode(rootProject.version.toString())
        versionName = rootProject.version.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Same dormant upload-keystore wiring as app/android (plan §7) — phone and Wear share
    // the com.snapshotpb.configuration Play listing and upload key.
    val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // The full lint runs on the debug variant through the `lint` task. The lint vital pass
        // on the release variant analyzed the same sources again, so it is off.
        checkReleaseBuilds = false
    }
}

kotlin {
    configureOptIns()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    // Core shared modules
    implementation(projects.core.common)
    implementation(projects.core.bluetooth)
    implementation(projects.core.model)
    implementation(projects.core.protocol)
    implementation(projects.core.store)
    implementation(projects.core.di)
    // Shared strings (public Res class) read via Compose Resources
    implementation(projects.core.resources)
    // Shared tenths-of-a-millisecond formatting, so the watch renders timings exactly as the phone does
    implementation(projects.core.util)

    // Android platform implementation modules
    implementation(projects.platform.android.bluetooth)
    implementation(projects.platform.android.di)
    implementation(projects.platform.android.store)

    // Wear OS UI & Play Services dependencies
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.play.services.wearable)
    
    // Dependency Injection (Kodein)
    implementation(libs.kodein)
    implementation(libs.kodein.compose)
    implementation(libs.kodein.compose.runtime)

    // Core Android/Compose dependencies
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.arbor)
    implementation(libs.arbor.android)
    implementation(libs.blue.falcon)
    implementation(libs.blue.falcon.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.compose.components.resources)
    implementation(libs.coil)

    // Testing dependencies
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
