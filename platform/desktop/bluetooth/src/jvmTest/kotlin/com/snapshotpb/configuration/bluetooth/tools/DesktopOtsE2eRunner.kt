package com.snapshotpb.configuration.bluetooth.tools

import com.snapshotpb.configuration.bluetooth.BleDebugLog
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResult
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.DeviceWriteResult
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.selectBlueFalconEngine
import com.snapshotpb.configuration.model.Device
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Headless desktop end-to-end test of the OTS read and write against a real board, through the
 * **production** desktop [BluetoothManager] and its [com.snapshotpb.configuration.bluetooth.BluetoothConnectionEngine]
 * (connect, initial read, write, read-back), with the BlueFalcon engine the desktop app selects.
 *
 * Like [main] in `DesktopReliabilityRunner`, it lives in `jvmTest` so the `main()` never ships in
 * the desktop app, and its Gradle task is not wired into `build`, so CI never opens a radio.
 *
 * Each cycle writes a new boot text, waits for the write result, reads the board again on the live
 * link, and checks that the board holds the new text. The last step writes the original boot text
 * back, so the board ends as it started.
 *
 * Run against a board that advertises (on the dev board, `ble on` on its console first):
 *   ./gradlew -q --console=plain :platform:desktop:bluetooth:bleOtsE2eRun -Pcycles=5 -PboardName="Snapshot PB"
 *
 * Output (stdout, each line prefixed `E2E `):
 *   E2E START ..., E2E connect ms=.. result=OK|FAIL, E2E cycle=K write=OK|FAIL readback=OK|FAIL ms=..,
 *   E2E SUMMARY cycles=.. ok=.. fail=.. restored=true|false
 */

private fun prop(key: String, default: String): String = System.getProperty(key) ?: default

private fun now(): Long = System.currentTimeMillis()

// stdout IS the API here, as in DesktopReliabilityRunner, so this deliberately uses println, not Arbor.
private fun emit(line: String) = println("E2E $line")

/**
 * Runs [action] and waits for the next result on [results] that [accept] takes, or null on a
 * timeout. The buses do not replay, so the collector is started UNDISPATCHED: it subscribes before
 * [action] runs, as OtsClient's awaitControlResponse does.
 */
private suspend fun <T> awaitNext(
    scope: CoroutineScope,
    results: Flow<T>,
    timeoutMs: Long,
    accept: (T) -> Boolean,
    action: suspend () -> Unit,
): T? {
    val next = scope.async(start = CoroutineStart.UNDISPATCHED) { results.first(accept) }
    action()
    return withTimeoutOrNull(timeoutMs) { next.await() }.also { next.cancel() }
}

private suspend fun BluetoothManager.readBoard(
    scope: CoroutineScope,
    readBus: DeviceReadResultBus,
    deviceId: String,
): DeviceReadResult? =
    // deviceId is the scan key; the read result carries the board device_id as its id (#127).
    awaitNext(scope, readBus.results, READ_TIMEOUT_MS, { it.scanKey == deviceId }) { connect(deviceId) }

private suspend fun BluetoothManager.writeBoard(
    scope: CoroutineScope,
    writeBus: DeviceWriteResultBus,
    deviceId: String,
    device: Device,
    requestId: Long,
): DeviceWriteResult? = awaitNext(scope, writeBus.results, WRITE_TIMEOUT_MS, { it.requestId == requestId }) {
    writeDeviceConfiguration(deviceId, device, requestId)
}

private const val DEFAULT_CYCLES = 5
private const val SCAN_TIMEOUT_MS = 30_000L
private const val READ_TIMEOUT_MS = 60_000L
private const val WRITE_TIMEOUT_MS = 60_000L
private const val ADAPTER_READY_TIMEOUT_MS = 10_000L

// The BlueZ/Blessed threads are not daemons, so the JVM does not end when main returns.
fun main() {
    val passed = runBlocking { run() }
    exitProcess(if (passed) 0 else 1)
}

private suspend fun run(): Boolean = coroutineScope {
    val cycles = prop("cycles", DEFAULT_CYCLES.toString()).toIntOrNull() ?: DEFAULT_CYCLES
    val boardName = prop("boardName", "Snapshot PB")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = selectBlueFalconEngine()
    val blueFalcon = BlueFalcon(engine = engine)
    val readBus = DeviceReadResultBus()
    val writeBus = DeviceWriteResultBus()
    val manager = BluetoothManager(
        blueFalcon = blueFalcon,
        bluetoothStateManager = BluetoothStateManager(),
        bleDebugLog = BleDebugLog(),
        deviceReadResultBus = readBus,
        deviceWriteResultBus = writeBus,
        coroutineScope = scope,
    )
    emit("START cycles=$cycles engine=${engine::class.simpleName} boardName='$boardName' t=${now()}")

    withTimeoutOrNull(ADAPTER_READY_TIMEOUT_MS) { blueFalcon.managerState.first { it == BluetoothManagerState.Ready } }
    manager.startScanning()
    val deviceId = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
        manager.deviceState.first { state -> state.devices.values.any { it.name?.contains(boardName) == true } }
            .devices.entries.first { it.value.name?.contains(boardName) == true }.key
    }
    manager.stopScanning()
    if (deviceId == null) {
        emit("ERROR detail=board-not-found t=${now()}")
        scope.cancel()
        return@coroutineScope false
    }

    val connectStart = now()
    val initial = manager.readBoard(scope, readBus, deviceId)
    emit("connect ms=${now() - connectStart} result=${if (initial != null) "OK" else "FAIL"} t=${now()}")
    if (initial == null) {
        emit("SUMMARY cycles=$cycles ok=0 fail=$cycles restored=false")
        scope.cancel()
        return@coroutineScope false
    }
    val original = initial.device
    emit("initial bootText='${original.bootText}' profiles=${original.knownProfiles.size}")

    var ok = 0
    var fail = 0
    var board = original
    for (cycle in 1..cycles) {
        val text = "E2E$cycle-${now() % 1000}".take(BOOT_TEXT_MAX)
        val start = now()
        val write = manager.writeBoard(scope, writeBus, deviceId, board.copy(bootText = text), requestId = cycle.toLong())
        val writeOk = write != null && write.error == null && write.device.bootText == text
        val read = if (writeOk) manager.readBoard(scope, readBus, deviceId) else null
        val readOk = read?.device?.bootText == text
        if (writeOk && readOk) ok++ else fail++
        emit(
            "cycle=$cycle bootText='$text' write=${if (writeOk) "OK" else "FAIL"} " +
                "readback=${if (readOk) "OK" else "FAIL"} ms=${now() - start}" +
                (write?.error?.let { " error='${it.message}'" } ?: if (write == null) " error='no write result'" else "")
        )
        read?.device?.let { board = it }
    }

    val restore = manager.writeBoard(scope, writeBus, deviceId, board.copy(bootText = original.bootText), requestId = cycles + 1L)
    val restored = restore != null && restore.error == null &&
        manager.readBoard(scope, readBus, deviceId)?.device?.bootText == original.bootText
    emit("SUMMARY cycles=$cycles ok=$ok fail=$fail restored=$restored t=${now()}")

    manager.disconnect(deviceId)
    delay(1_000)
    scope.cancel()
    fail == 0 && restored
}

/** The board's boot text limit (the protocol allows 10 characters). */
private const val BOOT_TEXT_MAX = 10
