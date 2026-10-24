package com.snapshotpb.configuration.common.screen.firmware

import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.firmware.FirmwareStatus
import com.snapshotpb.configuration.firmware.FirmwareUnavailableReason
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.model.FirmwareVersion

/**
 * The steps of the update flow (`docs/firmware-update-plan.md` §2.7), in their order. The update
 * screen shows one row for each step.
 */
enum class FirmwareStep {
    CHECK,
    DOWNLOAD,
    PREPARE,
    UPLOAD,
    RESET,
    SWAP,
    VERIFY,
}

/** The step of a running [FirmwareUpdateProgress], or `null` when no update runs. */
internal fun FirmwareUpdateProgress.runningStep(): FirmwareStep? = when (this) {
    FirmwareUpdateProgress.Checking -> FirmwareStep.CHECK
    is FirmwareUpdateProgress.Downloading -> FirmwareStep.DOWNLOAD
    FirmwareUpdateProgress.Preparing -> FirmwareStep.PREPARE
    is FirmwareUpdateProgress.Uploading -> FirmwareStep.UPLOAD
    FirmwareUpdateProgress.Resetting -> FirmwareStep.RESET
    is FirmwareUpdateProgress.AwaitingSwap -> FirmwareStep.SWAP
    FirmwareUpdateProgress.Verifying -> FirmwareStep.VERIFY
    FirmwareUpdateProgress.Idle, is FirmwareUpdateProgress.Done, is FirmwareUpdateProgress.Failed -> null
}

/** The part of the step that is complete, from 0 to 1, or `null` when the step has no fraction. */
internal fun FirmwareUpdateProgress.stepFraction(): Float? = when (this) {
    is FirmwareUpdateProgress.Downloading -> fraction
    is FirmwareUpdateProgress.Uploading -> fraction
    else -> null
}

/** What a stop of the update does at a step. The cancel dialog tells the user. */
enum class FirmwareCancelEffect {
    /** Before the reset: the board stops the update and keeps its firmware. */
    STOPS_UPDATE,

    /** After the reset: the board continues to install the image. Only the app stops its wait. */
    STOPS_WAIT,
}

/**
 * The cancel effect at [step], or `null` when the screen must not offer a stop. During the reset,
 * the board can get the reset command at any time, so a stop has no clear result.
 */
internal fun cancelEffectAt(step: FirmwareStep): FirmwareCancelEffect? = when (step) {
    FirmwareStep.CHECK,
    FirmwareStep.DOWNLOAD,
    FirmwareStep.PREPARE,
    FirmwareStep.UPLOAD -> FirmwareCancelEffect.STOPS_UPDATE
    FirmwareStep.RESET -> null
    FirmwareStep.SWAP,
    FirmwareStep.VERIFY -> FirmwareCancelEffect.STOPS_WAIT
}

/** The action of the Firmware section of the configuration screen. */
enum class FirmwareSectionAction {
    /** An "Update" button that opens the update screen. */
    UPDATE,

    /** An update of this board runs or has a result: a button opens the update screen. */
    SHOW_UPDATE,

    /** An inactive "Update" button. The section shows the reason. */
    NONE,
}

/**
 * The Firmware section of the configuration screen.
 *
 * @property status The firmware status of the board, or `null` before the first value.
 * @property action The button of the section.
 * @property activeStep The step of the update of this board that runs now, or `null`.
 * @property activeFraction The part of [activeStep] that is complete, or `null`.
 */
data class FirmwareSectionState(
    val status: FirmwareStatus? = null,
    val action: FirmwareSectionAction = FirmwareSectionAction.NONE,
    val activeStep: FirmwareStep? = null,
    val activeFraction: Float? = null,
) {
    /**
     * The reason to show under the button, or `null` when the section shows no reason. Before the
     * first status, the section shows [FirmwareUnavailableReason.CHECKING].
     */
    val reason: FirmwareUnavailableReason?
        get() = when (action) {
            FirmwareSectionAction.NONE -> status?.reason ?: FirmwareUnavailableReason.CHECKING
            FirmwareSectionAction.UPDATE, FirmwareSectionAction.SHOW_UPDATE -> null
        }
}

/**
 * The section state of the board [deviceId]. The coordinator updates one board at a time:
 * [updatingId] is that board, and [progress] is its step.
 */
internal fun firmwareSectionState(
    deviceId: String,
    status: FirmwareStatus,
    progress: FirmwareUpdateProgress,
    updatingId: String?,
): FirmwareSectionState {
    val ownProgress = progress.takeIf { updatingId == deviceId } ?: FirmwareUpdateProgress.Idle
    val action = when {
        ownProgress != FirmwareUpdateProgress.Idle -> FirmwareSectionAction.SHOW_UPDATE
        status.canUpdate -> FirmwareSectionAction.UPDATE
        else -> FirmwareSectionAction.NONE
    }
    return FirmwareSectionState(
        status = status,
        action = action,
        activeStep = ownProgress.runningStep(),
        activeFraction = ownProgress.stepFraction(),
    )
}

