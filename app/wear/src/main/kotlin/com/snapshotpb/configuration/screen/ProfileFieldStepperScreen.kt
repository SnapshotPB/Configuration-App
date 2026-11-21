package com.snapshotpb.configuration.screen

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import com.snapshotpb.configuration.bluetooth.EyeReadingState
import com.snapshotpb.configuration.common.composable.rememberRepeatingClick
import com.snapshotpb.configuration.common.composable.markerProfileFieldLabel
import com.snapshotpb.configuration.common.composable.markerProfileFieldValueLabel
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.configuration.model.MarkerProfileField
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.navigation_back
import com.snapshotpb.core.resources.profile_eye_live_level
import com.snapshotpb.core.resources.profile_eye_result_ball
import com.snapshotpb.core.resources.profile_eye_result_empty
import com.snapshotpb.core.resources.profile_marker_max_bps
import com.snapshotpb.core.resources.profile_step_coarse
import com.snapshotpb.core.resources.profile_step_fine
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource

/** The test tag of the large value text. */
const val PROFILE_FIELD_VALUE_TAG = "profile_field_value"

/** The test tag of the button that steps the value up. */
const val PROFILE_FIELD_PLUS_TAG = "profile_field_plus"

/** The test tag of the live eye level text. */
const val PROFILE_EYE_LEVEL_TAG = "profile_eye_level"

/** The test tag of the ball result text of the live eye. */
const val PROFILE_EYE_RESULT_TAG = "profile_eye_result"

/** The test tag of the live eye hint text. */
const val PROFILE_EYE_HINT_TAG = "profile_eye_hint"

/**
 * The stepper for one numeric [field] of [profile].
 *
 * The value lives in the state of this screen while the user steps. A tap or a hold on a button
 * and a turn of the crown each change only that state. The screen commits the value through
 * [onCommit] at two points: a short time after the last step while no button is down, and when
 * the screen closes. A store update runs a JSON write and a Data Layer push, and a hold reaches
 * one step every 25 ms, so a commit on every step is too costly.
 *
 * The fine step is one stored unit. The coarse step is [MarkerProfileField.COARSE_STEP_FACTOR]
 * units. The fields that set the firing cycle show the max BPS line, computed from [profile] with
 * the local value applied, so the user sees the effect of a change before the store has it. For
 * [MarkerProfileField.EYE_THRESHOLD] with an eye mode, the screen shows the live eye level and
 * result for the edited threshold, or a hint.
 *
 * @param eyeReading The live eye state of the board of [profile]. The screen collects it only for
 *   [MarkerProfileField.EYE_THRESHOLD] of a profile with an eye, and only while the screen is
 *   started. A collection makes the screen an eye watcher. The flow is cold, so a flow that the
 *   screen does not collect costs nothing.
 */
