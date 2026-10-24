package com.snapshotpb.configuration.model

import kotlinx.serialization.Serializable

/**
 * The step of a firmware update, in the order of the update flow (`docs/firmware-update-plan.md`
 * §2.7).
 *
 * This is a plain model with no Bluetooth types, so the screens and the Bluetooth layer can share
 * it. The app does not store it.
 */
@Serializable
sealed interface FirmwareUpdateProgress {

    /** No update runs. */
    @Serializable
    data object Idle : FirmwareUpdateProgress

    /** The app reads the newest release of the board from the firmware site. */
    @Serializable
    data object Checking : FirmwareUpdateProgress

    /**
     * The app downloads the image.
     *
     * @property received The bytes that arrived.
     * @property total The length of the image, or `null` when the length is not known.
     */
    @Serializable
    data class Downloading(val received: Long, val total: Long?) : FirmwareUpdateProgress {
        /** The part of the image that arrived, from 0 to 1, or `null` when [total] is not known. */
        val fraction: Float?
            get() = fractionOf(received, total)
    }

    /** The app checks the board and its image slots, and shows the warnings to the user. */
    @Serializable
    data object Preparing : FirmwareUpdateProgress

    /**
     * The app sends the image to the board.
     *
     * @property offset The bytes that the board accepted.
     * @property total The length of the image.
     */
    @Serializable
    data class Uploading(val offset: Long, val total: Long) : FirmwareUpdateProgress {
        /** The part of the image that the board accepted, from 0 to 1. */
        val fraction: Float
            get() = fractionOf(offset, total) ?: 0f
    }

    /** The app marks the new image for a test boot and sends the reset command. */
    @Serializable
    data object Resetting : FirmwareUpdateProgress

    /**
     * The board is off the air while the bootloader copies or swaps the image.
     *
     * @property elapsedMillis The time since the reset.
     * @property expectedMillis The time that the copy usually takes, or `null` when it is not known.
     */
    @Serializable
    data class AwaitingSwap(
        val elapsedMillis: Long = 0,
        val expectedMillis: Long? = null,
    ) : FirmwareUpdateProgress

    /** The app scans for the board, connects again, and checks the version of the board. */
    @Serializable
    data object Verifying : FirmwareUpdateProgress

    /** The board runs the new image. [version] is the version that the board reports. */
    @Serializable
    data class Done(val version: FirmwareVersion? = null) : FirmwareUpdateProgress

    /**
     * The update stopped.
     *
     * @property reason Why the update stopped.
     * @property detail More text for the log or for the screen, or `null`.
     */
    @Serializable
    data class Failed(
        val reason: FirmwareUpdateFailure,
        val detail: String? = null,
    ) : FirmwareUpdateProgress
}

/**
 * The reason of a [FirmwareUpdateProgress.Failed] step.
 *
 * @property canRetry True when a new attempt can succeed with no change to the board or the
 *   release. An upload that a new attempt starts again resumes at the offset that the board
 *   reports.
 */
@Serializable
enum class FirmwareUpdateFailure(val canRetry: Boolean) {
    /** The firmware site did not answer, or the download stopped. */
    NETWORK(canRetry = true),

    /** The firmware site has no release for the board in the channel. */
    NO_RELEASE(canRetry = false),

    /** The size or the SHA-256 of the download is not the value of the site. */
    BAD_IMAGE(canRetry = true),

    /** The `compatible_hardware` list of the release does not hold the board. */
    INCOMPATIBLE_HARDWARE(canRetry = false),

    /**
     * The board did not answer `os info`, so its firmware has no update service. The board needs a
     * USB flash of a firmware with the update service.
     */
    NO_UPDATE_SERVICE(canRetry = false),

    /** The board is not connected. */
    NOT_CONNECTED(canRetry = true),

    /** The Bluetooth link stopped during the upload. */
    LINK_LOST(canRetry = true),

    /** The board refused a command of the update. */
    BOARD_REFUSED(canRetry = true),

    /** After the reset, the board advertises the old version: the bootloader reverted the image. */
    REVERTED(canRetry = true),

    /** After the reset, the board advertises the old version: the bootloader did not copy the image. */
    NOT_COPIED(canRetry = true),

    /** No advertisement came after the reset. The radio is off at each boot until the user turns it on. */
    NO_ADVERTISEMENT(canRetry = true),

    /** The user stopped the update. */
    CANCELLED(canRetry = true),
}

private fun fractionOf(part: Long, total: Long?): Float? = when {
    total == null || total <= 0 -> null
    else -> (part.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
}
