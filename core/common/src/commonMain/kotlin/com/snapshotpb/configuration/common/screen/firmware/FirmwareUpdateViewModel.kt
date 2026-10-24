package com.snapshotpb.configuration.common.screen.firmware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snapshotpb.configuration.firmware.FirmwareStatus
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.store.DeviceStore
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/**
 * The firmware update screen of the board [deviceId].
 *
 * The update runs in the [coordinator], not in this view model. When the user leaves the screen,
 * the update continues, and the Firmware section of the configuration screen opens the screen
 * again. Each action checks [state] first, so a late tap on a button that is gone does nothing.
 */
class FirmwareUpdateViewModel(
    private val deviceId: String,
    private val coordinator: FirmwareUpdateCoordinator,
    deviceStore: DeviceStore,
) : ViewModel() {

    /** The name of the board, or `null` when the store does not know the board. */
    val deviceName: Flow<String?> = deviceStore.knownDevices
        .map { devices -> devices.firstOrNull { it.id.toString() == deviceId }?.name }
        .distinctUntilChanged()

    /**
     * The progress and the board of the update, with the last running step of that board, so a
     * failure can mark the step where it stopped. A new board or [FirmwareUpdateProgress.Idle]
     * clears the last step. The fold drops its seed, so the first value is the real progress.
     */
    private val trackedProgress = combine(coordinator.progress, coordinator.deviceId, ::Pair)
        .runningFold(TrackedProgress()) { last, (progress, updatingId) ->
            val sameRun = updatingId == last.updatingId && progress != FirmwareUpdateProgress.Idle
            TrackedProgress(
                progress = progress,
                updatingId = updatingId,
                lastStep = progress.runningStep() ?: last.lastStep.takeIf { sameRun },
            )
        }
        .drop(1)

    /**
     * The status of the board. It starts with `null`, so the screen shows the progress of a running
     * update before the first status arrives.
     */
    private val boardStatus: Flow<FirmwareStatus?> = coordinator.status(deviceId).onStart<FirmwareStatus?> { emit(null) }

    val state: StateFlow<FirmwareUpdateScreenState> = combine(boardStatus, trackedProgress) { status, tracked ->
        firmwareUpdateScreenState(deviceId, status, tracked.progress, tracked.updatingId, tracked.lastStep)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FirmwareUpdateScreenState())

    /** Starts the update. The tap on the confirm button is the confirmation of the user. */
    fun start() {
        val phase = state.value.phase
        if (phase !is FirmwareUpdatePhase.Confirm || !phase.canStart) {
            log.d { "No start for $deviceId in $phase" }
            return
        }
        log.d { "The user confirmed the update of $deviceId" }
        coordinator.start(deviceId)
    }

    /** Starts the failed update again, when the failure permits it. */
    fun retry() {
        val phase = state.value.phase
        if (phase !is FirmwareUpdatePhase.Failed || !phase.canRetry) {
            log.d { "No retry for $deviceId in $phase" }
            return
        }
        log.d { "The user starts the update of $deviceId again after ${phase.reason}" }
        coordinator.retry()
    }

    /** Stops the update. The screen asks the user before it calls this. */
    fun cancel() {
        val phase = state.value.phase
        if (phase !is FirmwareUpdatePhase.Running || phase.cancelEffect == null) {
            log.d { "No cancel for $deviceId in $phase" }
            return
        }
        log.d { "The user stops the update of $deviceId at ${phase.step}" }
        coordinator.cancel()
    }

    /**
     * Clears the result of the update. Call it when the user closes the screen after the result.
     * Returns true when the screen can close: no update of this board runs.
     */
    fun close(): Boolean {
        return when (val phase = state.value.phase) {
            is FirmwareUpdatePhase.Running -> false
            is FirmwareUpdatePhase.Done, is FirmwareUpdatePhase.Failed -> {
                log.d { "The user closed the result of the update of $deviceId" }
                coordinator.dismiss()
                true
            }
            is FirmwareUpdatePhase.Confirm -> {
                log.d { "The user closed the update screen of $deviceId in $phase" }
                true
            }
        }
    }

    private data class TrackedProgress(
        val progress: FirmwareUpdateProgress = FirmwareUpdateProgress.Idle,
        val updatingId: String? = null,
        val lastStep: FirmwareStep? = null,
    )

    private companion object {
        private val log = Arbor.tag("FirmwareUpdateViewModel")
    }
}