@Composable
fun ProfileFieldStepperScreen(
    profile: MarkerProfile,
    field: MarkerProfileField,
    boltMode: MarkerBoltMode = MarkerBoltMode.CLOSED,
    eyeReading: Flow<EyeReadingState?> = emptyFlow(),
    onCommit: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var value by remember(profile.id, field) { mutableIntStateOf(field.get(profile)) }
    var committedValue by remember(profile.id, field) { mutableIntStateOf(field.get(profile)) }
    var isCoarse by remember { mutableStateOf(false) }
    val stepSize = if (isCoarse) MarkerProfileField.COARSE_STEP_FACTOR else 1
    val currentOnCommit by rememberUpdatedState(onCommit)

    val watchesEye = stepperWatchesEye(field, profile, boltMode)
    var eyeState by remember(profile.id, field) { mutableStateOf<EyeReadingState?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, eyeReading, watchesEye) {
        collectEyeReadingWhileStarted(lifecycle, eyeReading, watchesEye) { eyeState = it }
    }
    // The value is the edited threshold, so the result follows each step before a commit.
    val eyeLine = if (watchesEye) stepperEyeLine(eyeState, profile.eyeMode, value) else null
    val sectionGap = if (eyeLine != null) 4.dp else 8.dp

    val minusInteraction = remember { MutableInteractionSource() }
    val plusInteraction = remember { MutableInteractionSource() }
    val isMinusPressed by minusInteraction.collectIsPressedAsState()
    val isPlusPressed by plusInteraction.collectIsPressedAsState()
    val isButtonDown = isMinusPressed || isPlusPressed

    // The click after a hold that repeated is ignored, so a lift adds no extra step.
    val onMinusClick = rememberRepeatingClick(minusInteraction) { value = field.step(value, -stepSize) }
    val onPlusClick = rememberRepeatingClick(plusInteraction) { value = field.step(value, stepSize) }

    // The commit waits for the finger to lift and for the crown to stop. Each new step restarts
    // the wait, so a hold or a long crown turn makes one store update.
    LaunchedEffect(value, isButtonDown) {
        if (isButtonDown || value == committedValue) return@LaunchedEffect
        delay(COMMIT_SETTLE_DELAY)
        currentOnCommit(value)
        committedValue = value
    }

    // A close during the wait above cancels the effect, so the close commits the value itself.
    // The effect keeps the callback of its own profile: when the active profile changes under an
    // open stepper, the newest callback already points at the new profile, and the old value
    // must not go there.
    DisposableEffect(profile.id, field) {
        val commit = onCommit
        onDispose {
            if (value != committedValue) commit(value)
        }
    }

    // The crown reports pixels, not detents. The remainder carries a partial step to the next
    // event, so a slow turn still moves the value.
    val focusRequester = remember { FocusRequester() }
    var rotaryRemainder by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The E2E test finds the value and the plus button by their tags as resource ids.
            .semantics { testTagsAsResourceId = true }
            .onRotaryScrollEvent { event ->
                rotaryRemainder += event.verticalScrollPixels
                val steps = (rotaryRemainder / ROTARY_PIXELS_PER_STEP).toInt()
                if (steps != 0) {
                    rotaryRemainder -= steps * ROTARY_PIXELS_PER_STEP
                    value = field.step(value, steps * stepSize)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = markerProfileFieldLabel(field),
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onMinusClick,
                interactionSource = minusInteraction,
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
            ) {
                Text(STEP_DOWN_GLYPH)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = markerProfileFieldValueLabel(field, value),
                style = MaterialTheme.typography.title1,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(PROFILE_FIELD_VALUE_TAG),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onPlusClick,
                interactionSource = plusInteraction,
                modifier = Modifier
                    .size(ButtonDefaults.SmallButtonSize)
                    .testTag(PROFILE_FIELD_PLUS_TAG),
            ) {
                Text(STEP_UP_GLYPH)
            }
        }
        if (field.affectsMaxBps) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    Res.string.profile_marker_max_bps,
                    field.set(profile, value).maxBpsForTiming(boltMode),
                ),
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
            )
        }
        when (val line = eyeLine) {
            is StepperEyeLine.Level -> {
                Spacer(modifier = Modifier.height(4.dp))
                // No live region here: the sensor level changes 10 times each second.
                Text(
                    text = stringResource(Res.string.profile_eye_live_level, line.percentText),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.testTag(PROFILE_EYE_LEVEL_TAG),
                )
                val seesBall = line.seesBall
                if (seesBall != null) {
                    Text(
                        text = stringResource(
                            if (seesBall) {
                                Res.string.profile_eye_result_ball
                            } else {
                                Res.string.profile_eye_result_empty
                            },
                        ),
                        style = MaterialTheme.typography.caption1,
                        color = if (seesBall) {
                            MaterialTheme.colors.primary
                        } else {
                            MaterialTheme.colors.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(PROFILE_EYE_RESULT_TAG),
                    )
                }
            }

            is StepperEyeLine.Hint -> {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(line.text),
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .testTag(PROFILE_EYE_HINT_TAG),
                )
            }

            null -> Unit
        }
        Spacer(modifier = Modifier.height(sectionGap))
        ToggleChip(
            checked = isCoarse,
            onCheckedChange = { isCoarse = it },
            label = {
                Text(
                    if (isCoarse) {
                        stringResource(Res.string.profile_step_coarse)
                    } else {
                        stringResource(Res.string.profile_step_fine)
                    }
                )
            },
            toggleControl = { Switch(checked = isCoarse) },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(modifier = Modifier.height(sectionGap))
        Button(
            onClick = onBack,
            modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
        ) {
            Text(stringResource(Res.string.navigation_back))
        }
    }
}

/** The time with no step and no button down before the value goes to the store. */
private val COMMIT_SETTLE_DELAY = 300.milliseconds

/**
 * The crown travel, in pixels, that makes one step. A crown detent reports a device-specific
 * distance, so this value is a compromise: one detent gives one step on most watches.
 */
private const val ROTARY_PIXELS_PER_STEP = 40f

private const val STEP_DOWN_GLYPH = "−"
private const val STEP_UP_GLYPH = "+"
