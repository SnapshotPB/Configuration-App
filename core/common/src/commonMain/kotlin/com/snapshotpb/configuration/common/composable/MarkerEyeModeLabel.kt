package com.snapshotpb.configuration.common.composable

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.model.MarkerEyeMode
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_eye_mode_break_beam
import com.snapshotpb.core.resources.profile_eye_mode_off
import com.snapshotpb.core.resources.profile_eye_mode_reflective
import com.snapshotpb.core.resources.profile_mode_unknown
import org.jetbrains.compose.resources.stringResource

/**
 * Localized, title-case label for a [MarkerEyeMode] (e.g. "Break Beam" rather than the raw
 * enum name "BREAK_BEAM"). Shared so every eye-mode display stays consistent.
 */
@Composable
fun markerEyeModeLabel(eyeMode: MarkerEyeMode): String = stringResource(
    when (eyeMode) {
        MarkerEyeMode.OFF -> Res.string.profile_eye_mode_off
        MarkerEyeMode.REFLECTIVE -> Res.string.profile_eye_mode_reflective
        MarkerEyeMode.BREAK_BEAM -> Res.string.profile_eye_mode_break_beam
        MarkerEyeMode.UNKNOWN -> Res.string.profile_mode_unknown
    }
)
