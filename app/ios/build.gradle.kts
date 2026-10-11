import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * Whether the framework carries the simulated board of `core:fake-board`.
 *
 * A Kotlin source set cannot vary by the binary build type — one compilation feeds both the debug
 * and the release framework — so the switch has to be a project-level flag. Xcode sets CONFIGURATION
 * on every build it drives, the shipping Release archive included, so that is the flag: a Release
 * configuration compiles neither the simulated board nor the source set that binds it, exactly as
 * `debugImplementation` keeps both out of the Android release APK.
 *
 * A build with no CONFIGURATION set — a CLI or CI compile — takes the debug side, which keeps that
 * wiring under CI. No such build produces the framework that ships.
 */
val simulatedBoard: Boolean = providers.environmentVariable("CONFIGURATION")
    .map { configuration -> !configuration.equals("Release", ignoreCase = true) }
    .getOrElse(true)

kotlin {
    configureOptIns()
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    sourceSets {
        iosMain {
            kotlin.srcDir(if (simulatedBoard) "src/iosDebug/kotlin" else "src/iosRelease/kotlin")
            dependencies {
                // core:fake-board exposes the seam through `api`, so the debug side gets both.
                if (simulatedBoard) {
                    implementation(projects.core.fakeBoard)
                } else {
                    implementation(projects.core.debugBoard)
                }
                implementation(projects.core.common)
                implementation(projects.core.di)
                implementation(projects.core.bluetooth)
                implementation(projects.core.media)
                implementation(projects.core.model)
                implementation(projects.core.resources)
                implementation(projects.core.store)
                implementation(projects.platform.ios.bluetooth)
                implementation(projects.platform.ios.di)

                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.navigation3.ui)
                implementation(libs.androidx.navigation3.viewmodel)
                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.kodein)
                implementation(libs.kodein.compose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
    }
}
