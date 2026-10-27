package com.snapshotpb.configuration

import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snapshotpb.configuration.bluetooth.BleDebugLog
import com.snapshotpb.configuration.bluetooth.BlueFalconLogger
import com.snapshotpb.configuration.bluetooth.BluetoothManager
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResult
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.DeviceWriteResult
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.toDeviceUuid
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerFireMode
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.android.AndroidEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headless end-to-end test of the OTS session against a real board, through the production Android
 * [BluetoothManager] and its connection engine, built as `platform/android/di` builds them. It does no
 * UI automation, so it runs on a locked phone.
 *
 * Each cycle runs in the same process: connect with the full initial OTS read, write a new boot
 * text, check the write result, disconnect. So every cycle after the first is a same-session
 * reconnect, the case where a reused BlueFalcon peripheral once lost its CCC subscriptions. The last
 * step writes the original boot text back, or [finalBootText] when it is set.
 *
 * Instrumentation arguments:
 *   cycles        cycles to run                    (default 5)
 *   boardAddress  the board's BLE address          (default AC:EB:E6:8B:07:3A, the dev board)
 *   finalBootText the boot text to leave on the board (default: the boot text read first)
 *
 *   ./gradlew :app:android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.snapshotpb.configuration.OtsSessionE2ETest \
 *     -Pandroid.testInstrumentationRunnerArguments.cycles=5
 *
 * Logcat lines under the tag `OTS_E2E`: `cycle=K connect ms=.. write ms=.. result=OK|FAIL`, then
 * `SUMMARY cycles=.. ok=.. fail=.. restored=..`.
 */
