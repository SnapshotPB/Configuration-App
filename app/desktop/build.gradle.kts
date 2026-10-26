import com.snapshotpb.composite.util.configureOptIns
import com.snapshotpb.composite.util.packagingVersion
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.reload.gradle.ComposeHotRun

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.nucleus)
}

val generateDesktopConfig by tasks.registering {
    val outputFile = layout.buildDirectory.file("generated/desktopConfig/com/snapshotpb/configuration/DesktopConfig.kt")
    val enableAutoUpdater = project.findProperty("snapshotpb.enableAutoUpdater")?.toString()?.toBoolean() ?: false
    outputs.file(outputFile)
    inputs.property("enableAutoUpdater", enableAutoUpdater)
    doLast {
        outputFile.get().asFile.parentFile.mkdirs()
        outputFile.get().asFile.writeText("""
            package com.snapshotpb.configuration

            object DesktopConfig {
                const val ENABLE_AUTO_UPDATER = $enableAutoUpdater
            }
        """.trimIndent())
    }
}

kotlin {
    configureOptIns()
    jvm()
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.core.bluetooth)
                implementation(projects.core.camera)
                implementation(projects.core.common)
                implementation(projects.core.di)
                implementation(projects.core.firmware)
                implementation(projects.core.media)
                implementation(projects.core.model)
                implementation(projects.core.protocol)
                // Shared strings (public Res class), used by the updater dialog.
                implementation(projects.core.resources)
                implementation(projects.core.store)

                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.compose.components.resources)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.arbor)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.kodein.compose.runtime)
            }
        }
        jvmMain {
            kotlin.srcDir(generateDesktopConfig.map { it.outputs.files.singleFile.parentFile.parentFile.parentFile.parentFile })
            dependencies {
                implementation(projects.platform.desktop.bluetooth)
                implementation(projects.platform.desktop.di)

                implementation(compose.desktop.currentOs)
                implementation(libs.blue.falcon)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.datetime)
                implementation(libs.nucleus.updater)
            }
        }
    }
}

val desktopMainClass = "com.snapshotpb.configuration.MainKt"

// Compose Hot Reload works only on the desktop JVM, so this is the only module that applies
// the plugin. The plugin also configures the Compose modules that this app depends on (for
// example core:common and core:resources), so those modules do not apply it.
// Nucleus, not the Compose desktop application DSL, holds the main class, so the plugin cannot
// find it. Start the app with `./gradlew :app:desktop:hotRunJvm`.
tasks.withType<ComposeHotRun>().configureEach {
    mainClass = desktopMainClass
}

