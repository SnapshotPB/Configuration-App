import com.snapshotpb.composite.util.configureOptIns

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    configureOptIns()
    jvm()
    sourceSets {
        jvmMain {
            dependencies {
                implementation(projects.core.bluetooth)
                implementation(projects.core.protocol)
                implementation(libs.jna)
                implementation(projects.core.model)
                implementation(libs.kotlinx.datetime)

                implementation(libs.arbor)
                implementation(libs.blue.falcon)
                implementation(libs.blue.falcon.macos.jvm)
                implementation(libs.blue.falcon.linux)
                implementation(libs.blue.falcon.windows)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
            }
        }
    }
}

// `src/jvmTest` holds the reliability runner and nothing else — it is a `main()` program, not a
// suite, and it lives there so it stays off the desktop app's runtime classpath. The standard
// jvmTest task still runs as part of `build`, finds those sources and discovers no tests, which
// Gradle treats as a misconfiguration and fails on. Here it is the arrangement working as intended,
// so say so. Delete this the moment this module gains a real test — then an empty discovery really
// would be a misconfiguration.
tasks.named<Test>("jvmTest") {
    failOnNoDiscoveredTests = false
}

// BLE connection-reliability harness runner (method and usage: tools/ble-validation/README.md, "Desktop").
// The runner lives in jvmTest (like Android's androidTest harness) so it never ships in the desktop app,
// and the task is NOT wired into `build`/`check`, so CI never opens a radio. Run manually against a board:
//   ./gradlew -q --console=plain :platform:desktop:bluetooth:bleReliabilityRun \
//     -Parm=bluefalcon -Pmode=per-attempt -Pcount=100 -PconnectTimeoutMs=5000 -PsettleMs=750 -PboardName="Snapshot PB"
// Use -PboardName, NOT -Pname: `name` collides with the built-in Gradle project property and is shadowed.
tasks.register<JavaExec>("bleReliabilityRun") {
    group = "ble diagnostics"
    description = "Run the desktop BLE connect-reliability harness against a SnapshotPB board."
    val testCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
    classpath(testCompilation.output.allOutputs, configurations.named("jvmTestRuntimeClasspath"))
    mainClass.set("com.snapshotpb.configuration.bluetooth.tools.DesktopReliabilityRunnerKt")
    listOf(
        "arm", "mode", "count", "connectTimeoutMs", "settleMs", "boardName", "boardAddress", "probeCount", "probeGapMs",
        "uartPort", "python"
    ).forEach { key -> (project.findProperty(key) as? String)?.let { systemProperty(key, it) } }
    // The radio-off arm sends `ble off` and `ble on` on the board console through this script.
    systemProperty("consoleScript", rootProject.file("tools/ble-validation/board-console.py").absolutePath)
}
// OTS end-to-end runner: connect, initial read, then write + read-back cycles through the production
// desktop BluetoothManager, and the original boot text written back at the end. Like
// bleReliabilityRun it lives in jvmTest and is NOT wired into `build`/`check`. Run manually against
// a board:
//   ./gradlew -q --console=plain :platform:desktop:bluetooth:bleOtsE2eRun -Pcycles=5 -PboardName="Snapshot PB"
tasks.register<JavaExec>("bleOtsE2eRun") {
    group = "ble diagnostics"
    description = "Run the desktop OTS read/write end-to-end test against a SnapshotPB board."
    val testCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
    classpath(testCompilation.output.allOutputs, configurations.named("jvmTestRuntimeClasspath"))
    mainClass.set("com.snapshotpb.configuration.bluetooth.tools.DesktopOtsE2eRunnerKt")
    listOf("cycles", "boardName").forEach { key ->
        (project.findProperty(key) as? String)?.let { systemProperty(key, it) }
    }
}
