package com.snapshotpb.configuration.common.screen.firmware

import com.snapshotpb.configuration.bluetooth.StepStatus
import com.snapshotpb.configuration.firmware.FirmwareUnavailableReason
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cover for the step list rules and the text helpers of the firmware update screen. */
class FirmwareUpdateStateTest {

    private fun statuses(phase: FirmwareUpdatePhase) = FirmwareStep.entries.map { firmwareStepStatus(phase, it) }

    @Test
    fun beforeTheStartEveryStepIsPending() {
        assertEquals(
            List(FirmwareStep.entries.size) { StepStatus.PENDING },
            statuses(FirmwareUpdatePhase.Confirm(reason = null)),
        )
    }

    @Test
    fun theRunningStepIsInProgressAfterTheCompletedSteps() {
        assertEquals(
            listOf(
                StepStatus.COMPLETED,
                StepStatus.COMPLETED,
                StepStatus.COMPLETED,
                StepStatus.IN_PROGRESS,
                StepStatus.PENDING,
                StepStatus.PENDING,
                StepStatus.PENDING,
            ),
            statuses(FirmwareUpdatePhase.Running(step = FirmwareStep.UPLOAD)),
        )
    }

    @Test
    fun theFailedStepIsMarked() {
        assertEquals(
            listOf(
                StepStatus.COMPLETED,
                StepStatus.COMPLETED,
                StepStatus.COMPLETED,
                StepStatus.COMPLETED,
                StepStatus.COMPLETED,
                StepStatus.COMPLETED,
                StepStatus.FAILED,
            ),
            statuses(FirmwareUpdatePhase.Failed(FirmwareUpdateFailure.NO_ADVERTISEMENT, null, FirmwareStep.VERIFY)),
        )
    }

    @Test
    fun afterTheDoneStepEveryStepIsComplete() {
        assertEquals(
            List(FirmwareStep.entries.size) { StepStatus.COMPLETED },
            statuses(FirmwareUpdatePhase.Done(version = null)),
        )
    }

    @Test
    fun onlyTheResetOffersNoStop() {
        FirmwareStep.entries.forEach { step ->
            val effect = cancelEffectAt(step)
            when {
                step == FirmwareStep.RESET -> assertNull(effect)
                step < FirmwareStep.RESET -> assertEquals(FirmwareCancelEffect.STOPS_UPDATE, effect, "$step")
                else -> assertEquals(FirmwareCancelEffect.STOPS_WAIT, effect, "$step")
            }
        }
    }

    @Test
    fun theTimeShowsMinutesAndSeconds() {
        assertEquals("0:00", formatMinutesSeconds(0))
        assertEquals("0:00", formatMinutesSeconds(-5))
        assertEquals("0:09", formatMinutesSeconds(9_999))
        assertEquals("1:05", formatMinutesSeconds(65_000))
        assertEquals("1:30", formatMinutesSeconds(FirmwareUpdateCoordinator.EXPECTED_SWAP_MILLIS))
    }

    @Test
    fun theKnownDetailsOfNoReleaseShowNoDetailLine() {
        assertNull(firmwareFailureDetail(FirmwareUpdateCoordinator.UP_TO_DATE_DETAIL))
        assertNull(firmwareFailureDetail(FirmwareUpdateCoordinator.UNKNOWN_BOARD_VERSION_DETAIL))
        assertNull(firmwareFailureDetail(null))
        assertNull(firmwareFailureDetail(" "))
        assertEquals("NoAnswer", firmwareFailureDetail("NoAnswer"))
    }

    @Test
    fun theKnownDetailsOfNoReleaseGetTheTextOfTheSection() {
        assertEquals(
            firmwareReasonText(FirmwareUnavailableReason.UP_TO_DATE),
            firmwareFailureText(FirmwareUpdateFailure.NO_RELEASE, FirmwareUpdateCoordinator.UP_TO_DATE_DETAIL),
        )
    }
}
