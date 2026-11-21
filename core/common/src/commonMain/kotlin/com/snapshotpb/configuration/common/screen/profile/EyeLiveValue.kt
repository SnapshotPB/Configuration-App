package com.snapshotpb.configuration.common.screen.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_eye_hint_eye_off
import com.snapshotpb.core.resources.profile_eye_hint_fault
import com.snapshotpb.core.resources.profile_eye_hint_not_permitted
import com.snapshotpb.core.resources.profile_eye_hint_override_off
import com.snapshotpb.core.resources.profile_eye_hint_unsupported
import com.snapshotpb.core.resources.profile_eye_level_bar_description
import com.snapshotpb.core.resources.profile_eye_live_level
import com.snapshotpb.core.resources.profile_eye_result_ball
import com.snapshotpb.core.resources.profile_eye_result_empty
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Semantics test tag of the live eye level text. */
internal const val EYE_LIVE_LEVEL_TAG = "eyeLiveLevel"

/** Semantics test tag of the live eye level bar. */
internal const val EYE_LEVEL_BAR_TAG = "eyeLevelBar"

/** Semantics test tag of the live eye ball result text. */
internal const val EYE_RESULT_TAG = "eyeResult"

/** Semantics test tag of the live eye hint text. */
internal const val EYE_HINT_TAG = "eyeHint"

/**
 * Shows the live eye level, the level bar, and the ball result, or a hint under `EYE_THRESHOLD`.
 *
 * Emits no node for [EyeLiveDisplay.None].
 */
@Composable
internal fun EyeLiveValue(
    display: EyeLiveDisplay,
    modifier: Modifier = Modifier,
) {
    when (display) {
        EyeLiveDisplay.None -> Unit
        is EyeLiveDisplay.Hint -> {
            Text(
                text = stringResource(eyeLiveHintString(display.hint)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag(EYE_HINT_TAG),
            )
        }

        is EyeLiveDisplay.Level -> {
            val formattedLevel = formatEyeLevelPercent(display.levelPercent)
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Not a live region, because the level changes 10 times each second.
                    Text(
                        text = stringResource(Res.string.profile_eye_live_level, formattedLevel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(EYE_LIVE_LEVEL_TAG),
                    )
                    val seesBall = display.seesBall
                    if (seesBall != null) {
                        Text(
                            text = stringResource(
                                if (seesBall) {
                                    Res.string.profile_eye_result_ball
                                } else {
                                    Res.string.profile_eye_result_empty
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (seesBall) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .testTag(EYE_RESULT_TAG)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                Space(4.dp)
                EyeLevelBar(
                    levelFraction = display.levelFraction,
                    thresholdFraction = display.thresholdFraction,
                    description = stringResource(
                        Res.string.profile_eye_level_bar_description,
                        formattedLevel,
                        display.thresholdPercent,
                    ),
                )
            }
        }
    }
}

private fun eyeLiveHintString(hint: EyeLiveHint): StringResource = when (hint) {
    EyeLiveHint.EYE_OFF -> Res.string.profile_eye_hint_eye_off
    EyeLiveHint.OVERRIDE_OFF -> Res.string.profile_eye_hint_override_off
    EyeLiveHint.FAULT -> Res.string.profile_eye_hint_fault
    EyeLiveHint.UNSUPPORTED -> Res.string.profile_eye_hint_unsupported
    EyeLiveHint.NOT_PERMITTED -> Res.string.profile_eye_hint_not_permitted
}

@Composable
private fun EyeLevelBar(
    levelFraction: Float,
    thresholdFraction: Float,
    description: String,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    val thresholdColor = MaterialTheme.colorScheme.onSurface
    val clampedLevel = levelFraction.coerceIn(0f, 1f)
    val clampedThreshold = thresholdFraction.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .testTag(EYE_LEVEL_BAR_TAG)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = cornerRadius,
        )
        val fillWidth = size.width * clampedLevel
        if (fillWidth > 0f) {
            drawRoundRect(
                color = fillColor,
                size = Size(fillWidth, size.height),
                cornerRadius = cornerRadius,
            )
        }
        val markWidth = 2.dp.toPx()
        val markX = (size.width * clampedThreshold - markWidth / 2f)
            .coerceIn(0f, (size.width - markWidth).coerceAtLeast(0f))
        drawRect(
            color = thresholdColor,
            topLeft = Offset(markX, 0f),
            size = Size(markWidth, size.height),
        )
    }
}
