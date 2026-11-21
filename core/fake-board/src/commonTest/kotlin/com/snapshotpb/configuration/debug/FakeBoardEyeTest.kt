package com.snapshotpb.configuration.debug

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.model.EYE_ADC_FULL_SCALE_RAW
import com.snapshotpb.configuration.model.EyeSensorState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FakeBoardEyeTest {

    private class Fixture(
        val stateManager: BluetoothStateManager,
        val board: FakeBoard,
    )

    private fun TestScope.newFixture(): Fixture {
        val stateManager = BluetoothStateManager()
        val board = FakeBoard(
            bluetoothStateManager = stateManager,
            deviceReadResultBus = DeviceReadResultBus(),
            deviceWriteResultBus = DeviceWriteResultBus(),
            scope = backgroundScope,
        )
        board.applySwitch(true)
        runCurrent()
        return Fixture(stateManager = stateManager, board = board)
    }

    private suspend fun TestScope.connectBoard(f: Fixture) {
        f.board.handle(BluetoothRequest.Connect(FAKE_DEVICE_ID))
        advanceTimeBy(CONNECT_MILLIS)
        runCurrent()
        assertTrue(FAKE_DEVICE_ID in f.stateManager.connectedDevices.value)
    }

    private fun TestScope.startWatcher(
        stateManager: BluetoothStateManager,
    ): Pair<Job, MutableList<EyeReadingState?>> {
        val states = mutableListOf<EyeReadingState?>()
        val job = backgroundScope.launch {
            stateManager.eyeReading(FAKE_DEVICE_ID).collect { states += it }
        }
        return job to states
    }

    @Test
    fun simulatedEyeLevel_followsExpectedTriangleWave() {
        assertEquals(600, simulatedEyeLevel(0))
        assertEquals(2000, simulatedEyeLevel(10))
        assertEquals(3400, simulatedEyeLevel(20))
        assertEquals(600, simulatedEyeLevel(40))

        for (tick in 0 until 20) {
            assertTrue(
                simulatedEyeLevel(tick + 1) > simulatedEyeLevel(tick),
                "Expected wave to go up from tick $tick to ${tick + 1}",
            )
        }
        for (tick in 20 until 40) {
            assertTrue(
                simulatedEyeLevel(tick + 1) < simulatedEyeLevel(tick),
                "Expected wave to go down from tick $tick to ${tick + 1}",
            )
        }
    }

    @Test
    fun connectedWithWatcher_emitsActiveReadingsAcrossThresholdAtTenHz() = runTest {
        val f = newFixture()
        connectBoard(f)

        val (_, states) = startWatcher(f.stateManager)
        advanceTimeBy(SAMPLE_MILLIS)
        runCurrent()

        val readings = states.filterIsInstance<EyeReadingState.Reading>().map { it.reading }
        assertTrue(readings.size in 45..55, "Expected 45..55 readings in 5 s, got ${readings.size}")
        assertTrue(readings.all { it.state == EyeSensorState.ACTIVE })
        assertTrue(readings.all { it.fullScaleRaw == EYE_ADC_FULL_SCALE_RAW })
        val levels = readings.mapNotNull { it.levelRaw }
        assertEquals(readings.size, levels.size)
        assertTrue(levels.all { it in 600..3400 })
        assertTrue(levels.any { it < DEFAULT_THRESHOLD_RAW })
        assertTrue(levels.any { it > DEFAULT_THRESHOLD_RAW })
    }

    @Test
    fun watcherBeforeConnect_receivesOnlyNull() = runTest {
        val f = newFixture()

        val (_, states) = startWatcher(f.stateManager)
        advanceTimeBy(SAMPLE_MILLIS)
        runCurrent()

        assertEquals(listOf<EyeReadingState?>(null), states)
    }

    @Test
    fun lastWatcherStops_clearsStateSoNextWatcherStartsAtWaiting() = runTest {
        val f = newFixture()
        connectBoard(f)

        val (watcher1, states1) = startWatcher(f.stateManager)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(states1.any { it is EyeReadingState.Reading })

        watcher1.cancelAndJoin()
        advanceTimeBy(1_000L)
        runCurrent()

        val (_, states2) = startWatcher(f.stateManager)
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(EyeReadingState.Waiting, states2.first())
        assertTrue(states2.drop(1).any { it is EyeReadingState.Reading })
    }

    @Test
    fun disconnectRequest_emitsNullAndStopsReadings() = runTest {
        val f = newFixture()
        connectBoard(f)

        val (_, states) = startWatcher(f.stateManager)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(states.any { it is EyeReadingState.Reading })

        f.board.handle(BluetoothRequest.Disconnect(FAKE_DEVICE_ID))
        runCurrent()
        assertNull(states.last())
        val countAfterDisconnect = states.size

        advanceTimeBy(SAMPLE_MILLIS)
        runCurrent()
        assertEquals(countAfterDisconnect, states.size)
    }

    @Test
    fun applySwitchOff_stopsSimulatedEye() = runTest {
        val f = newFixture()
        connectBoard(f)

        val (_, states) = startWatcher(f.stateManager)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(states.any { it is EyeReadingState.Reading })

        f.board.applySwitch(false)
        runCurrent()
        val countAfterSwitchOff = states.size

        advanceTimeBy(SAMPLE_MILLIS)
        runCurrent()
        assertTrue(states.drop(countAfterSwitchOff).none { it is EyeReadingState.Reading })
    }

    private companion object {
        const val CONNECT_MILLIS = 5_000L
        const val SAMPLE_MILLIS = 5_000L
        const val DEFAULT_THRESHOLD_RAW = 2047
    }
}
