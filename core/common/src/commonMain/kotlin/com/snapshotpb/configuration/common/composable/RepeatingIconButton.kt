package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.time.Duration

/**
 * An [IconButton] that repeats [onClick] while the user holds it. See [rememberRepeatingClick].
 */
@Composable
fun RepeatingIconButton(
    onClick: () -> Unit,
    holdThreshold: Duration = DefaultHoldThreshold,
    initialRepeatDelay: Duration = DefaultInitialRepeatDelay,
    minRepeatDelay: Duration = DefaultMinRepeatDelay,
    repeatAcceleration: Double = DefaultRepeatAcceleration,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val click = rememberRepeatingClick(
        interactionSource = interactionSource,
        holdThreshold = holdThreshold,
        initialRepeatDelay = initialRepeatDelay,
        minRepeatDelay = minRepeatDelay,
        repeatAcceleration = repeatAcceleration,
        onStep = onClick,
    )

    IconButton(onClick = click, interactionSource = interactionSource) {
        content()
    }
}

/**
 * Repeats [onStep] while [interactionSource] reports a press (see [RepeatOnHold]), and returns
 * the click handler for the button that owns [interactionSource].
 *
 * A hold ends with a release, and the button reports the release as a click. After a hold that
 * repeated, the returned handler ignores that click, or the lift of the finger would add one more
 * step. The phone repeat button and the watch stepper both use this.
 */
@Composable
fun rememberRepeatingClick(
    interactionSource: MutableInteractionSource,
    holdThreshold: Duration = DefaultHoldThreshold,
    initialRepeatDelay: Duration = DefaultInitialRepeatDelay,
    minRepeatDelay: Duration = DefaultMinRepeatDelay,
    repeatAcceleration: Double = DefaultRepeatAcceleration,
    onStep: () -> Unit,
): () -> Unit {
    val guard = remember(interactionSource) { RepeatClickGuard() }
    val currentOnStep by rememberUpdatedState(onStep)

    // Each new press starts clear. A hold that was dragged off the button ends with no click, so
    // the flag must not carry over to the next tap.
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) guard.repeated = false
        }
    }

    RepeatOnHold(
        interactionSource = interactionSource,
        holdThreshold = holdThreshold,
        initialRepeatDelay = initialRepeatDelay,
        minRepeatDelay = minRepeatDelay,
        repeatAcceleration = repeatAcceleration,
        onRepeat = {
            guard.repeated = true
            currentOnStep()
        },
    )

    return remember(guard) {
        {
            if (guard.repeated) {
                guard.repeated = false
            } else {
                currentOnStep()
            }
        }
    }
}

/** Records that the current press repeated. It is not state: no read of it draws anything. */
private class RepeatClickGuard {
    var repeated: Boolean = false
}
