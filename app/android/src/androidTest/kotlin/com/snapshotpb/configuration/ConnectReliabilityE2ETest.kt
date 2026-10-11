package com.snapshotpb.configuration

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.Logger
import dev.bluefalcon.engine.android.AndroidEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headless connect-establishment reliability harness — the Android central side of the BLE
 * validation harness (`tools/ble-validation/README.md`, "Android"). It was aimed at this
 * hypothesis: the 0x3e / GATT_133 establishment failure reproduces on Android+iOS (both BlueFalcon)
 * yet NOT on a raw macOS/Bleak script, so the suspect is BlueFalcon and/or the shared app code — not
 * the firmware/RF.
 *
 * The result confirmed the hypothesis. The cause was a leak in the BlueFalcon Android engine: a
 * failed connect did not close its `connectGatt` handle, and the orphan kept the board busy.
 * BlueFalcon 3.5.1 and later close the handle. The harness now checks that the fix stays in place
 * (see `tools/ble-validation/README.md`, "Background").
 *
 * This test loops a single connect-establishment attempt N times through ONE of several `arm`s, all
 * on the same tablet under identical RF and the same no-reset cadence, so the only thing that varies
 * between arms is the central software. It does ZERO UI automation; the whole batch is one
 * `am instrument` call.
 *
 * Decisive comparison:
 *   - arm=bluefalcon : the production BlueFalcon path (constructed exactly like the app's DI).
 *   - arm=raw        : a minimal raw android.bluetooth.BluetoothGatt connect (no BlueFalcon),
 *                      the on-device analogue of the non-reproducing Bleak control.
 *   - arm=leak       : opens `count` raw connects and does not close them, then probes with a clean
 *                      connect to find whether the board wedged (the sensitivity arm).
 * If `bluefalcon` fails ~10-30% while `raw` fails ~0% under the same RF/cadence, the culprit is in
 * BlueFalcon / how the app drives it. If both fail equally, the failure is below the app (RF/board).
 *
 * The raw arm captures the EXACT GATT status in onConnectionStateChange (133 = a true establishment
 * failure) which the BlueFalcon path hides from the app, so it also distinguishes a real 0x3e from a
 * "link established but services never populated" software miss.
 *
 * Instrumentation args (`-e key value`):
 *   arm             bluefalcon | raw | leak          (default bluefalcon)
 *   count           iterations                       (default 30)
 *   autoConnect     false | true                     (default false — the app's setting)
 *   connectTimeoutMs per-attempt establishment cap   (default 5000 — = app CONNECT_ATTEMPT_TIMEOUT)
 *   settleMs        gap between attempts             (default 750  — = app retry backoff)
 *   name            scan name substring              (default "Snapshot PB")
 *   address         pin a MAC, skip name scan        (default AC:EB:E6:8B:07:3A)
 *   mtu             raw arm: request MTU after connect (default false — keep the control minimal)
 *   probeCount      leak arm: clean-connect probes   (default 8)
 *   probeGapMs      leak arm: gap before each probe  (default 2500)
 *
 * Output (logcat tag RELIABILITY, epoch-stamped so the host can align with serial-monitor.py UART):
 *   RELIABILITY arm=.. iter=k result=OK|FAIL detail=.. ms=.. t=<epochMs>
 *   RELIABILITY SUMMARY arm=.. ok=.. fail=.. rate=..%
 */
@RunWith(AndroidJUnit4::class)
class ConnectReliabilityE2ETest {

    private val args = InstrumentationRegistry.getArguments()
    private fun arg(k: String, d: String) = args.getString(k) ?: d

    private val otsServiceUuid = java.util.UUID.fromString("00001825-0000-1000-8000-00805f9b34fb")

    @Test
    fun connectEstablishmentLoop() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val app = instr.targetContext.applicationContext as Application
        grantHeadlessScanPermissions()

        val arm = arg("arm", "bluefalcon")
        val count = arg("count", "30").toInt()
        val autoConnect = arg("autoConnect", "false").toBoolean()
        val timeoutMs = arg("connectTimeoutMs", "5000").toLong()
        val settleMs = arg("settleMs", "750").toLong()
        val nameNeedle = arg("name", "Snapshot PB")
        val pinnedAddress = arg("address", "AC:EB:E6:8B:07:3A")
        val rawMtu = arg("mtu", "false").toBoolean()

        Log.i(TAG, "START arm=$arm count=$count autoConnect=$autoConnect timeoutMs=$timeoutMs " +
            "settleMs=$settleMs name='$nameNeedle' address=$pinnedAddress mtu=$rawMtu t=${now()}")

        when (arm) {
            "bluefalcon" -> runBlueFalcon(app, count, autoConnect, timeoutMs, settleMs, nameNeedle, pinnedAddress)
            "raw" -> runRaw(app, count, autoConnect, timeoutMs, settleMs, pinnedAddress, rawMtu)
            "leak" -> runLeak(app, count, timeoutMs, settleMs, pinnedAddress)
            else -> throw IllegalArgumentException("unknown arm=$arm (use bluefalcon|raw|leak)")
        }
    }

    // ---- arm=bluefalcon : production BlueFalcon path, single attempt per iteration ----------------

    private suspend fun runBlueFalcon(
        app: Application,
        count: Int,
        autoConnect: Boolean,
        timeoutMs: Long,
        settleMs: Long,
        nameNeedle: String,
        pinnedAddress: String,
    ) {
        // Construct exactly like platform/android/di/.../BluetoothModule.kt.
        val blueFalcon = BlueFalcon(
            engine = AndroidEngine(
                app,
                logger = LogcatLogger,
                autoDiscoverAllServicesAndCharacteristics = true,
            )
        )

        // Scan once to resolve the board peripheral, then stop scanning for the rest of the run
        // (mirrors the app: it stops scanning before connecting).
        blueFalcon.scan()
        val board: BluetoothPeripheral = withTimeout(20_000) {
            blueFalcon.peripherals
                .first { set -> set.any { it.matches(nameNeedle, pinnedAddress) } }
                .first { it.matches(nameNeedle, pinnedAddress) }
        }
        blueFalcon.stopScanning()
        Log.i(TAG, "resolved peripheral uuid=${board.uuid} name=${board.name} t=${now()}")
        delay(500)

        var ok = 0
        var fail = 0
        for (i in 1..count) {
            val start = System.currentTimeMillis()
            blueFalcon.connect(board, autoConnect)
            // Gate on the actual link state, not just board.services: BlueFalcon reuses one
            // BluetoothPeripheral handle whose services are NOT guaranteed to clear on disconnect, so
            // polling services alone reports a stale OTS as a false OK on iteration 2+ (and would make a
            // real board wedge invisible). Wait for Connected first — a failed establishment never reaches
            // it and times out to FAIL even with stale services — then for the OTS service. Mirrors
            // DesktopReliabilityRunner. NB: AndroidEngine auto-discovers on connect
            // (autoDiscoverAllServicesAndCharacteristics = true), so unlike desktop no explicit
            // discoverServices call is needed here.
            val reached = withTimeoutOrNull(timeoutMs) {
                while (!blueFalcon.isConnected(board)) delay(STATE_POLL_INTERVAL_MS)
                while (board.services.none { it.uuid.toString().equals(otsServiceUuid.toString(), true) }) {
                    delay(STATE_POLL_INTERVAL_MS)
                }
                true
            } == true
            val ms = System.currentTimeMillis() - start
            if (reached) ok++ else fail++
            Log.i(TAG, "arm=bluefalcon iter=$i result=${ok(reached)} " +
                "detail=${if (reached) "services" else "no-services"} ms=$ms t=${now()}")
            runCatching { blueFalcon.disconnect(board) }
            // Wait for the link to actually settle to Disconnected before the next attempt, so the next
            // Connected gate is meaningful (a stale Connected state would otherwise make iter N+1 a false
            // OK). For the dominant wedge mode — a failed establishment (0x3e/133) after a clean disconnect
            // — the next connect never reaches Connected and correctly FAILs. (If a link instead gets stuck
            // reporting Connected after disconnect, this settle times out and the next gate may still pass
            // on stale state; same residual as the desktop runner, where the raw arm is the backstop.)
            withTimeoutOrNull(DISCONNECT_SETTLE_TIMEOUT_MS) {
                while (blueFalcon.connectionState(board) != BluetoothPeripheralState.Disconnected) {
                    delay(STATE_POLL_INTERVAL_MS)
                }
            }
            delay(settleMs)
        }
        summary("bluefalcon", ok, fail, count)
    }

    private fun BluetoothPeripheral.matches(nameNeedle: String, address: String): Boolean =
        (name?.contains(nameNeedle, ignoreCase = true) == true) || uuid.equals(address, ignoreCase = true)

    private fun BlueFalcon.isConnected(board: BluetoothPeripheral): Boolean =
        connectionState(board) == BluetoothPeripheralState.Connected

    // ---- arm=raw : minimal android.bluetooth.BluetoothGatt control (no BlueFalcon) ----------------

    private suspend fun runRaw(
        app: Application,
        count: Int,
        autoConnect: Boolean,
        timeoutMs: Long,
        settleMs: Long,
        address: String,
        requestMtu: Boolean,
    ) {
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager
        val adapter = manager.adapter
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        Log.i(TAG, "raw target address=$address t=${now()}")
        delay(500)

        var ok = 0
        var fail = 0
        for (i in 1..count) {
            val start = System.currentTimeMillis()
            val outcome = rawConnectOnce(app, device, autoConnect, timeoutMs, requestMtu)
            val ms = System.currentTimeMillis() - start
            if (outcome.reachedServices) ok++ else fail++
            Log.i(TAG, "arm=raw iter=$i result=${ok(outcome.reachedServices)} " +
                "detail=${outcome.detail} connStatus=${outcome.connStatus} ms=$ms t=${now()}")
            delay(settleMs)
        }
        summary("raw", ok, fail, count)
    }

    private class RawOutcome(
        val reachedServices: Boolean,
        val detail: String,
        val connStatus: Int,
    )

    /** One minimal connect→discover→close cycle, capturing the exact GATT connection status. */
    private suspend fun rawConnectOnce(
        context: Context,
        device: BluetoothDevice,
        autoConnect: Boolean,
        timeoutMs: Long,
        requestMtu: Boolean,
    ): RawOutcome {
        // Resolves to: true=OTS service discovered, false=failed; the Int carries the gatt status
        // seen in onConnectionStateChange (133 = establishment failure) for diagnosis.
        val done = CompletableDeferred<RawOutcome>()
        // Written on a binder thread (callbacks), read on the coroutine thread → atomic for visibility.
        val lastConnStatus = java.util.concurrent.atomic.AtomicInteger(-1)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                lastConnStatus.set(status)
                Log.d(TAG, "raw onConnectionStateChange status=$status newState=$newState t=${now()}")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (requestMtu) gatt.requestMtu(517) else gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!done.isCompleted) {
                        done.complete(RawOutcome(false, detailForStatus(status), status))
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "raw onMtuChanged mtu=$mtu status=$status t=${now()}")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val hasOts = gatt.services.any { it.uuid == otsServiceUuid }
                Log.d(TAG, "raw onServicesDiscovered status=$status services=${gatt.services.size} ots=$hasOts t=${now()}")
                if (!done.isCompleted) {
                    done.complete(
                        RawOutcome(hasOts, if (hasOts) "services" else "no-ots", lastConnStatus.get())
                    )
                }
            }
        }

        val gatt: BluetoothGatt =
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)

        val outcome = withTimeoutOrNull(timeoutMs) { done.await() }
            ?: RawOutcome(false, "timeout", lastConnStatus.get())

        // Always tear the link fully down (disconnect + close) before the next iteration — the
        // hygiene a minimal correct central practices; close() releases the clientIf.
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        return outcome
    }

    private fun detailForStatus(status: Int): String = when (status) {
        0 -> "disconnected-clean"
        8 -> "conn-timeout-0x08"      // GATT_CONN_TIMEOUT
        19 -> "peer-terminated-0x13"  // GATT_CONN_TERMINATE_PEER_USER
        22 -> "local-terminated-0x16" // GATT_CONN_TERMINATE_LOCAL_HOST
        62 -> "estab-fail-0x3e"       // GATT_CONN_FAIL_ESTABLISH (0x3e) — the bug, surfaced raw
        133 -> "gatt-133"             // generic GATT_ERROR (often the masked 0x3e)
        else -> "status-$status"
    }

    // ---- arm=leak : reproduce a MISBEHAVING central (orphaned, never-closed direct connects) -------
    // Mimics stock BlueFalcon's bug to stress the board, then probes with a CLEAN connect to report
    // whether the board wedged. Used to validate firmware defense-in-depth: an unhardened board fails
    // the probe (wedged, needs power-cycle); a hardened board passes (recovered its single slot).
    // count is kept under the Android GATT clientIf ceiling (~30) so every leak actually initiates.

    private suspend fun runLeak(
        app: Application,
        count: Int,
        timeoutMs: Long,
        settleMs: Long,
        address: String,
    ) {
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager
        val adapter = manager.adapter
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val leaked = java.util.concurrent.CopyOnWriteArrayList<BluetoothGatt>()
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "leak onConnectionStateChange status=$status newState=$newState t=${now()}")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.discoverServices()
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "leak onServicesDiscovered status=$status t=${now()}")
            }
        }
        Log.i(TAG, "leak phase 1: issuing $count orphaned direct-connects (never closed) t=${now()}")
        for (i in 1..count) {
            val g = device.connectGatt(app, false, cb, BluetoothDevice.TRANSPORT_LE)
            if (g != null) leaked.add(g)
            Log.i(TAG, "arm=leak iter=$i issued leaked=${leaked.size} t=${now()}")
            delay(settleMs)
        }
        // Cleanup the leaked central handles FIRST so the probe reflects the BOARD's state, not the
        // central's leaked clientIfs. (The wedge is on the board; closing here frees the central.)
        leaked.forEach { runCatching { it.disconnect() }; runCatching { it.close() } }
        // Phase 2: probe with a clean connect repeatedly over a window, to detect whether — and when —
        // the board recovers on its own (e.g. via a firmware connectability watchdog) without a reset.
        val probeCount = arg("probeCount", "8").toInt()
        val probeGapMs = arg("probeGapMs", "2500").toLong()
        var recoveredAt = -1
        for (p in 1..probeCount) {
            delay(probeGapMs)
            val probe = rawConnectOnce(app, device, false, timeoutMs, false)
            Log.i(TAG, "arm=leak PROBE $p/$probeCount result=${ok(probe.reachedServices)} " +
                "detail=${probe.detail} connStatus=${probe.connStatus} t=${now()}")
            if (probe.reachedServices) { recoveredAt = p; break }
        }
        Log.i(TAG, "SUMMARY arm=leak leaked=${leaked.size} " +
            "recovered=${recoveredAt > 0} recoveredAtProbe=$recoveredAt t=${now()}")
    }

    // ---- shared helpers --------------------------------------------------------------------------

    private fun ok(b: Boolean) = if (b) "OK" else "FAIL"
    private fun now() = System.currentTimeMillis()

    private fun summary(arm: String, ok: Int, fail: Int, count: Int) {
        val rate = if (count > 0) 100 * fail / count else 0
        Log.i(TAG, "SUMMARY arm=$arm ok=$ok fail=$fail rate=$rate% t=${now()}")
    }

    private object LogcatLogger : Logger {
        override fun error(message: String, cause: Throwable?) { Log.e("BlueFalcon", message, cause) }
        override fun warn(message: String, cause: Throwable?) { Log.w("BlueFalcon", message, cause) }
        override fun info(message: String, cause: Throwable?) { Log.i("BlueFalcon", message, cause) }
        override fun debug(message: String, cause: Throwable?) { Log.d("BlueFalcon", message, cause) }
    }

    companion object {
        private const val TAG = "RELIABILITY"

        /** Poll cadence for link-state/service gates (matches DesktopReliabilityRunner). */
        private const val STATE_POLL_INTERVAL_MS = 50L

        /** How long to wait for the link to settle to Disconnected after a disconnect, before the next attempt. */
        private const val DISCONNECT_SETTLE_TIMEOUT_MS = 2_000L
    }
}
