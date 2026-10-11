@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.snapshotpb.composite.util.configureOptIns
import com.snapshotpb.composite.util.projectVersionCode

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The resolved version reaches shared code only through generated source: `project.version` is a
// Gradle-side value, and commonMain must stay platform-agnostic (so no BuildConfig, which is
// Android-only). Mirrors the generateDesktopConfig task in app/desktop/build.gradle.kts.
//
// WHAT IS STAMPED, AND WHY IT IS NOT THE WHOLE STRING
//
// `git describe` resolves a new value on every commit, but only the part after `+` is volatile:
// parseGitDescribe() puts commits-since / sha / dirty exclusively in SemVer build metadata. Baking
// that into compiled code made this file — and so core:common and everything downstream of it —
// change on every commit, which on CI (where every run carries a fresh sha) is a guaranteed cache
// miss on the largest module in the build, on all five targets.
//
// Dropping the metadata leaves a release exact (`26.30.1`, no `+` to strip) and a dev build stable
// between commits (`26.30.1-dev`). That still satisfies the plan's goal #5 — dev and release builds
// are unambiguously distinguishable — because the `-dev` marker is what carries that distinction.
// The full string including the sha is unaffected everywhere it is already recorded: Android's
// versionName, the desktop packageVersion, and every published release artifact. Pass
// -Psnapshotpb.stampBuildMetadata=true to force it into this in-app surface too.
//
// This has to be fixed by holding the VALUE steady rather than by module structure: Kotlin/Native
// and Kotlin/Wasm consume dependencies as opaque klibs with no ABI normalisation, so a dependent's
// compile task re-runs whenever the dependency's klib changes at all — neither a `val` instead of a
// `const val` nor a module boundary avoids it on those targets. Both of those are still applied
// below, because they do work on JVM/Android and they bound the blast radius on the rare builds
// where this value legitimately changes (a tag being cut, or a release build).
val stampBuildMetadata = providers.gradleProperty("snapshotpb.stampBuildMetadata")
    .map(String::toBoolean)
    .getOrElse(false)

val generateAppVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/appVersion")
    val fullVersion = project.version.toString()
    val versionName = if (stampBuildMetadata) fullVersion else fullVersion.substringBefore('+')
    val versionCode = projectVersionCode(fullVersion)
    outputs.dir(outputDir)
    inputs.property("versionName", versionName)
    inputs.property("versionCode", versionCode)
    doLast {
        // `git describe` output is a tag or a hex sha in practice, but this string is pasted into
        // Kotlin source, so escape the three characters that would otherwise produce a file that
        // does not compile: a backslash, a quote, and `$` (which would start a string template).
        val literal = versionName
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\\$")
        val file = outputDir.get()
            .file("com/snapshotpb/configuration/version/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.snapshotpb.configuration.version

            /**
             * Build-time version stamp — GENERATED, do not edit.
             *
             * [NAME] is clean on a release (`26.30.1`) and carries the `-dev` marker otherwise
             * (docs/versioning-and-release-plan.md §4); [CODE] is the store integer. The
             * `+<commits>-g<sha>` build metadata is omitted so that this value — and therefore
             * every module compiled against it — stays stable between commits; build with
             * `-Psnapshotpb.stampBuildMetadata=true` to include it.
             *
             * These are deliberately NOT `const`. A `const val` is inlined into every call site and
             * so forms part of this module's ABI, which means a dependent's compiled output is
             * invalidated whenever the value changes. A plain `val` is read through a getter and
             * leaves the ABI byte-identical, so Gradle can skip recompiling JVM/Android dependents
             * when a new tag moves this string. Do not add `const`.
             */
            object AppVersion {
                val NAME: String = "$literal"
                val CODE: Int = $versionCode
            }

            """.trimIndent()
        )
    }
}

kotlin {
    configureOptIns(serialization = false)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    wasmJs { browser() }
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateAppVersion)
        }
    }
}
