package com.snapshotpb.configuration.common.screen.delegate

import com.snapshotpb.configuration.bluetooth.BluetoothStateManager
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.common.testDeviceId
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Unit tests for [EyeReadingDelegateImpl]. */
@OptIn(ExperimentalCoroutinesApi::class)
class EyeReadingDelegateTest {

    private val deviceId = testDeviceId.toString()

    @Test
    fun eyeReading_isNullWhileTheBoardIsNotConnected() = runTest {
        val bluetoothStateManager = BluetoothStateManager()
        val delegate = EyeReadingDelegateImpl(bluetoothStateManager)
        val values = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            delegate.eyeReading(deviceId).collect { values.add(it) }
        }

        assertEquals(listOf<EyeReadingState?>(null), values)
        job.cancelAndJoin()
    }

    @Test
    fun eyeReading_isWaitingWhenConnectedWithNoState() = runTest {
        val bluetoothStateManager = BluetoothStateManager()
        bluetoothStateManager.deviceConnected(deviceId)
        val delegate = EyeReadingDelegateImpl(bluetoothStateManager)
        val values = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            delegate.eyeReading(deviceId).collect { values.add(it) }
        }

        assertEquals(listOf<EyeReadingState?>(EyeReadingState.Waiting), values)
        job.cancelAndJoin()
    }

    @Test
    fun eyeReading_givesTheRecordedState() = runTest {
        val bluetoothStateManager = BluetoothStateManager()
        val expected = EyeReadingState.Reading(
            EyeReading(levelRaw = 1000, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE),
        )
        bluetoothStateManager.deviceConnected(deviceId)
        bluetoothStateManager.setEyeReading(deviceId, expected)
        val delegate = EyeReadingDelegateImpl(bluetoothStateManager)
        val values = mutableListOf<EyeReadingState?>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            delegate.eyeReading(deviceId).collect { values.add(it) }
        }

        assertEquals(listOf<EyeReadingState?>(expected), values)

        bluetoothStateManager.setEyeReading(deviceId, EyeReadingState.Unsupported)
        assertEquals(listOf<EyeReadingState?>(expected, EyeReadingState.Unsupported), values)
        job.cancelAndJoin()
    }

    @Test
    fun eyeReading_collectorIsAWatcherUntilCancelled() = runTest {
        val bluetoothStateManager = BluetoothStateManager()
        val delegate = EyeReadingDelegateImpl(bluetoothStateManager)

        val firstJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            delegate.eyeReading(deviceId).collect {}
        }
        assertEquals(1, bluetoothStateManager.eyeWatchers.value[deviceId])

        val secondJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            delegate.eyeReading(deviceId).collect {}
        }
        assertEquals(2, bluetoothStateManager.eyeWatchers.value[deviceId])

        firstJob.cancelAndJoin()
        assertEquals(1, bluetoothStateManager.eyeWatchers.value[deviceId])

        secondJob.cancelAndJoin()
        assertFalse(deviceId in bluetoothStateManager.eyeWatchers.value)
    }
}