/** The part of the update screen under the title. */
sealed interface FirmwareUpdatePhase {

    /**
     * Before the start: the warnings and the confirm button.
     *
     * @property reason Why the screen cannot start the update now, or `null` when it can.
     */
    data class Confirm(val reason: FirmwareUnavailableReason?) : FirmwareUpdatePhase {
        val canStart: Boolean get() = reason == null
    }

    /**
     * An update of this board runs.
     *
     * @property step The step that runs.
     * @property fraction The part of [step] that is complete, or `null`.
     * @property elapsedMillis The time since the reset, in the [FirmwareStep.SWAP] step only.
     * @property expectedMillis The expected time of the swap, in the [FirmwareStep.SWAP] step only.
     * @property cancelEffect What a stop does now, or `null` when the screen offers no stop.
     */
    data class Running(
        val step: FirmwareStep,
        val fraction: Float? = null,
        val elapsedMillis: Long? = null,
        val expectedMillis: Long? = null,
        val cancelEffect: FirmwareCancelEffect? = null,
    ) : FirmwareUpdatePhase

    /** The board runs the new image. [version] is the version that the board reports. */
    data class Done(val version: FirmwareVersion?) : FirmwareUpdatePhase

    /**
     * The update stopped.
     *
     * @property step The step that ran last, or `null` when the screen did not see it.
     */
    data class Failed(
        val reason: FirmwareUpdateFailure,
        val detail: String?,
        val step: FirmwareStep?,
    ) : FirmwareUpdatePhase {
        val canRetry: Boolean get() = reason.canRetry
    }
}

/**
 * The update screen of one board.
 *
 * @property status The firmware status of the board, or `null` before the first value.
 * @property phase The part of the screen under the title.
 */
data class FirmwareUpdateScreenState(
    val status: FirmwareStatus? = null,
    val phase: FirmwareUpdatePhase = FirmwareUpdatePhase.Confirm(FirmwareUnavailableReason.CHECKING),
) {
    /** True while an update of this board runs. The screen then stays on. */
    val isRunning: Boolean get() = phase is FirmwareUpdatePhase.Running
}

/**
 * The update screen state of the board [deviceId]. [lastStep] is the last running step that the
 * screen saw, so a failure can mark the step where the update stopped.
 */
internal fun firmwareUpdateScreenState(
    deviceId: String,
    status: FirmwareStatus?,
    progress: FirmwareUpdateProgress,
    updatingId: String?,
    lastStep: FirmwareStep?,
): FirmwareUpdateScreenState {
    val ownProgress = progress.takeIf { updatingId == deviceId } ?: FirmwareUpdateProgress.Idle
    val phase = when (ownProgress) {
        // Before the first status, the screen waits for the check. A status with no reason can start.
        FirmwareUpdateProgress.Idle ->
            FirmwareUpdatePhase.Confirm(reason = if (status == null) FirmwareUnavailableReason.CHECKING else status.reason)
        is FirmwareUpdateProgress.Done -> FirmwareUpdatePhase.Done(ownProgress.version)
        is FirmwareUpdateProgress.Failed -> FirmwareUpdatePhase.Failed(ownProgress.reason, ownProgress.detail, lastStep)
        else -> {
            val step = checkNotNull(ownProgress.runningStep())
            val swap = ownProgress as? FirmwareUpdateProgress.AwaitingSwap
            FirmwareUpdatePhase.Running(
                step = step,
                fraction = ownProgress.stepFraction(),
                elapsedMillis = swap?.elapsedMillis,
                expectedMillis = swap?.expectedMillis,
                cancelEffect = cancelEffectAt(step),
            )
        }
    }
    return FirmwareUpdateScreenState(status = status, phase = phase)
}

/**
 * The status of [step] in the step list of [phase]. Before the start, every step is pending. When
 * the screen did not see the step of a failure, every step is pending.
 */
internal fun firmwareStepStatus(phase: FirmwareUpdatePhase, step: FirmwareStep): StepStatus {
    val current = when (phase) {
        is FirmwareUpdatePhase.Confirm -> return StepStatus.PENDING
        is FirmwareUpdatePhase.Done -> return StepStatus.COMPLETED
        is FirmwareUpdatePhase.Running -> phase.step
        is FirmwareUpdatePhase.Failed -> phase.step ?: return StepStatus.PENDING
    }
    return when {
        step < current -> StepStatus.COMPLETED
        step > current -> StepStatus.PENDING
        phase is FirmwareUpdatePhase.Failed -> StepStatus.FAILED
        else -> StepStatus.IN_PROGRESS
    }
}
