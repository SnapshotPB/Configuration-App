package com.snapshotpb.configuration.common.composable

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_bolt_mode_closed
import com.snapshotpb.core.resources.profile_bolt_mode_open
import org.jetbrains.compose.resources.stringResource

/**
 * Localized, title-case label for a [MarkerBoltMode] (e.g. "Closed Bolt" rather than the raw
 * enum name "CLOSED"). Shared so every bolt-mode display stays consistent.
 */
@Composable
fun MarkerBoltMode.displayName(): String = stringResource(
    when (this) {
        MarkerBoltMode.CLOSED -> Res.string.profile_bolt_mode_closed
        MarkerBoltMode.OPEN -> Res.string.profile_bolt_mode_open
    }
)

/**
 * Localized, title-case label for a [MarkerBoltMode]. Delegates to [MarkerBoltMode.displayName]
 * so callers following the `marker*ModeLabel` naming convention stay consistent.
 */
@Composable
fun markerBoltModeLabel(boltMode: MarkerBoltMode): String = boltMode.displayName()
