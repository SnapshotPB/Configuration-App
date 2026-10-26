package com.snapshotpb.configuration.common.screen.firmware

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.firmware.FirmwareUnavailableReason
import com.snapshotpb.configuration.firmware.FirmwareUpdateCoordinator
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.firmware_failure_bad_image
import com.snapshotpb.core.resources.firmware_failure_board_refused
import com.snapshotpb.core.resources.firmware_failure_cancelled
import com.snapshotpb.core.resources.firmware_failure_link_lost
import com.snapshotpb.core.resources.firmware_failure_network
import com.snapshotpb.core.resources.firmware_failure_no_advertisement
import com.snapshotpb.core.resources.firmware_failure_no_release
import com.snapshotpb.core.resources.firmware_failure_not_connected
import com.snapshotpb.core.resources.firmware_failure_not_copied
import com.snapshotpb.core.resources.firmware_failure_reverted
import com.snapshotpb.core.resources.firmware_reason_check_failed
import com.snapshotpb.core.resources.firmware_reason_checking
import com.snapshotpb.core.resources.firmware_reason_incompatible_hardware
import com.snapshotpb.core.resources.firmware_reason_no_release
import com.snapshotpb.core.resources.firmware_reason_no_update_service
import com.snapshotpb.core.resources.firmware_reason_not_connected
import com.snapshotpb.core.resources.firmware_reason_not_yet_released
import com.snapshotpb.core.resources.firmware_reason_platform_unsupported
import com.snapshotpb.core.resources.firmware_reason_unknown_board_version
import com.snapshotpb.core.resources.firmware_reason_up_to_date
import com.snapshotpb.core.resources.firmware_reason_update_in_progress
import com.snapshotpb.core.resources.firmware_reason_update_service_error
import com.snapshotpb.core.resources.firmware_step_check
import com.snapshotpb.core.resources.firmware_step_download
import com.snapshotpb.core.resources.firmware_step_prepare
import com.snapshotpb.core.resources.firmware_step_reset
import com.snapshotpb.core.resources.firmware_step_swap
import com.snapshotpb.core.resources.firmware_step_upload
import com.snapshotpb.core.resources.firmware_step_verify
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The text of a step in the step list. */
@Composable
internal fun firmwareStepLabel(step: FirmwareStep): String = stringResource(
    when (step) {
        FirmwareStep.CHECK -> Res.string.firmware_step_check
        FirmwareStep.DOWNLOAD -> Res.string.firmware_step_download
        FirmwareStep.PREPARE -> Res.string.firmware_step_prepare
        FirmwareStep.UPLOAD -> Res.string.firmware_step_upload
        FirmwareStep.RESET -> Res.string.firmware_step_reset
        FirmwareStep.SWAP -> Res.string.firmware_step_swap
        FirmwareStep.VERIFY -> Res.string.firmware_step_verify
    }
)

/** The text that tells the user why the app offers no update. */
internal fun firmwareReasonText(reason: FirmwareUnavailableReason): StringResource = when (reason) {
    FirmwareUnavailableReason.UPDATE_IN_PROGRESS -> Res.string.firmware_reason_update_in_progress
    FirmwareUnavailableReason.NOT_CONNECTED -> Res.string.firmware_reason_not_connected
    FirmwareUnavailableReason.CHECKING -> Res.string.firmware_reason_checking
    FirmwareUnavailableReason.NO_UPDATE_SERVICE -> Res.string.firmware_reason_no_update_service
    FirmwareUnavailableReason.PLATFORM_UNSUPPORTED -> Res.string.firmware_reason_platform_unsupported
    FirmwareUnavailableReason.UPDATE_SERVICE_ERROR -> Res.string.firmware_reason_update_service_error
    FirmwareUnavailableReason.CHECK_FAILED -> Res.string.firmware_reason_check_failed
    FirmwareUnavailableReason.NO_RELEASE -> Res.string.firmware_reason_no_release
    FirmwareUnavailableReason.INCOMPATIBLE_HARDWARE -> Res.string.firmware_reason_incompatible_hardware
    FirmwareUnavailableReason.UNKNOWN_BOARD_VERSION -> Res.string.firmware_reason_unknown_board_version
    FirmwareUnavailableReason.UP_TO_DATE -> Res.string.firmware_reason_up_to_date
    FirmwareUnavailableReason.NOT_YET_RELEASED -> Res.string.firmware_reason_not_yet_released
}

/**
 * The text that tells the user why the update stopped. A [FirmwareUpdateFailure.NO_RELEASE] with a
 * known [detail] gets the more exact text of the section.
 */
internal fun firmwareFailureText(reason: FirmwareUpdateFailure, detail: String?): StringResource = when (reason) {
    FirmwareUpdateFailure.NETWORK -> Res.string.firmware_failure_network
    FirmwareUpdateFailure.NO_RELEASE -> when (detail) {
        FirmwareUpdateCoordinator.UP_TO_DATE_DETAIL -> Res.string.firmware_reason_up_to_date
        FirmwareUpdateCoordinator.UNKNOWN_BOARD_VERSION_DETAIL -> Res.string.firmware_reason_unknown_board_version
        else -> Res.string.firmware_failure_no_release
    }
    FirmwareUpdateFailure.BAD_IMAGE -> Res.string.firmware_failure_bad_image
    FirmwareUpdateFailure.INCOMPATIBLE_HARDWARE -> Res.string.firmware_reason_incompatible_hardware
    FirmwareUpdateFailure.NO_UPDATE_SERVICE -> Res.string.firmware_reason_no_update_service
    FirmwareUpdateFailure.NOT_CONNECTED -> Res.string.firmware_failure_not_connected
    FirmwareUpdateFailure.LINK_LOST -> Res.string.firmware_failure_link_lost
    FirmwareUpdateFailure.BOARD_REFUSED -> Res.string.firmware_failure_board_refused
    FirmwareUpdateFailure.REVERTED -> Res.string.firmware_failure_reverted
    FirmwareUpdateFailure.NOT_COPIED -> Res.string.firmware_failure_not_copied
    FirmwareUpdateFailure.NO_ADVERTISEMENT -> Res.string.firmware_failure_no_advertisement
    FirmwareUpdateFailure.CANCELLED -> Res.string.firmware_failure_cancelled
}

/**
 * The detail of a failure for the "Details" line, or `null` when the screen shows none. The
 * failure text already says what the two known details of [FirmwareUpdateFailure.NO_RELEASE] mean.
 */
internal fun firmwareFailureDetail(detail: String?): String? = detail
    ?.takeIf { it.isNotBlank() }
    ?.takeUnless {
        it == FirmwareUpdateCoordinator.UP_TO_DATE_DETAIL || it == FirmwareUpdateCoordinator.UNKNOWN_BOARD_VERSION_DETAIL
    }

/** A time as minutes and seconds, for example `1:05`. A negative time shows as `0:00`. */
internal fun formatMinutesSeconds(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:" + seconds.toString().padStart(2, '0')
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
