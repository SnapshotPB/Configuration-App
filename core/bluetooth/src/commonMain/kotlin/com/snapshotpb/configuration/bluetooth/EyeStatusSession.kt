package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.bluetooth.eye.decodeEyeStatus
import com.snapshotpb.configuration.bluetooth.log.BluetoothLogEvent
import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the eye status notifications of one BLE link.
 *
 * This session subscribes to [characteristic] when at least one watcher collects
 * [BluetoothStateManager.eyeReading] for [boardId], and unsubscribes after
 * [UNSUBSCRIBE_DELAY_MILLIS] with no watcher. It pauses notifications during a firmware upload
 * through [pauseForUpload] and [resumeAfterUpload]. Each CCC write runs under [writeMutex], so it
 * never overlaps an OTS or SMP transfer.
 */
internal class EyeStatusSession(
    private val blueFalcon: BlueFalcon,
    private val peripheral: BluetoothPeripheral,
    private val characteristic: BluetoothCharacteristic,
    private val boardId: String,
    private val bluetoothStateManager: BluetoothStateManager,
    private val writeMutex: Mutex,
    private val logEvent: (BluetoothLogEvent, String?) -> Unit,
) {
    /** True while a firmware upload runs. */
    private val paused = MutableStateFlow(false)

    /** True after a CCC write turned the notifications on. Read and changed only while [writeMutex] is held. */
    private var notifying = false

    /** True after a failed subscribe. Read and changed only while [writeMutex] is held. */
    private var failed = false

    /** Runs the notification collector and the watcher loop until the caller cancels this call. */
    suspend fun run(): Nothing = coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) {
            characteristic.notifications
                .mapNotNull { decodeEyeStatus(it) }
                .collectLatest { reading ->
                    bluetoothStateManager.setEyeReading(boardId, EyeReadingState.Reading(reading))
                    delay(READING_STALE_MILLIS)
                    bluetoothStateManager.setEyeReading(boardId, EyeReadingState.Waiting)
                }
        }
        combine(
            bluetoothStateManager.eyeWatcherCount(boardId).map { it > 0 },
            paused,
        ) { watched, isPaused -> watched to isPaused }
            .distinctUntilChanged()
            .collectLatest { (watched, isPaused) ->
                if (!watched && !isPaused) {
                    delay(UNSUBSCRIBE_DELAY_MILLIS)
                }
                writeMutex.withLock {
                    withContext(NonCancellable) {
                        applyNotifications(on = watched && !isPaused, detail = null)
                    }
                }
            }
        awaitCancellation()
    }

    /**
     * Pauses eye notifications before a firmware upload starts.
     *
     * Call this function while [writeMutex] is held.
     */
    suspend fun pauseForUpload() {
        paused.value = true
        withContext(NonCancellable) {
            applyNotifications(on = false, detail = FIRMWARE_DETAIL)
        }
    }

    /**
     * Resumes eye notifications when a firmware upload ends, also after a failure.
     *
     * Call this function while [writeMutex] is held. The watcher loop turns notifications on again
     * after the caller releases [writeMutex] when at least one watcher stays active.
     */
    fun resumeAfterUpload() {
        paused.value = false
    }

    /**
     * Writes the CCC of [characteristic] when [on] differs from [notifying].
     *
     * Call this function only while [writeMutex] is held.
     */
    private suspend fun applyNotifications(on: Boolean, detail: String?) {
        if (notifying == on) return
        val failureCode: String? = try {
            val completed = withTimeoutOrNull(CCC_WRITE_LIMIT_MILLIS) {
                blueFalcon.notifyCharacteristic(peripheral, characteristic, notify = on)
            }
            if (completed == null) TIMEOUT_FAILURE_CODE else null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error::class.simpleName ?: UNKNOWN_FAILURE_CODE
        }

        if (failureCode == null) {
            notifying = on
            if (on) {
                log.d { "Eye status notifications on" }
                logEvent(BluetoothLogEvent.EYE_SUBSCRIBED, null)
                if (failed) {
                    failed = false
                    bluetoothStateManager.setEyeReading(boardId, EyeReadingState.Waiting)
                }
            } else {
                log.d { "Eye status notifications off" }
                logEvent(BluetoothLogEvent.EYE_UNSUBSCRIBED, detail)
            }
        } else if (on) {
            failed = true
            bluetoothStateManager.setEyeReading(boardId, EyeReadingState.Failed)
            log.d { "Eye status subscribe failed: $failureCode" }
            logEvent(BluetoothLogEvent.EYE_SUBSCRIBE_FAILED, failureCode)
        } else {
            log.d { "Eye status unsubscribe failed: $failureCode" }
            logEvent(BluetoothLogEvent.EYE_UNSUBSCRIBE_FAILED, failureCode)
        }
    }

    companion object {
        /** Delay before this session turns notifications off after the last watcher stops. */
        const val UNSUBSCRIBE_DELAY_MILLIS = 2_000L

        /** Time with no valid notification before the state returns to [EyeReadingState.Waiting]. */
        const val READING_STALE_MILLIS = 1_000L

        /** Time limit for one CCC write call. */
        const val CCC_WRITE_LIMIT_MILLIS = 2_000L

        private const val TIMEOUT_FAILURE_CODE = "timeout"
        private const val UNKNOWN_FAILURE_CODE = "Unknown"
        private const val FIRMWARE_DETAIL = "firmware"

        private val log = Arbor.tag("BluetoothManager")
    }
}
