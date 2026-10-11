package com.snapshotpb.configuration.bluetooth.tools

import com.snapshotpb.configuration.bluetooth.ots.OtsUuids
import com.snapshotpb.configuration.bluetooth.selectBlueFalconEngine
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Headless desktop connect-establishment reliability harness — the macOS/Windows/Linux central side of
 * the BLE validation harness (`tools/ble-validation/README.md`, "Desktop"). The desktop analogue of the
 * Android `app/android/src/androidTest/.../ConnectReliabilityE2ETest`, and like it, it lives in a **test**
 * source set (`jvmTest`) so the `main()` and the hardcoded board MAC never ship in the desktop app.
 *
 * It loops a connect → discover → reach-OTS cycle N times through the **production** BlueFalcon path (the
 * same engine the desktop app selects, via the shared [selectBlueFalconEngine]), so the only variable
 * across a batch is the central software. It deliberately does NOT instantiate
 * [com.snapshotpb.configuration.bluetooth.BluetoothConnectionEngine] (wired to the state/debug/read/write
 * buses + full OTS read); instead it mirrors only the engine's connect/discover contract so the
 * measurement holds the bus/state/retry layer constant.
 *
 * Establishment is gated on the actual link state, not just `peripheral.services`: BlueFalcon reuses one
 * [BluetoothPeripheral] handle whose `services` are not guaranteed to clear on disconnect, so reading them
 * alone would report a stale OTS as a false OK on iteration 2+ (and would make the leak arm unable to ever
 * see a wedge). Each attempt therefore waits for [BluetoothPeripheralState.Connected] first, *then* issues
 * an explicit `discoverServices` (desktop engines do NOT auto-discover — that is Android-only; cf.
 * `DefaultBluetoothSeams.awaitServicesDiscovered`) and polls `peripheral.services` for the OTS service
 * `0x1825` (== [OtsUuids.Service]) — the CONNECT_OK definition in `tools/ble-validation/README.md` —
 * and finally waits for the link to return to `Disconnected` before the next attempt so the next gate is
 * meaningful.
 *
 * Run via the `bleReliabilityRun` Gradle task (see build.gradle.kts), which forwards `-P` flags as system
 * properties. The board is matched by **name** because macOS surfaces a CoreBluetooth UUID, not the MAC.
 *
 * Arms (`-Parm=`):
 *   - bluefalcon : the production BlueFalcon path (default).
 *   - leak       : issue N back-to-back connects on the one peripheral handle without disconnecting, then
 *                  probe with a clean connect to detect whether the board wedged. NOTE: unlike the Android
 *                  `arm=leak` (which opens N independent raw GATT clients), BlueFalcon maps one peripheral
 *                  object to one connection, so this is N reconnect attempts on a single handle, not N
 *                  orphaned links — it stresses the engine, but the cross-platform Bleak control
 *                  (`connect_reliability.py --reset none`) is the stronger Mode-A sensitivity lever on
 *                  desktop. `recovered=false` means a wedge the harness can SEE.
 *   - radio-off  : the sensitivity arm for firmware that does not wedge. The harness finds the board, then
 *                  turns the board radio off with `ble off` on the UART console (`-PuartPort=`, through
 *                  `tools/ble-validation/board-console.py`). Each of the N connect attempts must then FAIL.
 *                  At the end it sends `ble on`. `detected=true` means that the harness counted every
 *                  attempt to an unreachable board as a failure, so it cannot report a false OK.
 *
 * Modes (`-Pmode=`, bluefalcon arm only):
 *   - per-attempt  : one establishment per iteration — the sensitive number a leak/RF fix must move (default).
 *   - user-visible : wraps CONNECT_ATTEMPTS=3 with the retry backoff like the production engine — the
 *                    user-felt rate.
 *
 * Output (stdout, every line prefixed `RELIABILITY ` so the run wrapper can `grep '^RELIABILITY'`;
 * epoch-ms `t=` so `tools/ble-validation/correlate.py` can align with the board UART). `rate=` is the
 * *fail* rate, matching the Android harness:
 *   RELIABILITY START arm=.. mode=.. count=.. engine=.. ..
 *   RELIABILITY arm=.. iter=K result=OK|FAIL detail=services|no-services ms=M t=<epochMs>
 *   RELIABILITY SUMMARY arm=.. ok=.. fail=.. rate=..% t=<epochMs>
 */

