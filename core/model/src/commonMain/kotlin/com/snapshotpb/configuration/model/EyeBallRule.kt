package com.snapshotpb.configuration.model

/** The full scale of the 12-bit eye ADC of the current boards (0 to 4095). */
const val EYE_ADC_FULL_SCALE_RAW: Int = 4095

/**
 * Gives the effective eye threshold percent for [thresholdPercent].
 *
 * This function copies `effective_eye_threshold_percent` in
 * `snapshot-config/src/wire_presence.rs`. A value of 0 or less gives [EYE_THRESHOLD_DEFAULT]. A
 * value above [EYE_THRESHOLD_MAX] gives [EYE_THRESHOLD_MAX].
 */
fun effectiveEyeThresholdPercent(thresholdPercent: Int): Int = when {
    thresholdPercent <= 0 -> EYE_THRESHOLD_DEFAULT
    thresholdPercent > EYE_THRESHOLD_MAX -> EYE_THRESHOLD_MAX
    else -> thresholdPercent
}

/**
 * Converts [thresholdPercent] to raw ADC counts on the [fullScaleRaw] scale.
 *
 * This function copies `effective_eye_threshold_percent` and `effective_eye_threshold_adc` in
 * `snapshot-config/src/wire_presence.rs`. A value of 0 or less selects [EYE_THRESHOLD_DEFAULT],
 * and a value above [EYE_THRESHOLD_MAX] becomes [EYE_THRESHOLD_MAX]. The result uses integer
 * division (`effectivePercent * fullScaleRaw / 100`). A [fullScaleRaw] of 0 or less gives 0.
 *
 * The bar of the editor must draw the threshold mark from this function, so the mark and the
 * [eyeSeesBall] result agree at each count.
 */
fun eyeThresholdRaw(fullScaleRaw: Int, thresholdPercent: Int): Int {
    if (fullScaleRaw <= 0) return 0
    val effectivePercent = effectiveEyeThresholdPercent(thresholdPercent).toLong()
    return (effectivePercent * fullScaleRaw.toLong() / 100L).toInt()
}

/**
 * Gives `true` when the breach eye detects a ball at [levelRaw], `false` when not, or `null` when
 * no rule applies.
 *
 * This function copies `fc_eye_ball_present` in `snapshot-core/src/chamber.rs` and
 * `effective_eye_threshold_adc` in `snapshot-config/src/wire_presence.rs`. In
 * [MarkerEyeMode.BREAK_BEAM], a ball is present when [levelRaw] is at or below [eyeThresholdRaw].
 * In [MarkerEyeMode.REFLECTIVE], a ball is present when [levelRaw] is above [eyeThresholdRaw].
 * [MarkerEyeMode.OFF] gives `null`. [MarkerEyeMode.UNKNOWN] gives `null` too, because the app does
 * not know the rule of a mode that it does not know.
 *
 * The bar of the editor must draw the threshold mark from [eyeThresholdRaw], so the mark and this
 * result agree at each count.
 */
fun eyeSeesBall(
    eyeMode: MarkerEyeMode,
    levelRaw: Int,
    fullScaleRaw: Int,
    thresholdPercent: Int,
): Boolean? = when (eyeMode) {
    MarkerEyeMode.BREAK_BEAM -> levelRaw <= eyeThresholdRaw(fullScaleRaw, thresholdPercent)
    MarkerEyeMode.REFLECTIVE -> levelRaw > eyeThresholdRaw(fullScaleRaw, thresholdPercent)
    MarkerEyeMode.OFF -> null
    MarkerEyeMode.UNKNOWN -> null
}
