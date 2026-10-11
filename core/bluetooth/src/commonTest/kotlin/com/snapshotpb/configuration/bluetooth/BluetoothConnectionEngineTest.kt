package com.snapshotpb.configuration.bluetooth

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the platform-agnostic [BluetoothConnectionEngine] state machine, driven through a
 * fake [PlatformBluetoothSeams] + a real `BlueFalcon` over a [FakeBlueFalconEngine]. The connect path
 * is steered short of OTS discovery (which needs a real GATT peripheral) via the seam's
 * services-discovered outcome, which is enough to cover scan/connect/abort/timeout/cancellation.
 *
 * The tests run on an [UnconfinedTestDispatcher] so the engine's launched work (the connect job and
 * the two BlueFalcon collectors, started on [backgroundScope]) executes eagerly up to each suspension
 * point; [advanceUntilIdle] is still used to drive the virtual-time scan/connect timeouts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectionEngineTest {

    private class Fixture(
        val engine: BluetoothConnectionEngine,
        val stateManager: BluetoothStateManager,
        val falconEngine: FakeBlueFalconEngine,
        val seams: FakePlatformBluetoothSeams
    )

    private fun TestScope.newFixture(
        seams: FakePlatformBluetoothSeams = FakePlatformBluetoothSeams()
    ): Fixture {
        // The radio is on unless a test turns it off: a failed connect puts back the idle state
        // that the radio supports (L14).
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
            .apply { emitManagerState(BluetoothManagerState.Ready) }
        val stateManager = BluetoothStateManager()
        val engine = BluetoothConnectionEngine(
            blueFalcon = BlueFalcon(falconEngine),
            bluetoothStateManager = stateManager,
            bleDebugLog = BleDebugLog(),
            deviceReadResultBus = DeviceReadResultBus(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            seams = seams,
            coroutineScope = backgroundScope
        )
        return Fixture(engine, stateManager, falconEngine, seams)
    }

    @Test
    fun startScan_whenReady_scansAndEntersScanning() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.stateManager.update(BluetoothState.Ready)

        f.engine.startScan()

        assertEquals(BluetoothState.Scanning, f.stateManager.bluetoothState.value)
        assertEquals(1, f.seams.scanCount)
    }

    @Test
    fun startScan_whenNotReady_isNoOp() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        // Default state is NeedsPermission, i.e. not Ready.
        f.engine.startScan()

        assertEquals(BluetoothState.NeedsPermission, f.stateManager.bluetoothState.value)
        assertEquals(0, f.seams.scanCount)
    }

    @Test
    fun scanOnce_whenReady_scansAndStaysReady() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.stateManager.update(BluetoothState.Ready)

        f.engine.scanOnce()

        // A one-choice scan (the browser device list) has no Scanning state and no Stop button.
        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
        assertEquals(1, f.seams.scanCount)
    }

    @Test
    fun scanOnce_whenNotReady_isNoOp() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()

        f.engine.scanOnce()

        assertEquals(BluetoothState.NeedsPermission, f.stateManager.bluetoothState.value)
        assertEquals(0, f.seams.scanCount)
    }

    @Test
    fun stopScan_whenScanning_returnsToReady() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.stateManager.update(BluetoothState.Scanning)

        f.engine.stopScan()

        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
        assertEquals(1, f.seams.stopScanCount)
    }

    @Test
    fun connect_whenSeamNotReady_abortsBeforeConnecting() = runTest(UnconfinedTestDispatcher()) {
        val seams = FakePlatformBluetoothSeams().apply { awaitReadyResult = false }
        seams.setDevices(mapOf("dev" to FakeBluetoothPeripheral(uuid = "p1")))
        val f = newFixture(seams)
        f.stateManager.update(BluetoothState.Ready)

        f.engine.connect("dev")
        advanceUntilIdle()

        // awaitReadyToConnect == false returns before any GATT connect; the finally restores Ready.
        assertEquals(0, f.falconEngine.connectCalls.size)
        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
        assertTrue(f.stateManager.connectedDevices.value.isEmpty())
        assertEquals(ConnectionProgress(), f.stateManager.connectionProgress.value)
    }

    @Test
    fun connect_whenPeripheralNeverDiscovered_failsStep1AndRestoresReady() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.stateManager.update(BluetoothState.Ready)

        f.engine.connect("missing")
        advanceTimeBy(13_000) // past the 12s scan-to-locate timeout
        advanceUntilIdle()

        assertEquals(0, f.falconEngine.connectCalls.size)
        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
        assertEquals(StepStatus.FAILED, f.stateManager.connectionProgress.value.step1)
        assertTrue(f.seams.scanCount >= 1, "ensurePeripheral should have scanned")
        assertTrue(f.seams.stopScanCount >= 1, "ensurePeripheral should have stopped scanning")
    }

    @Test
    fun connect_whenItFailsWithTheRadioOff_restoresDisabledNotReady() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        f.stateManager.update(BluetoothState.Ready)
        f.falconEngine.emitManagerState(BluetoothManagerState.NotReady)

        f.engine.connect("missing")
        advanceTimeBy(13_000) // past the 12s scan-to-locate timeout
        advanceUntilIdle()

        // With the radio off, Ready would let a later scan enter Scanning with no radio (L14).
        assertEquals(BluetoothState.Disabled, f.stateManager.bluetoothState.value)
        assertEquals(StepStatus.FAILED, f.stateManager.connectionProgress.value.step1)
    }

    @Test
    fun connect_whenServicesNeverDiscovered_retriesThenFailsStep2() = runTest(UnconfinedTestDispatcher()) {
        // Every attempt drops before services (a connect failure that the retry cannot recover).
        val seams = FakePlatformBluetoothSeams().apply { awaitServicesResult = false }
        seams.setDevices(mapOf("dev" to FakeBluetoothPeripheral(uuid = "p1")))
        val f = newFixture(seams)
        f.stateManager.update(BluetoothState.Ready)

        f.engine.connect("dev")
        advanceTimeBy(10_000) // drive the inter-attempt backoff delays
        advanceUntilIdle()

        // The connect is retried up to the bound; only after every attempt fails does step2 fail.
        assertEquals(3, f.falconEngine.connectCalls.size, "connect should be retried before giving up")
        assertEquals(3, f.falconEngine.disconnectCalls.size, "each dropped attempt tears down its half-open link")
        val progress = f.stateManager.connectionProgress.value
        assertEquals(StepStatus.COMPLETED, progress.step1)
        assertEquals(StepStatus.FAILED, progress.step2)
        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
    }

    @Test
    fun connect_retriesAndSucceedsWhenALaterAttemptEstablishesTheLink() = runTest(UnconfinedTestDispatcher()) {
        // The first two attempts drop before services; the third connects and discovers them.
        val seams = FakePlatformBluetoothSeams().apply {
            awaitServicesResults.addAll(listOf(false, false, true))
        }
        seams.setDevices(mapOf("dev" to FakeBluetoothPeripheral(uuid = "p1")))
        val f = newFixture(seams)
        f.stateManager.update(BluetoothState.Ready)

        f.engine.connect("dev")
        advanceTimeBy(10_000) // drive the inter-attempt backoff delays
        advanceUntilIdle()

        assertEquals(3, f.falconEngine.connectCalls.size, "should retry until the link establishes")
        // The two dropped attempts tear down before services. The third discovers services but this
        // fixture exposes no OTS (it is steered short of OTS discovery), so OTS init fails and tears
        // that link down too — finding #6 — rather than orphaning a live, untracked GATT link.
        assertEquals(3, f.falconEngine.disconnectCalls.size)
        // Reached service discovery on the successful attempt and proceeded past it.
        assertEquals(StepStatus.COMPLETED, f.stateManager.connectionProgress.value.step2)
        assertEquals(1, f.seams.onServicesDiscoveredCalls.size, "proceeded past service discovery")
        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
    }

    @Test
    fun connect_putsTheAttemptNumberIntoTheProgressDuringARetry() = runTest(UnconfinedTestDispatcher()) {
        // Park each attempt inside service discovery, so the per-attempt timeout ends it.
        val gate = CompletableDeferred<Unit>()
        val seams = FakePlatformBluetoothSeams().apply { awaitServicesGate = gate }
        seams.setDevices(mapOf("dev" to FakeBluetoothPeripheral(uuid = "p1")))
        val f = newFixture(seams)
        f.stateManager.update(BluetoothState.Ready)

        f.engine.connect("dev")

        // The first attempt is not a retry, so the screen shows no attempt number.
        val first = f.stateManager.connectionProgress.value
        assertEquals(StepStatus.IN_PROGRESS, first.step2)
        assertEquals(1, first.attempt)
        assertEquals(3, first.maxAttempts)
        assertFalse(first.isRetrying)

        // The 5 s attempt timeout, then the 750 ms backoff, start the second attempt.
        advanceTimeBy(6_000)
        val second = f.stateManager.connectionProgress.value
        assertEquals(2, f.falconEngine.connectCalls.size)
        assertEquals(StepStatus.IN_PROGRESS, second.step2)
        assertEquals(2, second.attempt)
        assertEquals(3, second.maxAttempts)
        assertTrue(second.isRetrying)

        // The second attempt discovers the services. The progress after the link step holds no
        // attempt number, because no link attempt is in progress then.
        gate.complete(Unit)
        advanceUntilIdle()
        val after = f.stateManager.connectionProgress.value
        assertEquals(StepStatus.COMPLETED, after.step2)
        assertEquals(0, after.attempt)
        assertFalse(after.isRetrying)
    }

    @Test
    fun disconnect_duringRetryLoop_cancelsAndCleansUp() = runTest(UnconfinedTestDispatcher()) {
        // Park the first attempt inside service discovery so the cancel lands mid retry-loop.
        val seams = FakePlatformBluetoothSeams().apply { awaitServicesGate = CompletableDeferred() }
        seams.setDevices(mapOf("dev" to FakeBluetoothPeripheral(uuid = "p1")))
        val f = newFixture(seams)
        f.stateManager.update(BluetoothState.Ready)

        // On the unconfined dispatcher the attempt runs eagerly up to the parked gate (no time advanced,
        // so the per-attempt timeout has not fired yet).
        f.engine.connect("dev")
        assertEquals(BluetoothState.Connecting("dev"), f.stateManager.bluetoothState.value)
        assertEquals(1, f.falconEngine.connectCalls.size, "parked mid-attempt after the first connect")

        f.engine.disconnect("dev")
        advanceUntilIdle()

        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
        assertEquals(ConnectionProgress(), f.stateManager.connectionProgress.value)
        assertTrue(f.stateManager.connectedDevices.value.isEmpty())
    }

    @Test
    fun disconnect_cancelsInFlightConnect_andCleansUp() = runTest(UnconfinedTestDispatcher()) {
        val seams = FakePlatformBluetoothSeams().apply { awaitReadyGate = CompletableDeferred() }
        val f = newFixture(seams)
        f.stateManager.update(BluetoothState.Ready)

        f.engine.connect("dev")
        advanceUntilIdle() // runs the attempt up to the parked awaitReadyToConnect gate

        assertEquals(BluetoothState.Connecting("dev"), f.stateManager.bluetoothState.value)

        f.engine.disconnect("dev")
        advanceUntilIdle()

        assertEquals(BluetoothState.Ready, f.stateManager.bluetoothState.value)
        assertEquals(ConnectionProgress(), f.stateManager.connectionProgress.value)
        assertTrue(f.stateManager.connectedDevices.value.isEmpty())
        assertEquals(0, f.falconEngine.connectCalls.size)
    }

    @Test
    fun managerStateChanges_areForwardedToSeam() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        advanceUntilIdle() // let the engine's managerState collector subscribe

        f.falconEngine.emitManagerState(BluetoothManagerState.Ready)
        advanceUntilIdle()
        assertEquals(BluetoothManagerState.Ready, f.seams.managerStates.last())

        f.falconEngine.emitManagerState(BluetoothManagerState.NotReady)
        advanceUntilIdle()
        assertEquals(BluetoothManagerState.NotReady, f.seams.managerStates.last())
    }

    @Test
    fun discoveredPeripherals_areForwardedToSeamAddDevice() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        advanceUntilIdle() // let the engine's peripherals collector subscribe

        val peripheral = FakeBluetoothPeripheral(uuid = "p1")
        f.falconEngine.emitPeripherals(setOf(peripheral))
        advanceUntilIdle()

        assertEquals(listOf<Any>(peripheral), f.seams.addDeviceCalls)
    }

    @Test
    fun deviceState_reflectsSeamDeviceState() = runTest(UnconfinedTestDispatcher()) {
        val f = newFixture()
        val peripheral = FakeBluetoothPeripheral(uuid = "p1")
        f.seams.setDevices(mapOf("dev" to peripheral))

        assertEquals(mapOf("dev" to peripheral), f.engine.deviceState.value.devices)
    }
}