private fun prop(key: String, default: String): String = System.getProperty(key) ?: default

private fun now(): Long = System.currentTimeMillis()

// stdout IS the API here (consumed by correlate.py), so this deliberately uses println, not Arbor.
private fun emit(line: String) = println("RELIABILITY $line")

private fun result(reached: Boolean): String = if (reached) "OK" else "FAIL"

private fun BluetoothPeripheral.matches(name: String, address: String): Boolean =
    (this.name?.contains(name, ignoreCase = true) == true) || uuid.equals(address, ignoreCase = true)

private fun BluetoothPeripheral.hasOts(): Boolean = services.any { it.uuid == OtsUuids.Service }

private fun BlueFalcon.isConnected(board: BluetoothPeripheral): Boolean =
    connectionState(board) == BluetoothPeripheralState.Connected

private suspend fun resolveBoard(
    blueFalcon: BlueFalcon,
    name: String,
    address: String,
): BluetoothPeripheral? {
    // The production path only scans once the manager reports Ready; await that so a board-not-found
    // result means "really not there", not "adapter still powering on" (notably on a cold macOS start).
    val ready = withTimeoutOrNull(ADAPTER_READY_TIMEOUT_MS) {
        blueFalcon.managerState.first { it == BluetoothManagerState.Ready }
    } != null
    if (!ready) emit("WARN detail=adapter-not-ready (scanning anyway) t=${now()}")

    blueFalcon.scan()
    val board = withTimeoutOrNull(SCAN_RESOLVE_TIMEOUT_MS) {
        blueFalcon.peripherals
            .first { peripherals -> peripherals.any { it.matches(name, address) } }
            .first { it.matches(name, address) }
    }
    runCatching { blueFalcon.stopScanning() }
    return board
}

/**
 * One connect → (await link up) → explicit discover → reach-OTS cycle, always tearing the link down and
 * waiting for it to settle to Disconnected afterward. Returns whether the OTS service `0x1825` was reached
 * over a genuinely-established link within [timeoutMs]. A failed establishment (e.g. HCI `0x3e`) never
 * reaches [BluetoothPeripheralState.Connected], so it times out to FAIL even if `services` is stale.
 */
private suspend fun attemptOnce(
    blueFalcon: BlueFalcon,
    board: BluetoothPeripheral,
    timeoutMs: Long,
): Boolean {
    blueFalcon.connect(board)
    val reached = withTimeoutOrNull(timeoutMs) {
        while (!blueFalcon.isConnected(board)) delay(STATE_POLL_INTERVAL_MS)
        runCatching { blueFalcon.discoverServices(board) }
        while (!board.hasOts()) delay(STATE_POLL_INTERVAL_MS)
        true
    } == true
    runCatching { blueFalcon.disconnect(board) }
    withTimeoutOrNull(DISCONNECT_SETTLE_TIMEOUT_MS) {
        while (blueFalcon.connectionState(board) != BluetoothPeripheralState.Disconnected) {
            delay(STATE_POLL_INTERVAL_MS)
        }
    }
    return reached
}

/** Mirrors [com.snapshotpb.configuration.bluetooth.BluetoothConnectionEngine]'s bounded retry loop. */
private suspend fun attemptWithRetry(
    blueFalcon: BlueFalcon,
    board: BluetoothPeripheral,
    timeoutMs: Long,
    backoffMs: Long,
): Boolean {
    for (attempt in 1..CONNECT_ATTEMPTS) {
        if (attemptOnce(blueFalcon, board, timeoutMs)) return true
        if (attempt < CONNECT_ATTEMPTS) delay(backoffMs)
    }
    return false
}

