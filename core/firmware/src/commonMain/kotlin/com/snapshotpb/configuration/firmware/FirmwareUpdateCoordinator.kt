package com.snapshotpb.configuration.firmware

import com.snapshotpb.configuration.model.FirmwareChannel
import com.snapshotpb.configuration.model.FirmwareManifest
import com.snapshotpb.configuration.model.FirmwareUpdateFailure
import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Runs the firmware update of a board (`docs/firmware-update-plan.md` §2.7), and gives the firmware
 * status of each board. A screen uses only this API: it needs no Bluetooth type and sends no
 * Bluetooth request.
 *
 * The flow of one update:
 *
 * 1. [FirmwareUpdateProgress.Checking]: the coordinator reads the newest release of the hardware
 *    target of the board from the firmware site.
 * 2. [FirmwareUpdateProgress.Downloading]: it downloads the image and checks its size and SHA-256.
 * 3. [FirmwareUpdateProgress.Preparing]: it checks that the board is still connected.
 * 4. [FirmwareUpdateProgress.Uploading]: the board gets the image. The board holds fire control.
 * 5. [FirmwareUpdateProgress.Resetting]: the board marks the image for a test boot and resets.
 * 6. [FirmwareUpdateProgress.AwaitingSwap]: the board is dark while the bootloader swaps the images.
 *    [FirmwareUpdateProgress.AwaitingSwap.expectedMillis] is [EXPECTED_SWAP_MILLIS]. Tell the user
 *    not to remove the battery. The radio of the board is off at each boot: tell the user to turn
 *    it on in the board menu.
 * 7. [FirmwareUpdateProgress.Verifying]: the coordinator connects to the board again, checks that
 *    the new image runs, and confirms it when the firmware did not confirm itself.
 * 8. [FirmwareUpdateProgress.Done], or [FirmwareUpdateProgress.Failed] at any step.
 *
 * Show the warnings (keep the marker still, do not remove the battery) before you call [start]: the
 * call is the confirmation of the user. One update runs at a time.
 *
 * All the functions return at once. They do their work on the scope of the coordinator, so a
 * screen can call them from the main thread.
 */
interface FirmwareUpdateCoordinator {

    /**
     * The step of the update in progress, or of the last update. It is [FirmwareUpdateProgress.Idle]
     * when no update ran, or after [dismiss]. A [FirmwareUpdateProgress.Failed] value has a
     * [FirmwareUpdateFailure.canRetry] flag: show a "try again" action for [retry] when it is true.
     */
    val progress: StateFlow<FirmwareUpdateProgress>

    /** The board id of the update that [progress] reports, or `null` when [progress] is idle. */
    val deviceId: StateFlow<String?>

    /**
     * The firmware status of the board [deviceId] (the board id, the [com.snapshotpb.configuration.model.Device.id]
     * as text) in [channel]. The flow gives a new value when the connection, the board version, the
     * hardware target, the release or the update changes.
     *
     * The coordinator reads the release of a target from the site one time, and keeps it. Call
     * [refresh] to read it again.
     */
    fun status(deviceId: String, channel: FirmwareChannel = FirmwareChannel.STABLE): Flow<FirmwareStatus>

    /** Forgets the releases that the coordinator read, so each [status] flow reads the site again. */
    fun refresh()

    /**
     * Starts the update of the board [deviceId] to the newest release of [channel]. The call does
     * nothing when an update runs, or when [FirmwareStatus.canUpdate] is false because of
     * [FirmwareUnavailableReason.NOT_YET_RELEASED]. Each other reason gives a
     * [FirmwareUpdateProgress.Failed] step. A board on the newest release gives
     * [FirmwareUpdateFailure.NO_RELEASE] with the detail [UP_TO_DATE_DETAIL], and a board with no
     * known version gives it with the detail [UNKNOWN_BOARD_VERSION_DETAIL].
     */
    fun start(deviceId: String, channel: FirmwareChannel = FirmwareChannel.STABLE)

