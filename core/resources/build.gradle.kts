@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.task.LocalizationCheckTask
import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    configureOptIns()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs { browser() }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.ui)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.snapshotpb.core.resources"
    generateResClass = auto
}

val composeResourcesDir = layout.projectDirectory.dir("src/commonMain/composeResources")

val localizationCheck = tasks.register<LocalizationCheckTask>("localizationCheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compares each translated strings.xml with the base locale."
    baseFile = composeResourcesDir.file("values/strings.xml")
    translationFiles.from(
        fileTree(composeResourcesDir) {
            include("values-*/strings.xml")
        }
    )
    // Every locale that the app ships. A locale that has no directory produces no file, so
    // only this list can report it.
    expectedLocales = setOf(
        "values-de",
        "values-es",
        "values-es-rMX",
        "values-es-rUS",
        "values-fr",
        "values-fr-rCA"
    )
    // Strings the task must not report as untranslated. A bare name covers every locale and
    // suits a brand name or a term the app keeps in English everywhere. A `<locale>:<name>`
    // entry covers one locale and suits a word that happens to be spelled the same in that
    // language. Prefer the scoped form: `updater_error` is `Error` in Spanish but `Fehler` in
    // German, so a bare entry would hide a real German gap. A value that holds only format
    // specifiers or punctuation needs no entry, because the task skips it already.
    untranslatedAllowList = setOf(
        "app_name",
        "app_version_label",
        "bluetooth_needs_permission_title",
        "board_screen_off_delay_seconds",
        "device_default_name",
        // The unit symbols `ms`, `%`, `BPS` and `×` are the same in every locale.
        "profile_value_ms",
        "profile_value_bps",
        "profile_value_multiple",
        "profile_unit_ms",
        "profile_unit_percent",
        "profile_unit_bps",
        "profile_unit_multiple",
        "profile_fire_mode_semi",
        // `NXL 2026` is the name of a rule set of the league. The board menu also shows it
        // unchanged in each language.
        "profile_fire_mode_ramping_nxl_2026",
        "profile_section_ramping",
        "profile_timing_diagram_lane_sear",
        // German keeps these English loanwords, and `Name` is the same word in German.
        "values-de:profile_label_name",
        "values-de:profile_section_timing",
        "values-de:profile_timing_diagram_lane_cocking",
        // `Services` is the same word in French.
        "values-fr:wear_step_services",
        "values-fr-rCA:wear_step_services",
        // `Error` is the same word in Spanish.
        "values-es:updater_error",
        "values-es-rMX:updater_error",
        "values-es-rUS:updater_error"
    )
    reportFile = layout.buildDirectory.file("reports/localization/strings.txt")
}

tasks.named("check") {
    dependsOn(localizationCheck)
}
