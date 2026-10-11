package com.snapshotpb.configuration.common.composable

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.model.MarkerFireMode
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_fire_mode_custom_ramping
import com.snapshotpb.core.resources.profile_fire_mode_full_auto
import com.snapshotpb.core.resources.profile_fire_mode_mechanical
import com.snapshotpb.core.resources.profile_fire_mode_ramping_nxl_2026
import com.snapshotpb.core.resources.profile_fire_mode_semi
import com.snapshotpb.core.resources.profile_fire_mode_trigger_only
import com.snapshotpb.core.resources.profile_mode_unknown
import org.jetbrains.compose.resources.stringResource

/**
 * Localized, title-case label for a [MarkerFireMode] (e.g. "Trigger Only" rather than the
 * raw enum name "TRIGGER_ONLY"). Shared so every fire-mode display stays consistent. The NXL
 * ramping mode shows the name of its rule set, `NXL 2026`, in each language, as the board menu
 * does.
 */
@Composable
fun markerFireModeLabel(fireMode: MarkerFireMode): String = stringResource(
    when (fireMode) {
        MarkerFireMode.MECHANICAL -> Res.string.profile_fire_mode_mechanical
        MarkerFireMode.SEMI -> Res.string.profile_fire_mode_semi
        MarkerFireMode.TRIGGER_ONLY -> Res.string.profile_fire_mode_trigger_only
        MarkerFireMode.FULL_AUTO -> Res.string.profile_fire_mode_full_auto
        MarkerFireMode.RAMPING_NXL_2026 -> Res.string.profile_fire_mode_ramping_nxl_2026
        MarkerFireMode.CUSTOM_RAMPING -> Res.string.profile_fire_mode_custom_ramping
        MarkerFireMode.UNKNOWN -> Res.string.profile_mode_unknown
    }
)
