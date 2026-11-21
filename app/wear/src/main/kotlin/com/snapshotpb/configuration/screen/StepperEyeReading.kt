package com.snapshotpb.configuration.screen

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.common.util.format
import com.snapshotpb.configuration.model.EyeSensorState
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.configuration.model.eyeSeesBall
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_eye_hint_eye_off
import com.snapshotpb.core.resources.profile_eye_hint_fault
import com.snapshotpb.core.resources.profile_eye_hint_override_off
import com.snapshotpb.core.resources.profile_eye_hint_unsupported
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.StringResource

/**
 * Gives `true` when the Wear stepper watches the live eye value for [field] of [profile] under
 * [boltMode].
 *
 * Only [MarkerProfileField.EYE_THRESHOLD] watches the eye, and only when the field table shows it
 * for [profile] and [boltMode].
 */
internal fun stepperWatchesEye(
    field: MarkerProfileField,
    profile: MarkerProfile,
    boltMode: MarkerBoltMode,
): Boolean = field == MarkerProfileField.EYE_THRESHOLD && field.isShownFor(profile, boltMode)

/**
 * Collects [eyeReading] into [onState] while [lifecycle] is at or above [Lifecycle.State.STARTED]
 * and [watches] is `true`.
 *
 * When [watches] is `false`, this function calls [onState] with `null` one time and stops at once.
 * When [watches] is `true`, this function stops the collection each time [lifecycle] goes below
 * [Lifecycle.State.STARTED] and calls [onState] with `null`. Thus the watch is no eye watcher in
 * the background and shows no old value on return.
 */
internal suspend fun collectEyeReadingWhileStarted(
    lifecycle: Lifecycle,
    eyeReading: Flow<EyeReadingState?>,
    watches: Boolean,
    onState: (EyeReadingState?) -> Unit,
) {
    if (!watches) {
        onState(null)
        return
    }
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        try {
            eyeReading.collect { state -> onState(state) }
        } finally {
            onState(null)
        }
    }
}

/** The live eye line that the Wear stepper shows for [MarkerProfileField.EYE_THRESHOLD]. */
internal sealed interface StepperEyeLine {
    /**
     * A live sensor level in percent.
     *
     * @property percentText The sensor level with one decimal, for example `"42.3"`.
     * @property seesBall `true` when the eye detects a ball, `false` when not, or `null` when no
     *   ball rule applies.
     */
    data class Level(val percentText: String, val seesBall: Boolean?) : StepperEyeLine

    /**
     * A hint that tells why the board gives no live eye value.
     *
     * @property text The shared string resource of the hint.
     */
    data class Hint(val text: StringResource) : StepperEyeLine
}

/**
 * Maps [state] to the [StepperEyeLine] of the Wear stepper for [eyeMode] and [thresholdPercent],
 * or `null` when the stepper shows no eye line.
 *
 * The Wear app ignores [EyeReadingState.NotPermitted], because its hint names a browser chooser
 * that a watch does not have.
 */
internal fun stepperEyeLine(
    state: EyeReadingState?,
    eyeMode: MarkerEyeMode,
    thresholdPercent: Int,
): StepperEyeLine? = when (state) {
    null,
    EyeReadingState.Waiting,
    EyeReadingState.Failed,
    EyeReadingState.NotPermitted,
    -> null

    EyeReadingState.Unsupported -> StepperEyeLine.Hint(Res.string.profile_eye_hint_unsupported)

    is EyeReadingState.Reading -> when (state.reading.state) {
        EyeSensorState.ACTIVE -> {
            val levelRaw = state.reading.levelRaw
            val levelPercent = state.reading.levelPercent
            if (levelRaw == null || levelPercent == null) {
                null
            } else {
                StepperEyeLine.Level(
                    percentText = levelPercent.format(1),
                    seesBall = eyeSeesBall(
                        eyeMode = eyeMode,
                        levelRaw = levelRaw,
                        fullScaleRaw = state.reading.fullScaleRaw,
                        thresholdPercent = thresholdPercent,
                    ),
                )
            }
        }

        EyeSensorState.EYE_OFF -> StepperEyeLine.Hint(Res.string.profile_eye_hint_eye_off)
        EyeSensorState.OVERRIDE_OFF -> StepperEyeLine.Hint(Res.string.profile_eye_hint_override_off)
        EyeSensorState.FAULT -> StepperEyeLine.Hint(Res.string.profile_eye_hint_fault)
        EyeSensorState.UNKNOWN -> null
    }
}
