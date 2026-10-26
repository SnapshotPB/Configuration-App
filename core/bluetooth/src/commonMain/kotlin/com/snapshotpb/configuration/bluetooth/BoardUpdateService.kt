package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.FirmwareUpdateProgress
import com.snapshotpb.configuration.model.FirmwareVersion
import com.snapshotpb.configuration.model.HardwareTarget

/** What the engine knows of the firmware update service (MCUmgr SMP) of one connected board. */
enum class UpdateServiceStatus {
    /** The engine reads `os info` and `mcumgr params` now. */
    CHECKING,

    /** The board answered `os info`. [BoardUpdateService.target] has the hardware target. */
    AVAILABLE,

    /** The board has no SMP service: its firmware has no update service and needs a USB flash. */
    ABSENT,

    /**
     * The Bluetooth engine of this platform has no write without response with a result
     * (`SmpWriteUnsupported`: `MacosJvmEngine`, `JsEngine`). The board can have the service.
     */
    UNSUPPORTED_PLATFORM,

    /** The board has the SMP service, but `os info` failed. A new connect reads it again. */
    FAILED,
}

/**
 * The image that runs on the board, from `image list`.
 *
 * @property version The MCUboot image version as text, for example `26.43.1`. The firmware writes
 *   the year minus 2000, the week and the count (`app/VERSION`).
 * @property hash The MCUboot image hash in lowercase hexadecimal, as the `mcumgr` CLI prints it.
 * @property confirmed True when MCUboot keeps the image after the next reset.
 */
data class BoardImage(
    val version: String,
    val hash: String,
    val confirmed: Boolean,
) {
    /**
     * [version] as a firmware version, or `null` when the text has no three numbers. The MCUboot
     * header has no dev flag, so a dev build gives the version of a release with the same numbers.
     * Use the version of the advertisement where the platform gives one.
     */
    val firmwareVersion: FirmwareVersion?
        get() {
            val parts = version.split('.')
            if (parts.size < VERSION_PARTS) return null
            val (major, minor, patch) = parts.take(VERSION_PARTS).map { it.toIntOrNull() ?: return null }
            if (major !in 0..BYTE_MAX || minor !in 0..BYTE_MAX || patch !in 0..BYTE_MAX) return null
            return FirmwareVersion(year = YEAR_BASE + major, week = minor, count = patch, dev = false)
        }

    private companion object {
        const val VERSION_PARTS = 3
        const val YEAR_BASE = 2000
        const val BYTE_MAX = 0xFF
    }
}

/**
 * The firmware update service of one connected board, as the engine read it after the 4-step
 * connect. [BluetoothStateManager.updateServices] holds one entry for each connected board id. The
 * entry is in memory only, not in the device file, so it is not a synced field.
 *
 * @property target The hardware target from `os info -f i`, or `null` when the board did not answer.
 * @property bufferSize The SMP buffer size from `os mcumgr_params`, or `null` when the board does not
 *   answer that command. The upload then uses the safe default frame.
 * @property activeImage The image that runs, from `image list`, or `null` when the list failed.
 */
data class BoardUpdateService(
    val status: UpdateServiceStatus,
    val target: HardwareTarget? = null,
    val bufferSize: Int? = null,
    val activeImage: BoardImage? = null,
)

/**
 * The state of the last [BluetoothRequest.UpdateFirmware] or [BluetoothRequest.ConfirmFirmware]
 * that the engine (or the simulated board) runs.
 *
 * An upload goes through `Preparing`, `Uploading`, `Resetting` and ends with
 * [FirmwareUpdateProgress.AwaitingSwap] (the board got the reset command) or
 * [FirmwareUpdateProgress.Failed]. A confirm goes through `Verifying` and ends with
 * [FirmwareUpdateProgress.Done] or [FirmwareUpdateProgress.Failed]. The state stays at its last value
 * until the next request, so a collector that comes late still sees the end of its request.
 *
 * @property requestId The request id of the request that this state reports.
 * @property deviceId The device id of that request.
 * @property uploadedImageHash The MCUboot hash of the uploaded image in slot 1, in lowercase
 *   hexadecimal. It is set with `AwaitingSwap`. After the reset, the board runs this image when
 *   [BoardImage.hash] of its active image is the same.
 */
data class FirmwareUploadState(
    val requestId: Long = 0L,
    val deviceId: String? = null,
    val progress: FirmwareUpdateProgress = FirmwareUpdateProgress.Idle,
    val uploadedImageHash: String? = null,
)