private suspend fun runBlueFalcon(
    blueFalcon: BlueFalcon,
    board: BluetoothPeripheral,
    mode: String,
    count: Int,
    connectTimeoutMs: Long,
    settleMs: Long,
) {
    var ok = 0
    var fail = 0
    for (iter in 1..count) {
        val start = now()
        val reached = when (mode) {
            "per-attempt" -> attemptOnce(blueFalcon, board, connectTimeoutMs)
            "user-visible" -> attemptWithRetry(blueFalcon, board, connectTimeoutMs, settleMs)
            else -> error("unknown mode=$mode (use per-attempt|user-visible)")
        }
        val ms = now() - start
        if (reached) ok++ else fail++
        emit(
            "arm=bluefalcon iter=$iter result=${result(reached)} " +
                "detail=${if (reached) "services" else "no-services"} ms=$ms t=${now()}"
        )
        delay(settleMs)
    }
    val rate = if (count > 0) 100 * fail / count else 0
    emit("SUMMARY arm=bluefalcon ok=$ok fail=$fail rate=$rate% t=${now()}")
}

/**
 * Sensitivity arm: hammer the board's single connection slot, free the central side, then probe with clean
 * connects to report whether — and when — the board recovered. `recovered=false` is a wedge the harness can
 * SEE (the falsifiability that `tools/ble-validation/README.md` requires before any "clean" verdict is
 * trusted).
 */
private suspend fun runLeak(
    blueFalcon: BlueFalcon,
    board: BluetoothPeripheral,
    count: Int,
    timeoutMs: Long,
    settleMs: Long,
) {
    emit("arm=leak phase1 reconnects=$count on one handle (never disconnected) t=${now()}")
    for (iter in 1..count) {
        runCatching { blueFalcon.connect(board) }
        emit("arm=leak iter=$iter issued t=${now()}")
        delay(settleMs)
    }
    // Free the central handle first so the probe reflects the BOARD's state, not the central's.
    runCatching { blueFalcon.disconnect(board) }

    val probeCount = prop("probeCount", DEFAULT_PROBE_COUNT.toString()).toInt()
    val probeGapMs = prop("probeGapMs", DEFAULT_PROBE_GAP_MS.toString()).toLong()
    var recoveredAtProbe = -1
    for (probe in 1..probeCount) {
        delay(probeGapMs)
        val reached = attemptOnce(blueFalcon, board, timeoutMs)
        emit("arm=leak PROBE $probe/$probeCount result=${result(reached)} t=${now()}")
        if (reached) {
            recoveredAtProbe = probe
            break
        }
    }
    emit(
        "SUMMARY arm=leak leaked=$count recovered=${recoveredAtProbe > 0} " +
            "recoveredAtProbe=$recoveredAtProbe t=${now()}"
    )
}

/**
 * Sensitivity arm for firmware that does not wedge: turn the board radio off, then make [count] clean
 * connects. The board cannot answer, so each attempt must FAIL. A reached OTS with the radio off is a
 * false OK (for example stale services on the reused handle), and the harness is then blind.
 */
private suspend fun runRadioOff(
    blueFalcon: BlueFalcon,
    board: BluetoothPeripheral,
    count: Int,
    timeoutMs: Long,
    settleMs: Long,
) {
    val uartPort = prop("uartPort", "")
    val consoleScript = prop("consoleScript", "tools/ble-validation/board-console.py")
    if (uartPort.isEmpty()) {
        emit("ERROR detail=no-uart-port (pass -PuartPort=<board console port>) t=${now()}")
        return
    }
    // Connect one time first, so the handle holds the OTS services that a stale read could report.
    val warmUp = attemptOnce(blueFalcon, board, timeoutMs)
    emit("arm=radio-off warm-up result=${result(warmUp)} t=${now()}")

    boardConsole(consoleScript, uartPort, "ble off")
    delay(RADIO_OFF_SETTLE_MS)
    var ok = 0
    var fail = 0
    try {
        for (iter in 1..count) {
            val start = now()
            val reached = attemptOnce(blueFalcon, board, timeoutMs)
            if (reached) ok++ else fail++
            emit("arm=radio-off iter=$iter result=${result(reached)} ms=${now() - start} t=${now()}")
            delay(settleMs)
        }
    } finally {
        boardConsole(consoleScript, uartPort, "ble on")
    }
    emit("SUMMARY arm=radio-off ok=$ok fail=$fail detected=${fail == count} warmUp=${result(warmUp)} t=${now()}")
}

