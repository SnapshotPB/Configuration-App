package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Calls [onRepeat] again and again while [interactionSource] reports a press.
 *
 * The first call comes after [holdThreshold], so a tap stays a single click for the button that
 * owns the press. Each later call comes after a delay that starts at [initialRepeatDelay] and
 * shrinks by [repeatAcceleration] down to [minRepeatDelay]. The phone repeat button and the watch
 * stepper share this effect.
 */
@Composable
fun RepeatOnHold(
    interactionSource: InteractionSource,
    holdThreshold: Duration = DefaultHoldThreshold,
    initialRepeatDelay: Duration = DefaultInitialRepeatDelay,
    minRepeatDelay: Duration = DefaultMinRepeatDelay,
    repeatAcceleration: Double = DefaultRepeatAcceleration,
    onRepeat: () -> Unit,
) {
    val isPressed by interactionSource.collectIsPressedAsState()
    val currentOnRepeat by rememberUpdatedState(onRepeat)

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(holdThreshold)
            var stepDelay = initialRepeatDelay
            while (true) {
                currentOnRepeat()
                delay(stepDelay)
                stepDelay = (stepDelay * repeatAcceleration).coerceAtLeast(minRepeatDelay)
            }
        }
    }
}

internal val DefaultHoldThreshold: Duration = 400.milliseconds
internal val DefaultInitialRepeatDelay: Duration = 100.milliseconds
internal val DefaultMinRepeatDelay: Duration = 25.milliseconds
internal const val DefaultRepeatAcceleration: Double = 0.85
