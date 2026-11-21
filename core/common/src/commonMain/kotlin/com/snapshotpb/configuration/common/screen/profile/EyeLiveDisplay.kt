package com.snapshotpb.configuration.common.screen.profile

import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.common.util.format
import com.snapshotpb.configuration.model.EYE_THRESHOLD_DEFAULT
import com.snapshotpb.configuration.model.EYE_THRESHOLD_MAX
import com.snapshotpb.configuration.model.EyeReading
import com.snapshotpb.configuration.model.EyeSensorState
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.eyeSeesBall
import com.snapshotpb.configuration.model.eyeThresholdRaw

/** The hint that the editor shows when the board gives no live eye level. */
internal enum class EyeLiveHint {
    EYE_OFF,
    OVERRIDE_OFF,
    FAULT,
    UNSUPPORTED,
    NOT_PERMITTED,
}

/** The live eye content that the profile editor shows under `EYE_THRESHOLD`. */
internal sealed interface EyeLiveDisplay {
    /** No value and no hint. */
    data object None : EyeLiveDisplay

    /**
     * The live sensor level and the ball result for the edited threshold.
     *
     * @property levelPercent The level in percent of the full scale ([EyeReading.levelPercent]).
     * @property levelFraction The ratio `levelRaw / fullScaleRaw` in the range `0f..1f`.
     * @property thresholdFraction The ratio of [eyeThresholdRaw] to `fullScaleRaw` in the range
     *   `0f..1f`.
     * @property thresholdPercent The effective threshold in percent. A value of 0 or less gives
     *   [EYE_THRESHOLD_DEFAULT], and a value above [EYE_THRESHOLD_MAX] gives [EYE_THRESHOLD_MAX].
     * @property seesBall The result of [eyeSeesBall], or `null` when no rule applies.
     */
    data class Level(
        val levelPercent: Double,
        val levelFraction: Float,
        val thresholdFraction: Float,
        val thresholdPercent: Int,
        val seesBall: Boolean?,
    ) : EyeLiveDisplay

    /** A hint that tells the user why the board gives no level. */
    data class Hint(val hint: EyeLiveHint) : EyeLiveDisplay
}

/**
 * Selects the display for [state] with the edited [eyeMode] and [thresholdPercent].
 *
 * The editor passes the draft values, so a local edit changes the result at once.
 */
internal fun eyeLiveDisplay(
    state: EyeReadingState?,
    eyeMode: MarkerEyeMode,
    thresholdPercent: Int,
): EyeLiveDisplay = when (state) {
    null,
    EyeReadingState.Waiting,
    EyeReadingState.Failed,
    -> EyeLiveDisplay.None

    EyeReadingState.Unsupported -> EyeLiveDisplay.Hint(EyeLiveHint.UNSUPPORTED)
    EyeReadingState.NotPermitted -> EyeLiveDisplay.Hint(EyeLiveHint.NOT_PERMITTED)
    is EyeReadingState.Reading -> readingDisplay(
        reading = state.reading,
        eyeMode = eyeMode,
        thresholdPercent = thresholdPercent,
    )
}

/** Formats [levelPercent] with one decimal place through `Double.format(1)`. */
internal fun formatEyeLevelPercent(levelPercent: Double): String = levelPercent.format(1)

/**
 * Returns `true` when the editor must watch the eye of the board.
 *
 * This is `true` when [profile] is not `null` and `MarkerProfileField.EYE_THRESHOLD.isShownFor`
 * returns `true` for [profile] and [boltMode].
 */
internal fun profileWatchesEye(profile: MarkerProfile?, boltMode: MarkerBoltMode): Boolean =
    profile != null && MarkerProfileField.EYE_THRESHOLD.isShownFor(profile, boltMode)

private fun readingDisplay(
    reading: EyeReading,
    eyeMode: MarkerEyeMode,
    thresholdPercent: Int,
): EyeLiveDisplay = when (reading.state) {
    EyeSensorState.EYE_OFF -> EyeLiveDisplay.Hint(EyeLiveHint.EYE_OFF)
    EyeSensorState.OVERRIDE_OFF -> EyeLiveDisplay.Hint(EyeLiveHint.OVERRIDE_OFF)
    EyeSensorState.FAULT -> EyeLiveDisplay.Hint(EyeLiveHint.FAULT)
    EyeSensorState.UNKNOWN -> EyeLiveDisplay.None
    EyeSensorState.ACTIVE -> activeReadingDisplay(
        reading = reading,
        eyeMode = eyeMode,
        thresholdPercent = thresholdPercent,
    )
}

private fun activeReadingDisplay(
    reading: EyeReading,
    eyeMode: MarkerEyeMode,
    thresholdPercent: Int,
): EyeLiveDisplay {
    val levelRaw = reading.levelRaw ?: return EyeLiveDisplay.None
    val fullScaleRaw = reading.fullScaleRaw
    if (fullScaleRaw <= 0) return EyeLiveDisplay.None
    val levelPercent = reading.levelPercent ?: return EyeLiveDisplay.None
    val effectiveThresholdPercent = effectiveEyeThresholdPercent(thresholdPercent)
    val thresholdRaw = eyeThresholdRaw(fullScaleRaw, thresholdPercent)
    return EyeLiveDisplay.Level(
        levelPercent = levelPercent,
        levelFraction = (levelRaw.toFloat() / fullScaleRaw.toFloat()).coerceIn(0f, 1f),
        thresholdFraction = (thresholdRaw.toFloat() / fullScaleRaw.toFloat()).coerceIn(0f, 1f),
        thresholdPercent = effectiveThresholdPercent,
        seesBall = eyeSeesBall(eyeMode, levelRaw, fullScaleRaw, thresholdPercent),
    )
}

private fun effectiveEyeThresholdPercent(thresholdPercent: Int): Int = when {
    thresholdPercent <= 0 -> EYE_THRESHOLD_DEFAULT
    thresholdPercent > EYE_THRESHOLD_MAX -> EYE_THRESHOLD_MAX
    else -> thresholdPercent
}