/** Runs [command] on the board console through [script], and logs what the board answers. */
private fun boardConsole(script: String, port: String, command: String) {
    // The script needs pyserial, so `-Ppython=` can name a Python that has it (for example a venv).
    val process = ProcessBuilder(prop("python", "python3"), script, port, command).redirectErrorStream(true).start()
    val answer = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    val lastLine = answer.lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""
    emit("console command='$command' exit=$exit answer='$lastLine' t=${now()}")
}

private const val DEFAULT_COUNT = 30
private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
private const val DEFAULT_SETTLE_MS = 750L
private const val DEFAULT_PROBE_COUNT = 8
private const val DEFAULT_PROBE_GAP_MS = 2_500L

/** Matches `BluetoothConnectionEngine.CONNECT_ATTEMPTS`. */
private const val CONNECT_ATTEMPTS = 3
private const val SCAN_RESOLVE_TIMEOUT_MS = 20_000L
private const val ADAPTER_READY_TIMEOUT_MS = 10_000L
private const val DISCONNECT_SETTLE_TIMEOUT_MS = 2_000L
private const val STATE_POLL_INTERVAL_MS = 50L

/** Time for the board to stop the radio after `ble off`. */
private const val RADIO_OFF_SETTLE_MS = 2_000L

// The BlueZ/Blessed threads are not daemons, so the JVM does not end when main returns or throws.
// An exception (for example an unknown arm) gives an ERROR line and exit status 1.
fun main() {
    val outcome = runCatching { runBlocking { run() } }
    outcome.exceptionOrNull()?.let { error ->
        // The stack trace goes to stderr, which the `grep '^RELIABILITY'` of the run wrapper removes.
        val message = error.message?.lineSequence()?.firstOrNull().orEmpty()
        emit("ERROR detail=exception type=${error::class.simpleName} message='$message' t=${now()}")
        error.printStackTrace()
    }
    exitProcess(if (outcome.isSuccess) 0 else 1)
}

private suspend fun run() {
    val arm = prop("arm", "bluefalcon")
    val mode = prop("mode", "per-attempt")
    val count = prop("count", DEFAULT_COUNT.toString()).toInt()
    val connectTimeoutMs = prop("connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS.toString()).toLong()
    val settleMs = prop("settleMs", DEFAULT_SETTLE_MS.toString()).toLong()
    // NB: `boardName`/`boardAddress`, not `name`/`address` — `name` collides with Gradle's built-in
    // `project.name`, so `-Pname=` is silently shadowed (use `-PboardName`, as
    // `tools/ble-validation/README.md` shows).
    val boardName = prop("boardName", "Snapshot PB")
    val boardAddress = prop("boardAddress", "AC:EB:E6:8B:07:3A")

    val engine = selectBlueFalconEngine()
    val blueFalcon = BlueFalcon(engine = engine)
    emit(
        "START arm=$arm mode=$mode count=$count engine=${engine::class.simpleName} " +
            "connectTimeoutMs=$connectTimeoutMs settleMs=$settleMs boardName='$boardName' " +
            "os='${System.getProperty("os.name")}' t=${now()}"
    )

    val board = resolveBoard(blueFalcon, boardName, boardAddress)
    if (board == null) {
        emit("ERROR detail=board-not-found boardName='$boardName' t=${now()}")
        return
    }
    emit("resolved peripheral uuid=${board.uuid} name=${board.name} t=${now()}")
    delay(500)

    when (arm) {
        "bluefalcon" -> runBlueFalcon(blueFalcon, board, mode, count, connectTimeoutMs, settleMs)
        "leak" -> runLeak(blueFalcon, board, count, connectTimeoutMs, settleMs)
        "radio-off" -> runRadioOff(blueFalcon, board, count, connectTimeoutMs, settleMs)
        else -> error("unknown arm=$arm (use bluefalcon|leak|radio-off)")
    }
}
