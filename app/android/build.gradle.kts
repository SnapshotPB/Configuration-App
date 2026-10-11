import com.snapshotpb.composite.util.configureOptIns
import com.snapshotpb.composite.util.projectVersionCode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.androidBaseline)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.snapshotpb.configuration"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.snapshotpb.configuration"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Driven from the resolved project version (docs/versioning-and-release-plan.md).
        versionCode = projectVersionCode(project.version.toString())
        versionName = project.version.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Upload-keystore signing, wired dormant (docs/versioning-and-release-plan.md §7): the
    // config only materialises when the release job has decoded a keystore into the
    // environment, so local, PR and CI dev builds are completely unaffected. Mirrors the
    // MSI_SIGNING_STORE_FILE idiom in app/desktop/build.gradle.kts.
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
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Absent without the keystore env, leaving the artifact unsigned — which is
            // fine for the QA artifacts on the GitHub Release, and is exactly why the Play
            // upload job is gated on the same secret.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }

        create("benchmark") {
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        htmlReport = true
        // The full lint runs on the debug variant through the `lint` task. The lint vital pass
        // on each release-type variant analyzed the same sources again, so it is off.
        checkReleaseBuilds = false
    }

    sourceSets {
        getByName("main").res.directories.add(
            project.layout.buildDirectory.dir("copiedRes").get().asFile.path
        )
        // DebugBoard is bound per build type: src/debug imports core:fake-board's module,
        // src/release binds the inert one. The benchmark type is a release build, so it takes the
        // release copy rather than a third copy of the same three lines.
        getByName("benchmark").kotlin.directories.add("src/release/kotlin")
    }
}

kotlin {
    configureOptIns()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

baselineProfile {
    mergeIntoMain = true
}

dependencies {
    implementation(projects.core.bluetooth)
    implementation(projects.core.common)
    implementation(projects.core.debugBoard)
    implementation(projects.core.di)
    implementation(projects.core.media)
    implementation(projects.core.model)
    implementation(projects.core.protocol)
    implementation(projects.core.resources)
    implementation(projects.core.store)
    implementation(projects.platform.android.bluetooth)
    implementation(projects.platform.android.di)
    implementation(projects.platform.android.media)
    implementation(projects.platform.android.store)
    implementation(libs.play.services.wearable)

    // The simulated board reaches the debug APK only. A release build has no compile-time access
    // to it at all, so the guarantee holds without relying on R8 to strip an unreferenced class.
    debugImplementation(projects.core.fakeBoard)


    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splash)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.material)
    implementation(libs.arbor)
    implementation(libs.arbor.android)
    implementation(libs.blue.falcon)
    implementation(libs.blue.falcon.android)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kodein.compose.runtime)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.io)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.androidx.espresso.core)
    testImplementation(libs.androidx.testExt.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)

    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // The connect-reliability harness drives BlueFalcon directly; androidTest only inherits `api`
    // deps from the app, so the BlueFalcon artifacts must be requested explicitly here.
    androidTestImplementation(libs.blue.falcon)
    androidTestImplementation(libs.blue.falcon.android)

    baselineProfile(projects.app.android.baseline)
}

tasks {

    // TODO if compose resources, now or in the future, supports reading strings through the
    //  Android R interface, remove this in preference of that.
    // Only the base locale is copied, and only the manifest android:label still reads it.
    // Code reads every string through Compose Resources, which resolves the locale itself,
    // so a translated values-* directory must not be mirrored into the Android res.
    val copyStringResTask = register("copyStringRes", Copy::class.java) {
        val sourceSourceSet = "src/commonMain/composeResources/values"
        val destSourceSet = "copiedRes/values"
        val resourcesProject = project(project.projects.core.resources.path)
        from(resourcesProject.layout.projectDirectory.dir(sourceSourceSet))
        // The copy lands under build/, and clean deletes build/ as a whole. A clean.doLast that
        // deleted it again called Task.project at execution time, which the configuration cache
        // does not support.
        into(project.layout.buildDirectory.dir(destSourceSet))
    }
    getByName("preBuild").dependsOn(copyStringResTask)
}
