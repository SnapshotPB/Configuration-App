import java.security.MessageDigest

plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
    `maven-publish`
}

group = "com.snapshotpb.composite"

// The published version carries a digest of this build logic, so the coordinate changes
// whenever the source does. The root settings.gradle.kts computes the same digest and asks
// for exactly that version, which turns a forgotten republish into an immediate
// "plugin not found" instead of a jar that is silently out of date.
//
// Keep buildLogicVersion() identical to the copy in the root settings.gradle.kts. Only the
// indentation and the `java.security.` prefix are different: in a project script, `java`
// is the Java plugin extension, so this copy imports MessageDigest. The two cannot share a
// file, because this build produces the plugin that the root build consumes.
// BuildLogicVersionParityTest compares the two copies.
// A divergence also stops the root build at once, because the versions do not agree.
//
// The digest covers build.gradle.kts, gradle.properties, settings.gradle.kts and src/main.
// It does not cover src/test: a test-only edit does not change the jar, so it must not give
// every consumer a new coordinate. It also does not cover gradle/libs.versions.toml, so a
// toolchain or dependency bump still needs a manual republish.
version = buildLogicVersion(layout.projectDirectory.asFile)

fun buildLogicVersion(root: File): String {
    val sources = listOf("build.gradle.kts", "gradle.properties", "settings.gradle.kts")
        .map { name -> root.resolve(name) } +
        root.resolve("src/main").walkTopDown().filter { file -> file.isFile }
    val digest = MessageDigest.getInstance("SHA-256")
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

gradlePlugin {
    website = "https://github.com/SnapshotPB/Configuration-App"
    vcsUrl = "https://github.com/SnapshotPB/Configuration-App.git"
    plugins {
        register("composite") {
            id = "composite"
            displayName = "Composite Plugin"
            implementationClass = "com.snapshotpb.composite.CompositePlugin"
        }
    }
}

dependencies {
    // compileOnly, so that this plugin does not put its own Kotlin Gradle plugin version on
    // the classpath of the root build. The root build.gradle.kts declares the Kotlin plugins
    // and `composite` in one plugins {} block, so they load in the same class loader, and
    // configureOptIns() finds the KGP classes of the root build at runtime.
    compileOnly(libs.kotlin.gradle)
    testImplementation(libs.kotlin.test)
}

// The version mapping is release-critical and easy to get subtly wrong (jpackage rejects a
// suffix, MSI caps the major at 255, Play needs a monotonic int), so it is unit-tested here
// — the Kotlin-side counterpart to scripts/tests/test_cut_version.py.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven(uri("m2")) {
            name = "m2"
        }
    }
}

// Because the version carries a digest, every edit would otherwise leave the previous
// version behind and `m2` would grow without limit. Emptying the repository first keeps
// exactly one version in the tree, which also keeps the committed diff readable.
val clearM2 = tasks.register<Delete>("clearM2") {
    delete(layout.projectDirectory.dir("m2"))
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(clearM2)
}
