import com.snapshotpb.composite.util.resolveProjectVersion

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidBaseline) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composite) apply false
    alias(libs.plugins.nucleus) apply false
}

// Single calendar-derived version, resolved once from `git describe`
// (see docs/versioning-and-release-plan.md and composite util resolveProjectVersion()).
// Release builds (checked out on a tag) get a clean `26.29.1`; dev/PR/master builds
// get a `-dev` marker.
//
// Only the root project has the version. The modules that need it read `rootProject.version`:
// the Android and Wear apps, the desktop package, and core:app-version. A library module keeps
// the default version "unspecified", on purpose. The version changes at each commit, and it is
// an input of two cacheable tasks of each module: the Kotlin test compile has the jar of the
// main compilation in its friend paths, and the jar name holds the version; the lint analysis
// has the input `projectInputs.mavenVersion`. With the version on each module, each commit made
// all test compiles and all lint tasks run again.
version = resolveProjectVersion()