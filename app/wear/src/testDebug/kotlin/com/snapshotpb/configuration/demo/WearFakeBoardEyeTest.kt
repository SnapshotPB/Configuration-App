package com.snapshotpb.configuration.demo

import com.snapshotpb.configuration.bluetooth.BluetoothRequest
import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.DeviceReadResultBus
import com.snapshotpb.configuration.bluetooth.DeviceWriteResultBus
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.model.EYE_ADC_FULL_SCALE_RAW
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Unit tests for the simulated eye level of [WearFakeBoard]. */
@OptIn(ExperimentalCoroutinesApi::class)
class WearFakeBoardEyeTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var bluetoothStateManager: BluetoothStateManager
    private lateinit var board: WearFakeBoard

    @BeforeTest
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        bluetoothStateManager = BluetoothStateManager()
        board = WearFakeBoard(
            bluetoothStateManager = bluetoothStateManager,
            deviceReadResultBus = DeviceReadResultBus(),
            deviceWriteResultBus = DeviceWriteResultBus(),
        )
    }

    @AfterTest
    fun tearDown() {
        board.applySwitch(false)
        Dispatchers.resetMain()
    }

    @Test
    fun simulatedEyeLevelRaw_followsTriangleWaveAcrossThreshold() {
        assertEquals(600, simulatedEyeLevelRaw(0))
        assertEquals(2050, simulatedEyeLevelRaw(10))
        assertEquals(3500, simulatedEyeLevelRaw(20))
        assertEquals(600, simulatedEyeLevelRaw(40))

        for (tick in -40..200) {
            val level = simulatedEyeLevelRaw(tick)
            assertTrue(level in 600..3500, "Tick $tick gave $level outside 600..3500")
        }

        for (tick in 0 until 20) {
            assertTrue(simulatedEyeLevelRaw(tick) < simulatedEyeLevelRaw(tick + 1))
        }
        for (tick in 20 until 40) {
            assertTrue(simulatedEyeLevelRaw(tick) > simulatedEyeLevelRaw(tick + 1))
        }

        val periodValues = (0 until 40).map { simulatedEyeLevelRaw(it) }
        assertTrue(periodValues.any { it <= 2047 })
        assertTrue(periodValues.any { it > 2047 })
    }

    @Test
    fun connectedAndWatched_emitsActiveLevelsAtTenHz() = runTest(testDispatcher) {
        connectDemoBoard()
        val values = mutableListOf<EyeReadingState?>()

        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bluetoothStateManager.eyeReading(DEMO_ID).collect { values.add(it) }
        }
        runCurrent()

        assertEquals(1, bluetoothStateManager.eyeWatchers.value[DEMO_ID])
        assertEquals(
            EyeReadingState.Reading(
                EyeReading(600, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.ACTIVE),
            ),
            values.last(),
        )

        advanceTimeBy(100)
        runCurrent()
        assertEquals(
            EyeReadingState.Reading(
                EyeReading(simulatedEyeLevelRaw(1), EYE_ADC_FULL_SCALE_RAW, EyeSensorState.ACTIVE),
            ),
            values.last(),
        )

        collector.cancel()
        board.handle(BluetoothRequest.Disconnect(DEMO_ID))
        runCurrent()
    }

    @Test
    fun zeroWatchers_stopsAndClearsReadingForNextWatcher() = runTest(testDispatcher) {
        connectDemoBoard()
        val firstValues = mutableListOf<EyeReadingState?>()

        val firstCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bluetoothStateManager.eyeReading(DEMO_ID).collect { firstValues.add(it) }
        }
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        firstCollector.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(DEMO_ID in bluetoothStateManager.eyeWatchers.value)

        val secondValues = mutableListOf<EyeReadingState?>()
        val secondCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bluetoothStateManager.eyeReading(DEMO_ID).collect { secondValues.add(it) }
        }
        assertEquals(listOf<EyeReadingState?>(EyeReadingState.Waiting), secondValues)

        runCurrent()
        assertEquals(
            EyeReadingState.Reading(
                EyeReading(600, EYE_ADC_FULL_SCALE_RAW, EyeSensorState.ACTIVE),
            ),
            secondValues.last(),
        )

        secondCollector.cancel()
        board.handle(BluetoothRequest.Disconnect(DEMO_ID))
        runCurrent()
    }

    @Test
    fun notConnected_emitsOnlyNull() = runTest(testDispatcher) {
        board.applySwitch(true)
        val values = mutableListOf<EyeReadingState?>()

        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bluetoothStateManager.eyeReading(DEMO_ID).collect { values.add(it) }
        }
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf<EyeReadingState?>(null), values)

        collector.cancel()
        board.applySwitch(false)
        runCurrent()
    }

    @Test
    fun disconnectWhileWatched_emitsNullLast() = runTest(testDispatcher) {
        connectDemoBoard()
        val values = mutableListOf<EyeReadingState?>()

        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bluetoothStateManager.eyeReading(DEMO_ID).collect { values.add(it) }
        }
        runCurrent()
        assertTrue(values.last() is EyeReadingState.Reading)

        board.handle(BluetoothRequest.Disconnect(DEMO_ID))
        runCurrent()
        assertNull(values.last())

        collector.cancel()
        runCurrent()
    }

    @Test
    fun switchOffWhileWatched_stopsNewReadings() = runTest(testDispatcher) {
        connectDemoBoard()
        val values = mutableListOf<EyeReadingState?>()

        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bluetoothStateManager.eyeReading(DEMO_ID).collect { values.add(it) }
        }
        runCurrent()
        assertTrue(values.last() is EyeReadingState.Reading)

        board.applySwitch(false)
        runCurrent()
        val readingsAfterSwitchOff = values.filterIsInstance<EyeReadingState.Reading>().size

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            readingsAfterSwitchOff,
            values.filterIsInstance<EyeReadingState.Reading>().size,
        )

        collector.cancel()
        runCurrent()
    }

    private suspend fun TestScope.connectDemoBoard() {
        board.applySwitch(true)
        board.handle(BluetoothRequest.Connect(DEMO_ID))
        advanceTimeBy(3_000)
        runCurrent()
    }

    private companion object {
        const val DEMO_ID = "00000000-0000-0000-0000-000000001234"
    }
}
