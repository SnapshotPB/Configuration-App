package com.snapshotpb.configuration.screen

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.common.screen.profile.EyeLiveDisplay
import com.snapshotpb.configuration.common.screen.profile.EyeLiveHint
import com.snapshotpb.configuration.common.screen.profile.eyeLiveDisplay
import com.snapshotpb.configuration.common.screen.profile.eyeLiveHintString
import com.snapshotpb.configuration.common.screen.profile.formatEyeLevelPercent
import com.snapshotpb.configuration.common.screen.profile.profileWatchesEye
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.StringResource

/**
 * Gives `true` when the Wear stepper watches the live eye value for [field] of [profile] under
 * [boltMode].
 *
 * Only [MarkerProfileField.EYE_THRESHOLD] watches the eye, and only when [profileWatchesEye] is
 * `true` for [profile] and [boltMode].
 */
internal fun stepperWatchesEye(
    field: MarkerProfileField,
    profile: MarkerProfile,
    boltMode: MarkerBoltMode,
): Boolean = field == MarkerProfileField.EYE_THRESHOLD && profileWatchesEye(profile, boltMode)

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
): StepperEyeLine? = when (val display = eyeLiveDisplay(state, eyeMode, thresholdPercent)) {
    EyeLiveDisplay.None -> null
    is EyeLiveDisplay.Hint -> if (display.hint == EyeLiveHint.NOT_PERMITTED) {
        null
    } else {
        StepperEyeLine.Hint(eyeLiveHintString(display.hint))
    }

    is EyeLiveDisplay.Level -> StepperEyeLine.Level(
        percentText = formatEyeLevelPercent(display.levelPercent),
        seesBall = display.seesBall,
    )
}