nucleus.application {
    mainClass = desktopMainClass

    nativeDistributions {
        packageName = "Snapshot PB"
        // The native packagers validate this at CONFIGURATION time and reject anything
        // non-numeric, so the `-dev+<sha>` marker must be stripped here or every local and
        // PR build fails at `./gradlew help`. The marker still reaches project.version and
        // Android's versionName; see packagingVersion() for the full rationale.
        packageVersion = packagingVersion(project.version.toString())
        description = "The Snapshot PB board configuration application."
        copyright = "© 2026 Snapshot PB"
        vendor = "Snapshot PB"

        // AppImage (not Flatpak) for Linux: the Nucleus in-app updater's file selector
        // recognizes .appimage but NOT .flatpak, so Linux must ship AppImage to be
        // self-updatable from GitHub Releases (docs/versioning-and-release-plan.md §6.1,
        // §10). AppImage here is the electron-builder format that also emits the
        // latest-linux.yml update manifest — not RawAppImage (jpackage). Flatpak/Flathub,
        // if wanted later, is a separate channel that self-updates via OSTree.
        // TargetFormat.Zip is added only when packaging ON macOS. The Nucleus updater can
        // silently install-and-restart from a macOS .zip, but for a .dmg it can only `open`
        // the file and exit — leaving the user to drag the app across by hand. Unlike Dmg
        // (targetOS = MacOS), Zip is OS-agnostic — it has no targetOS at all — so without
        // this guard every runner would emit a zip, adding a redundant Windows/Linux asset
        // to the Release and a second `files:` entry the updater could select over the real
        // installer. The guard keeps the zip a macOS-only artifact.
        targetFormats(
            *buildList {
                add(TargetFormat.Dmg)
                add(TargetFormat.Msi)
                add(TargetFormat.AppImage)
                if (System.getProperty("os.name").startsWith("Mac")) add(TargetFormat.Zip)
            }.toTypedArray()
        )

        // Generates the electron-builder-style latest*.yml update manifests that the in-app
        // Nucleus updater reads from the GitHub Release (plan §10). Without this block the
        // plugin only emits a manifest for Msi/Portable, so macOS and Linux would ship a
        // Release that looks healthy but can never self-update.
        //
        // publishMode stays at its Never default: this configures manifest GENERATION only.
        // Uploading remains the release workflow's job (softprops/action-gh-release), so no
        // token is needed here. owner/repo must stay in lockstep with the GitHubProvider in
        // Application.kt — the updater resolves assets as
        // https://github.com/<owner>/<repo>/releases/download/v<version>/<file>.
        publish {
            github {
                enabled = true
                owner = "SnapshotPB"
                repo = "Configuration-App"
            }
        }

        windows {
            iconFile.set(project.file("icon/icon.ico"))
            
            // TODO: Research sigstore viability for managing code signing in the future.
            // Currently, signing is deferred until a certificate/key is obtained. The release
            // workflow decodes the MSI_SIGNING_PFX_BASE64 secret into a .pfx file and sets
            // MSI_SIGNING_STORE_FILE to its path. Without that secret, the MSI is unsigned.
            val pfxPath = System.getenv("MSI_SIGNING_STORE_FILE")
            if (!pfxPath.isNullOrBlank()) {
                signing {
                    certificateFile = project.file(pfxPath)
                    certificatePassword = System.getenv("MSI_SIGNING_STORE_PASS")
                }
            }
        }
        macOS {
            iconFile.set(project.file("icon/icon.icns"))

            // An explicit reverse-DNS bundle identifier. Left unset, the plugin derives one
            // from packageName ("Snapshot PB"), which contains a space and is therefore not
            // a legal bundle ID — codesign and notarization both reject that, so macOS
            // signing could never be switched on. Matches the Android applicationId.
            bundleID = "com.snapshotpb.configuration"

            // NOTE: macOS Developer ID signing + notarization are NOT configured here.
            // The plugin already reads them from Gradle properties —
            //   compose.desktop.mac.sign / .signing.identity / .signing.keychain
            //   compose.desktop.mac.notarization.{appleID,password,teamID}
            //     (or .{apiKey,apiKeyId,apiIssuer} for an App Store Connect key)
            // — so the release workflow supplies them from secrets by writing a runner-local
            // gradle.properties, and they stay absent (hence unsigned) everywhere else (§7).
            // Do not add a signing {} / notarization {} block here: hardcoding it would take
            // precedence over those properties and break the dormant-until-secrets-exist
            // posture. Signing must also happen DURING packaging, not after — notarization
            // staples a ticket into the .dmg/.zip, changing bytes the update manifest hashes.
        }
        linux {
            // A PNG, not the SVG source: on Linux, jpackage reads only a PNG. The jpackage of
            // JDK 21 ignores an SVG and gives the app its default 32 x 32 icon. The jpackage
            // of JDK 25 stops with an error. icon.png is a 512 x 512 render of icon.svg. That
            // is the largest size of the freedesktop icon theme, and the AppImage step makes
            // the smaller icons (16 to 256) from it. To make the file again, run this command
            // in app/desktop:
            //   inkscape icon/icon.svg --export-type=png --export-area-page \
            //     --export-width=512 --export-height=512 --export-filename=icon/icon.png
            iconFile.set(project.file("icon/icon.png"))
        }
    }
}

