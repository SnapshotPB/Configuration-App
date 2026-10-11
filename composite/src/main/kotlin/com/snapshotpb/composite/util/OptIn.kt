package com.snapshotpb.composite.util

import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// The rule: every module that compiles Kotlin calls configureOptIns() in its kotlin {} block.
// Do not put @OptIn for these markers at call sites.
//
// The compiler warns about an opt-in marker that is not on the compile classpath. The two stdlib
// markers are always there. The kotlinx-serialization marker is there only when the module has
// kotlinx-serialization, directly or through an api dependency. A module without it passes
// serialization = false.

/**
 * Opts in to the experimental APIs that this project uses. See the rule at the top of this file.
 *
 * @param serialization Set to false when kotlinx-serialization is not on the compile classpath
 *   of the module. The compiler then does not warn that the marker is unresolved.
 */
fun KotlinMultiplatformExtension.configureOptIns(serialization: Boolean = true) {
    compilerOptions { optIn.set(optInValues(serialization)) }
}

/** The Android form of [KotlinMultiplatformExtension.configureOptIns]. */
fun KotlinAndroidProjectExtension.configureOptIns(serialization: Boolean = true) {
    compilerOptions { optIn.set(optInValues(serialization)) }
}

internal fun optInValues(serialization: Boolean): List<String> =
    if (serialization) stdlibOptIns + SERIALIZATION_OPT_IN else stdlibOptIns

private val stdlibOptIns = listOf(
    "kotlin.ExperimentalUnsignedTypes",
    "kotlin.uuid.ExperimentalUuidApi",
)

private const val SERIALIZATION_OPT_IN = "kotlinx.serialization.ExperimentalSerializationApi"