@RunWith(AndroidJUnit4::class)
class OtsSessionE2ETest {

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun connectReadWriteDisconnectCycles() = runBlocking {
        val cycles = args.getString("cycles")?.toIntOrNull() ?: DEFAULT_CYCLES
        val deviceId = (args.getString("boardAddress") ?: DEFAULT_BOARD_ADDRESS).toDeviceUuid().toString()
        val finalBootText = args.getString("finalBootText")
        val finalBoltMode = args.getString("finalBoltMode")?.let { MarkerBoltMode.valueOf(it) }
        val finalSearDelay = args.getString("finalSearDelay")?.toIntOrNull()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        grantHeadlessScanPermissions()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val readBus = DeviceReadResultBus()
        val writeBus = DeviceWriteResultBus()
        // Built as platform/android/di BluetoothModule builds it.
        val blueFalcon = BlueFalcon(
            engine = AndroidEngine(app, logger = BlueFalconLogger(), autoDiscoverAllServicesAndCharacteristics = true),
        )
        val manager = BluetoothManager(
            blueFalcon = blueFalcon,
            bluetoothStateManager = BluetoothStateManager(),
            bleDebugLog = BleDebugLog(),
            deviceReadResultBus = readBus,
            deviceWriteResultBus = writeBus,
            coroutineScope = scope,
        )
        // MainActivity reports these from its permission and location checks; the test runner has
        // the permissions granted, so report them directly.
        manager.permissionGranted()
        manager.locationServicesEnabled()
        Log.i(TAG, "START cycles=$cycles deviceId=$deviceId")

        // Device.id is the board device_id, not the id made from the address, so match the read
        // on the scan key that the connect used.
        val isThisBoard = { result: DeviceReadResult -> result.scanKey == deviceId }
        var original: Device? = null
        var board: Device? = null
        var ok = 0
        for (cycle in 1..cycles) {
            val connectStart = System.currentTimeMillis()
            val read = awaitNext(scope, readBus.results, READ_TIMEOUT_MS, isThisBoard) {
                manager.connect(deviceId)
            }
            val connectMs = System.currentTimeMillis() - connectStart
            if (read == null) {
                Log.i(TAG, "cycle=$cycle connect ms=$connectMs result=FAIL detail=no-initial-read")
                continue
            }
            if (original == null) original = read.device
            board = read.device

            val text = "AND$cycle-${System.currentTimeMillis() % 1000}".take(BOOT_TEXT_MAX)
            val expectedBoltMode = if (cycle % 2 == 1) MarkerBoltMode.OPEN else MarkerBoltMode.CLOSED
            val expectedSearDelay = 120 + cycle * 15
            val updatedProfiles = read.device.knownProfiles.mapIndexed { idx, profile ->
                if (idx == 0) {
                    profile.copy(searSolenoidDelay = expectedSearDelay)
                } else {
                    profile
                }
            }
            val writeStart = System.currentTimeMillis()
            val write = writeBoard(
                scope,
                writeBus,
                manager,
                deviceId,
                read.device.copy(bootText = text, boltMode = expectedBoltMode, knownProfiles = updatedProfiles),
                cycle.toLong(),
            )
            val writeMs = System.currentTimeMillis() - writeStart
            val writtenFirst = write?.device?.knownProfiles?.firstOrNull()
            val passed = write != null &&
                write.error == null &&
                write.device.bootText == text &&
                write.device.boltMode == expectedBoltMode &&
                writtenFirst?.searSolenoidDelay == expectedSearDelay
            if (passed) {
                ok++
                board = write.device
            }
            Log.i(
                TAG,
                "cycle=$cycle connect ms=$connectMs write ms=$writeMs boltMode=$expectedBoltMode searDelay=$expectedSearDelay result=${if (passed) "OK" else "FAIL"}" +
                    (write?.error?.let { " error='${it.message}'" } ?: if (write == null) " error='no write result'" else ""),
            )
            manager.disconnect(deviceId)
            delay(SETTLE_MS)
        }

        var restored = false
        val start = original
        if (start != null) {
            val target = finalBootText ?: start.bootText
            val targetBoltMode = finalBoltMode ?: start.boltMode ?: MarkerBoltMode.CLOSED
            val targetSearDelay = finalSearDelay ?: start.knownProfiles.firstOrNull()?.searSolenoidDelay ?: 1000
            val targetProfiles = start.knownProfiles.mapIndexed { idx, profile ->
                if (idx == 0) {
                    profile.copy(
                        fireMode = MarkerFireMode.SEMI,
                        searSolenoidDelay = targetSearDelay,
                        cockingSolenoidDelay = 1000,
                        searSolenoidDwell = 1000,
                        cockingSolenoidDwellOpen = 1000,
                        cockingSolenoidDwellClosed = 1000,
                    )
                } else {
                    profile
                }
            }
            awaitNext(scope, readBus.results, READ_TIMEOUT_MS, isThisBoard) {
                manager.connect(deviceId)
            }
            val restore = writeBoard(
                scope, writeBus, manager, deviceId,
                (board ?: start).copy(bootText = target, boltMode = targetBoltMode, knownProfiles = targetProfiles), cycles + 1L,
            )
            val restoredFirst = restore?.device?.knownProfiles?.firstOrNull()
            restored = restore != null &&
                restore.error == null &&
                restore.device.bootText == target &&
                restore.device.boltMode == targetBoltMode &&
                restoredFirst?.searSolenoidDelay == targetSearDelay
            manager.disconnect(deviceId)
        }
        Log.i(TAG, "SUMMARY cycles=$cycles ok=$ok fail=${cycles - ok} restored=$restored")
        scope.cancel()

        assertNotNull("The board was never read", original)
        assertEquals("Cycles that failed", cycles, ok)
        assertEquals("The final boot text was not written", true, restored)
    }

    private suspend fun writeBoard(
        scope: CoroutineScope,
        writeBus: DeviceWriteResultBus,
        manager: BluetoothManager,
        deviceId: String,
        device: Device,
        requestId: Long,
    ): DeviceWriteResult? = awaitNext(scope, writeBus.results, WRITE_TIMEOUT_MS, { it.requestId == requestId }) {
        manager.writeDeviceConfiguration(deviceId, device, requestId)
    }

    /**
     * Runs [action] and waits for the next result on [results] that [accept] takes, or null on a
     * timeout. The buses do not replay, so the collector subscribes (UNDISPATCHED) before [action].
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

    private companion object {
        const val TAG = "OTS_E2E"
        const val DEFAULT_CYCLES = 5
        const val DEFAULT_BOARD_ADDRESS = "AC:EB:E6:8B:07:3A"
        const val READ_TIMEOUT_MS = 60_000L
        const val WRITE_TIMEOUT_MS = 60_000L
        const val SETTLE_MS = 1_500L

        /** The board's boot text limit (the protocol allows 10 characters). */
        const val BOOT_TEXT_MAX = 10
    }
}
