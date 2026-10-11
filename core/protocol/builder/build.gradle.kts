import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.remove

// This module does not call configureOptIns(): it applies no Kotlin plugin and compiles no Kotlin.
// It only generates the protocol sources, and core:protocol compiles them with the central opt-ins.
plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

sourceSets {
    main {
        proto {
            // Import root: the new schema uses path-qualified imports (e.g.
            // `import "snapshotpb/v1/device.proto"`), so the source dir must be the
            // submodule root for those to resolve. The plugin globs all *.proto beneath it.
            srcDir("Configuration-Protocol")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobufSchema.get()}"
    }

    plugins {
        id("kotlinx-protobuf-gen") {
            artifact = "${libs.protobuf.kotlin.gen.get()}:jvm8@jar"
        }
    }

    // Enable Kotlin generation
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                remove("java") // Optionally you can keep the java generated files.
            }
            task.plugins {
                // No `package_prefix`: the proto files now declare their own packages
                // (snapshotpb.v1, snapshotpb.autococker.v1, snapshotpb.common.v1), so the
                // generated Kotlin packages mirror them directly. A prefix would redundantly
                // double the `snapshotpb` segment (e.g. ...protocol.snapshotpb.v1).
                id("kotlinx-protobuf-gen")
            }
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.protobuf)
}