    /**
     * Starts the failed update again, when its [FirmwareUpdateFailure.canRetry] is true. After the
     * board got the reset command, a retry only does the verify step again: it connects to the board
     * and checks the image. Use this when the user turned the radio on too late, or when the
     * connect failed (a known defect on Linux). Before the reset, a retry starts at the check. A new
     * upload continues at the offset that the board reports.
     */
    fun retry()

    /**
     * Stops the update. During the upload, the coordinator disconnects the board, so the board stops
     * the upload and releases its fire-control hold. After the reset command, the board continues
     * its swap: the call only stops the wait and the verify step. [progress] becomes
     * [FirmwareUpdateProgress.Failed] with [FirmwareUpdateFailure.CANCELLED].
     */
    fun cancel()

    /** Puts [progress] back to [FirmwareUpdateProgress.Idle] after a [FirmwareUpdateProgress.Done] or a failure. */
    fun dismiss()

    companion object {
        /**
         * The expected time from the reset to the first advertisement of the new image, in
         * milliseconds. The screen shows it in the "do not remove the battery" step, and the
         * coordinator waits this time before it connects.
         *
         * THIS IS A PLACEHOLDER. Nobody has measured the swap on the bench yet (work item 2 of the
         * firmware update plan). The bench must measure the swap using move of a full image on a
         * `snapshot_v2` board, and this value must then change to the measured time with a margin.
         */
        const val EXPECTED_SWAP_MILLIS: Long = 90_000L

        /** The detail of [FirmwareUpdateFailure.NO_RELEASE] when the board runs the newest release. */
        const val UP_TO_DATE_DETAIL: String = "UpToDate"

        /** The detail of [FirmwareUpdateFailure.NO_RELEASE] when the app does not know the board version. */
        const val UNKNOWN_BOARD_VERSION_DETAIL: String = "UnknownBoardVersion"
    }
}

/**
 * The firmware status of one board, for the Firmware section of a screen.
 *
 * @property deviceId The board id.
 * @property connected True when the board is connected now.
 * @property boardVersion The firmware version of the board: the version of the advertisement where
 *   the platform gives one (Android), else the version of the running image from `image list`. It
 *   is `null` when the app knows neither.
 * @property target The hardware target that the board reported, or `null` when it did not.
 * @property latest The newest release of the hardware target in the channel, or `null` when the
 *   coordinator has none (see [reason]).
 * @property reason Why the app cannot offer [latest] now, or `null` when it can.
 */
data class FirmwareStatus(
    val deviceId: String,
    val connected: Boolean,
    val boardVersion: FirmwareVersion?,
    val target: HardwareTarget?,
    val latest: FirmwareManifest?,
    val reason: FirmwareUnavailableReason?,
) {
    /** True when the screen can show an active "Update" button. */
    val canUpdate: Boolean get() = reason == null
}

/**
 * Why a board gets no update offer now, in the order of the checks. Each value is a code for a
 * text of the screen.
 */
enum class FirmwareUnavailableReason {
    /** An update runs. */
    UPDATE_IN_PROGRESS,

    /** The board is not connected. Connect to it to read its hardware target. */
    NOT_CONNECTED,

    /** The app reads the update service of the board or the release of the site now. */
    CHECKING,

    /** The board has no update service. It needs a USB flash of a firmware with the update service. */
    NO_UPDATE_SERVICE,

    /** The Bluetooth engine of this platform cannot send the image (macOS desktop, web). */
    PLATFORM_UNSUPPORTED,

    /** The board has the update service, but it did not answer. Connect to it again. */
    UPDATE_SERVICE_ERROR,

    /** The app could not read the firmware site. [FirmwareUpdateCoordinator.refresh] reads it again. */
    CHECK_FAILED,

    /** The firmware site has no release for the hardware target in the channel. */
    NO_RELEASE,

    /** The newest release is not for the hardware target of the board. */
    INCOMPATIBLE_HARDWARE,

    /** The app does not know the firmware version of the board, so it cannot compare. */
    UNKNOWN_BOARD_VERSION,

    /** The board runs the newest release or a newer firmware. */
    UP_TO_DATE,

    /**
     * The update is not released yet. The release build of the app shows the status, but it offers
     * no update (see [FirmwareUpdatePolicy]).
     */
    NOT_YET_RELEASED,
}
