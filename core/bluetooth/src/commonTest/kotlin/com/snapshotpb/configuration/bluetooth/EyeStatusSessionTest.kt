package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.log.BluetoothLogEvent
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import snapshotpb.v1.EyeStatus
import snapshotpb.v1.EyeSensorState as ProtoEyeSensorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EyeStatusSessionTest {

    private class Fixture(
        val stateManager: BluetoothStateManager,
        val falconEngine: FakeBlueFalconEngine,
        val eyeService: FakeEyeStatusService,
        val writeMutex: Mutex,
        val events: MutableList<Pair<BluetoothLogEvent, String?>>,
        val session: EyeStatusSession,
        val sessionJob: Job,
    ) {
        fun notifyPair(on: Boolean): Pair<BluetoothCharacteristic, Boolean> = eyeService.status to on
    }

    private fun TestScope.newFixture(): Fixture {
        val stateManager = BluetoothStateManager().apply { deviceConnected(BOARD) }
        val falconEngine = FakeBlueFalconEngine(backgroundScope)
        val eyeService = FakeEyeStatusService()
        val peripheral = FakeBluetoothPeripheral(
            uuid = PERIPHERAL_UUID,
            services = listOf(eyeService.service),
            characteristics = listOf(eyeService.status),
        )
        val writeMutex = Mutex()
        val events = mutableListOf<Pair<BluetoothLogEvent, String?>>()
        val session = EyeStatusSession(
            blueFalcon = BlueFalcon(falconEngine),
            peripheral = peripheral,
            characteristic = eyeService.status,
            boardId = BOARD,
            bluetoothStateManager = stateManager,
            writeMutex = writeMutex,
            logEvent = { event, detail -> events += event to detail },
        )
        val sessionJob = backgroundScope.launch { session.run() }
        return Fixture(
            stateManager = stateManager,
            falconEngine = falconEngine,
            eyeService = eyeService,
            writeMutex = writeMutex,
            events = events,
            session = session,
            sessionJob = sessionJob,
        )
    }

    private fun TestScope.startWatcher(
        stateManager: BluetoothStateManager,
    ): Pair<Job, MutableList<EyeReadingState?>> {
        val states = mutableListOf<EyeReadingState?>()
        val job = backgroundScope.launch {
            stateManager.eyeReading(BOARD).collect { states += it }
        }
        return job to states
    }

    private fun logEntry(
        event: BluetoothLogEvent,
        detail: String? = null,
    ): Pair<BluetoothLogEvent, String?> = event to detail

    private fun encodeEyeStatus(
        levelRaw: UInt? = 2048U,
        fullScaleRaw: UInt = 4095U,
        state: ProtoEyeSensorState = ProtoEyeSensorState.EYE_SENSOR_STATE_ACTIVE,
    ): ByteArray = ProtoBuf.encodeToByteArray(
        EyeStatus.serializer(),
        EyeStatus(levelRaw = levelRaw, fullScaleRaw = fullScaleRaw, state = state),
    )

    @Test
    fun noWatcher_writesNoCccAfterTenSeconds() = runTest {
        val f = newFixture()

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(f.falconEngine.notifyCalls.isEmpty())
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun firstWatcher_enablesNotificationsImmediatelyAndLogsSubscribed() = runTest {
        val f = newFixture()
        runCurrent()

        startWatcher(f.stateManager)
        runCurrent()

        assertEquals(listOf(f.notifyPair(true)), f.falconEngine.notifyCalls)
        assertEquals(listOf(logEntry(BluetoothLogEvent.EYE_SUBSCRIBED)), f.events)
    }

    @Test
    fun secondWatcher_doesNotWriteSecondCcc() = runTest {
        val f = newFixture()
        startWatcher(f.stateManager)
        runCurrent()

        startWatcher(f.stateManager)
        runCurrent()

        assertEquals(listOf(f.notifyPair(true)), f.falconEngine.notifyCalls)
        assertEquals(listOf(logEntry(BluetoothLogEvent.EYE_SUBSCRIBED)), f.events)
    }

    @Test
    fun lastWatcherStops_disablesNotificationsAfterUnsubscribeDelay() = runTest {
        val f = newFixture()
        val (watcher, _) = startWatcher(f.stateManager)
        runCurrent()

        watcher.cancelAndJoin()
        advanceTimeBy(EyeStatusSession.UNSUBSCRIBE_DELAY_MILLIS - 1L)
        runCurrent()

        assertEquals(listOf(f.notifyPair(true)), f.falconEngine.notifyCalls)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(
            listOf(f.notifyPair(true), f.notifyPair(false)),
            f.falconEngine.notifyCalls,
        )
        assertEquals(
            listOf(
                logEntry(BluetoothLogEvent.EYE_SUBSCRIBED),
                logEntry(BluetoothLogEvent.EYE_UNSUBSCRIBED),
            ),
            f.events,
        )
    }

    @Test
    fun newWatcherWithinUnsubscribeDelay_cancelsOff() = runTest {
        val f = newFixture()
        val (watcher1, _) = startWatcher(f.stateManager)
        runCurrent()

        watcher1.cancelAndJoin()
        advanceTimeBy(1_000L)
        runCurrent()

        startWatcher(f.stateManager)
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(listOf(f.notifyPair(true)), f.falconEngine.notifyCalls)
        assertEquals(listOf(logEntry(BluetoothLogEvent.EYE_SUBSCRIBED)), f.events)
    }

    @Test
    fun notification_recordsReadingAndReturnsToWaitingAfterStaleDelay() = runTest {
        val f = newFixture()
        val (_, states) = startWatcher(f.stateManager)
        runCurrent()

        val expected = EyeReading(levelRaw = 2048, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE)
        f.eyeService.send(encodeEyeStatus(levelRaw = 2048U, fullScaleRaw = 4095U))
        runCurrent()

        assertEquals(EyeReadingState.Reading(expected), states.last())

        advanceTimeBy(EyeStatusSession.READING_STALE_MILLIS - 1L)
        runCurrent()
        assertEquals(EyeReadingState.Reading(expected), states.last())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(EyeReadingState.Waiting, states.last())
    }

    @Test
    fun badValue_recordsNothingAndDoesNotRestartStaleDelay() = runTest {
        val f = newFixture()
        val (_, states) = startWatcher(f.stateManager)
        runCurrent()

        val expected = EyeReading(levelRaw = 1024, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE)
        f.eyeService.send(encodeEyeStatus(levelRaw = 1024U, fullScaleRaw = 4095U))
        runCurrent()
        assertEquals(EyeReadingState.Reading(expected), states.last())

        advanceTimeBy(500L)
        f.eyeService.send(byteArrayOf(0xFF.toByte()))
        runCurrent()
        assertEquals(EyeReadingState.Reading(expected), states.last())

        advanceTimeBy(EyeStatusSession.READING_STALE_MILLIS - 500L)
        runCurrent()
        assertEquals(EyeReadingState.Waiting, states.last())
    }

    @Test
    fun cccWrite_waitsForWriteMutex() = runTest {
        val f = newFixture()
        runCurrent()

        f.writeMutex.lock()
        startWatcher(f.stateManager)
        runCurrent()
        assertTrue(f.falconEngine.notifyCalls.isEmpty())

        f.writeMutex.unlock()
        runCurrent()
        assertEquals(listOf(f.notifyPair(true)), f.falconEngine.notifyCalls)
    }

    @Test
    fun pauseForUpload_disablesImmediatelyBlocksWatchersAndResumesAfterUnlock() = runTest {
        val f = newFixture()
        val (watcher1, _) = startWatcher(f.stateManager)
        runCurrent()

        f.writeMutex.lock()
        f.session.pauseForUpload()
        runCurrent()

        assertEquals(
            listOf(f.notifyPair(true), f.notifyPair(false)),
            f.falconEngine.notifyCalls,
        )
        assertEquals(
            listOf(
                logEntry(BluetoothLogEvent.EYE_SUBSCRIBED),
                logEntry(BluetoothLogEvent.EYE_UNSUBSCRIBED, "firmware"),
            ),
            f.events,
        )

        watcher1.cancelAndJoin()
        f.writeMutex.unlock()
        startWatcher(f.stateManager)
        runCurrent()
        assertEquals(
            listOf(f.notifyPair(true), f.notifyPair(false)),
            f.falconEngine.notifyCalls,
        )

        f.writeMutex.lock()
        f.session.resumeAfterUpload()
        runCurrent()
        assertEquals(
            listOf(f.notifyPair(true), f.notifyPair(false)),
            f.falconEngine.notifyCalls,
        )

        f.writeMutex.unlock()
        runCurrent()
        assertEquals(
            listOf(f.notifyPair(true), f.notifyPair(false), f.notifyPair(true)),
            f.falconEngine.notifyCalls,
        )
        assertEquals(
            listOf(
                logEntry(BluetoothLogEvent.EYE_SUBSCRIBED),
                logEntry(BluetoothLogEvent.EYE_UNSUBSCRIBED, "firmware"),
                logEntry(BluetoothLogEvent.EYE_SUBSCRIBED),
            ),
            f.events,
        )
    }

    @Test
    fun resumeAfterUpload_withNoWatcher_doesNotEnableNotifications() = runTest {
        val f = newFixture()
        runCurrent()

        f.writeMutex.lock()
        f.session.pauseForUpload()
        f.session.resumeAfterUpload()
        f.writeMutex.unlock()

        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(f.falconEngine.notifyCalls.isEmpty())
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun subscribeThrows_recordsFailedAndNextWatcherCycleRecoversToWaiting() = runTest {
        val f = newFixture()
        f.falconEngine.onNotifyCharacteristic = { _, _ -> throw IllegalStateException("gatt failure") }

        val (watcher1, states1) = startWatcher(f.stateManager)
        runCurrent()

        assertEquals(EyeReadingState.Failed, states1.last())
        assertEquals(
            listOf(logEntry(BluetoothLogEvent.EYE_SUBSCRIBE_FAILED, "IllegalStateException")),
            f.events,
        )

        watcher1.cancelAndJoin()
        advanceTimeBy(EyeStatusSession.UNSUBSCRIBE_DELAY_MILLIS)
        runCurrent()

        f.falconEngine.onNotifyCharacteristic = null
        val (_, states2) = startWatcher(f.stateManager)
        runCurrent()

        assertEquals(EyeReadingState.Waiting, states2.last())
        assertEquals(
            listOf(
                logEntry(BluetoothLogEvent.EYE_SUBSCRIBE_FAILED, "IllegalStateException"),
                logEntry(BluetoothLogEvent.EYE_SUBSCRIBED),
            ),
            f.events,
        )
    }

    @Test
    fun subscribeTimeout_logsTimeoutAndReleasesWriteMutex() = runTest {
        val f = newFixture()
        f.falconEngine.onNotifyCharacteristic = { _, notify ->
            if (notify) awaitCancellation()
        }

        val (_, states) = startWatcher(f.stateManager)
        runCurrent()
        assertTrue(f.writeMutex.isLocked)

        advanceTimeBy(EyeStatusSession.CCC_WRITE_LIMIT_MILLIS - 1L)
        runCurrent()
        assertTrue(f.writeMutex.isLocked)
        assertTrue(f.events.isEmpty())

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(EyeReadingState.Failed, states.last())
        assertEquals(listOf(logEntry(BluetoothLogEvent.EYE_SUBSCRIBE_FAILED, "timeout")), f.events)
        assertFalse(f.writeMutex.isLocked)
    }

    @Test
    fun cancelledSession_ignoresFurtherNotifications() = runTest {
        val f = newFixture()
        val (_, states) = startWatcher(f.stateManager)
        runCurrent()

        val initialReading = EyeReading(levelRaw = 1000, fullScaleRaw = 4095, state = EyeSensorState.ACTIVE)
        f.eyeService.send(encodeEyeStatus(levelRaw = 1000U, fullScaleRaw = 4095U))
        runCurrent()
        assertEquals(EyeReadingState.Reading(initialReading), states.last())

        f.sessionJob.cancelAndJoin()

        f.eyeService.send(encodeEyeStatus(levelRaw = 3000U, fullScaleRaw = 4095U))
        advanceTimeBy(EyeStatusSession.READING_STALE_MILLIS * 2L)
        runCurrent()

        assertEquals(EyeReadingState.Reading(initialReading), states.last())
    }

    private companion object {
        const val BOARD = "board-1"
        const val PERIPHERAL_UUID = "peripheral-1"
    }
}
