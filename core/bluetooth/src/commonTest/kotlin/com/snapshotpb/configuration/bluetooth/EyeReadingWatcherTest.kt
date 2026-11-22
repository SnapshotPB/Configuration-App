package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.EYE_ADC_FULL_SCALE_RAW
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Unit tests for the eye state and watcher count API of [BluetoothStateManager]. */
@OptIn(ExperimentalCoroutinesApi::class)
class EyeReadingWatcherTest {

    private val sampleReading = EyeReadingState.Reading(
        EyeReading(
            levelRaw = 2048,
            fullScaleRaw = EYE_ADC_FULL_SCALE_RAW,
            state = EyeSensorState.ACTIVE,
        ),
    )

    @Test
    fun notConnected_emitsNullAndCountsOneWatcher() = runTest {
        val manager = BluetoothStateManager()
        val values = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeReading(BOARD_A).collect { values.add(it) }
        }

        assertEquals(listOf<EyeReadingState?>(null), values)
        assertEquals(1, manager.eyeWatchers.value[BOARD_A])

        job.cancelAndJoin()
        assertFalse(BOARD_A in manager.eyeWatchers.value)
    }

    @Test
    fun twoCollectors_incrementAndDecrementWatcherCount() = runTest {
        val manager = BluetoothStateManager()

        val job1 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeReading(BOARD_A).collect {}
        }
        val job2 = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeReading(BOARD_A).collect {}
        }

        assertEquals(2, manager.eyeWatchers.value[BOARD_A])

        job1.cancelAndJoin()
        assertEquals(1, manager.eyeWatchers.value[BOARD_A])

        job2.cancelAndJoin()
        assertFalse(BOARD_A in manager.eyeWatchers.value)
    }

    @Test
    fun collectorThatStopsEarlyOrThrows_returnsCountToZero() = runTest {
        val manager = BluetoothStateManager()

        assertNull(manager.eyeReading(BOARD_A).first())
        assertFalse(BOARD_A in manager.eyeWatchers.value)

        assertEquals(listOf<EyeReadingState?>(null), manager.eyeReading(BOARD_A).take(1).toList())
        assertFalse(BOARD_A in manager.eyeWatchers.value)

        assertFailsWith<IllegalStateException> {
            manager.eyeReading(BOARD_A).collect {
                throw IllegalStateException("stop")
            }
        }
        assertFalse(BOARD_A in manager.eyeWatchers.value)
    }

    @Test
    fun connectedBoard_transitionsBetweenWaitingAndRecordedState() = runTest {
        val manager = BluetoothStateManager()
        manager.deviceConnected(BOARD_A)
        val values = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeReading(BOARD_A).collect { values.add(it) }
        }

        assertEquals(listOf<EyeReadingState?>(EyeReadingState.Waiting), values)

        manager.setEyeReading(BOARD_A, sampleReading)
        assertEquals(listOf<EyeReadingState?>(EyeReadingState.Waiting, sampleReading), values)

        manager.clearEyeReading(BOARD_A)
        assertEquals(
            listOf<EyeReadingState?>(EyeReadingState.Waiting, sampleReading, EyeReadingState.Waiting),
            values,
        )

        job.cancelAndJoin()
    }

    @Test
    fun deviceDisconnected_keepsRecordedStateOverLiveReReadAndKeepsWatcher() = runTest {
        val manager = BluetoothStateManager()
        manager.deviceConnected(BOARD_A)
        val values = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeReading(BOARD_A).collect { values.add(it) }
        }
        manager.setEyeReading(BOARD_A, EyeReadingState.Unsupported)

        manager.deviceDisconnected(BOARD_A)
        assertEquals(1, manager.eyeWatchers.value[BOARD_A])
        assertEquals(listOf<EyeReadingState?>(EyeReadingState.Waiting, EyeReadingState.Unsupported, null), values)

        manager.deviceConnected(BOARD_A)
        assertEquals(1, manager.eyeWatchers.value[BOARD_A])
        assertEquals(
            listOf<EyeReadingState?>(
                EyeReadingState.Waiting,
                EyeReadingState.Unsupported,
                null,
                EyeReadingState.Unsupported,
            ),
            values,
        )

        job.cancelAndJoin()
    }

    @Test
    fun clearEyeReading_resetsStateToWaitingAfterTeardownAndReconnect() = runTest {
        val manager = BluetoothStateManager()
        manager.deviceConnected(BOARD_A)
        val values = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeReading(BOARD_A).collect { values.add(it) }
        }
        manager.setEyeReading(BOARD_A, sampleReading)

        manager.deviceDisconnected(BOARD_A)
        manager.clearEyeReading(BOARD_A)
        assertEquals(listOf<EyeReadingState?>(EyeReadingState.Waiting, sampleReading, null), values)

        manager.deviceConnected(BOARD_A)
        assertEquals(
            listOf<EyeReadingState?>(EyeReadingState.Waiting, sampleReading, null, EyeReadingState.Waiting),
            values,
        )

        job.cancelAndJoin()
    }

    @Test
    fun watcherCountsAndStates_areIsolatedPerBoard() = runTest {
        val manager = BluetoothStateManager()
        manager.deviceConnected(BOARD_A)
        manager.deviceConnected(BOARD_B)
        val valuesA = mutableListOf<EyeReadingState?>()

        val jobA = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeReading(BOARD_A).collect { valuesA.add(it) }
        }

        assertEquals(1, manager.eyeWatchers.value[BOARD_A])
        assertFalse(BOARD_B in manager.eyeWatchers.value)

        manager.setEyeReading(BOARD_B, sampleReading)
        assertEquals(listOf<EyeReadingState?>(EyeReadingState.Waiting), valuesA)

        jobA.cancelAndJoin()
    }

    @Test
    fun eyeWatcherCount_emitsZeroThenOneThenZero() = runTest {
        val manager = BluetoothStateManager()
        val counts = mutableListOf<Int>()

        val countJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeWatcherCount(BOARD_A).collect { counts.add(it) }
        }
        assertEquals(listOf(0), counts)

        val watcherJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.eyeReading(BOARD_A).collect {}
        }
        assertEquals(listOf(0, 1), counts)

        watcherJob.cancelAndJoin()
        assertEquals(listOf(0, 1, 0), counts)

        countJob.cancelAndJoin()
    }

    private companion object {
        const val BOARD_A = "board-a"
        const val BOARD_B = "board-b"
    }
}
