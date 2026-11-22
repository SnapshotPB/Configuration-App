package com.snapshotpb.configuration.bluetooth

import com.snapshotpb.configuration.model.EyeReading

/**
 * The live breach eye state of a connected board.
 *
 * This state stays in memory only. The app does not store it in `devices.json` and does not send
 * it in the Wear sync.
 */
sealed interface EyeReadingState {
    /** The app watches the eye, but it has no value from the last second (or no value yet). */
    data object Waiting : EyeReadingState

    /** The last eye status of the board. */
    data class Reading(val reading: EyeReading) : EyeReadingState

    /** The board has no eye status characteristic: its firmware is older than the feature. */
    data object Unsupported : EyeReadingState

    /** The web grant hides the eye status service. A new connect through the browser chooser gives access. */
    data object NotPermitted : EyeReadingState

    /** The subscribe to the eye status failed. */
    data object Failed : EyeReadingState
}
