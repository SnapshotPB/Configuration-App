rootProject.name = "SnapshotPB"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    // `composite` is consumed as a pre-published jar from composite/m2, not with
    // includeBuild, because includeBuild breaks the build cache that CI depends on. The cost
    // of that choice is that editing composite/src and forgetting to republish leaves a
    // stale jar in place with no message.
    //
    // Asking for a version that carries a digest of the build logic removes that failure
    // mode: the requested coordinate only exists in composite/m2 if someone published the
    // current source, so a forgotten republish fails at once with "plugin not found".
    //
    // Keep buildLogicVersion() identical to the copy in composite/build.gradle.kts. Only the
    // indentation and the `java.security.` prefix are different. BuildLogicVersionParityTest in composite compares the copies.
    fun buildLogicVersion(root: File): String {
        val sources = listOf("build.gradle.kts", "gradle.properties", "settings.gradle.kts")
            .map { name -> root.resolve(name) } +
            root.resolve("src/main").walkTopDown().filter { file -> file.isFile }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        sources.filter { file -> file.exists() }
            .sortedBy { file -> file.toRelativeString(root).replace(File.separatorChar, '/') }
            .forEach { file ->
                digest.update(
                    file.toRelativeString(root).replace(File.separatorChar, '/').toByteArray()
                )
                digest.update(0)
                digest.update(file.readBytes())
            }
        return "1.0-" + digest.digest().joinToString("") { value: Byte -> "%02x".format(value) }.take(12)
    }

    val compositeVersion = buildLogicVersion(File(rootDir, "composite"))

    repositories {
        maven { url = uri("composite/m2") }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // The version in gradle/libs.versions.toml is a placeholder; the digest decides.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "composite") useVersion(compositeVersion)
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.weliem.blessed-bluez")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.5.1"
}

// Build scans go to the public scans.gradle.com server, and this repository is private. A scan
// is therefore published only for a failed build on CI, where it helps to find the cause.
// A local build never publishes a scan. validation.yml does not pass --scan for this reason.
develocity {
    buildScan {
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
        publishing.onlyIf { context ->
            System.getenv("CI") == "true" && context.buildResult.failures.isNotEmpty()
        }
    }
}

include(
    ":app:android",
    ":app:android:baseline",
    ":app:browser",
    ":app:desktop",
    ":app:ios",
    ":app:wear",
    ":core:app-version",
    ":core:bluetooth",
    ":core:common",
    ":core:debug-board",
    ":core:di",
    ":core:fake-board",
    ":core:firmware",
    ":core:media",
    ":core:model",
    ":core:protocol",
    ":core:protocol:builder",
    ":core:resources",
    ":core:store",
    ":core:util",
    ":platform:android:bluetooth",
    ":platform:android:di",
    ":platform:android:media",
    ":platform:android:store",
    ":platform:browser:bluetooth",
    ":platform:browser:di",
    ":platform:desktop:bluetooth",
    ":platform:desktop:di",
    ":platform:ios:bluetooth",
    ":platform:ios:di",
)
