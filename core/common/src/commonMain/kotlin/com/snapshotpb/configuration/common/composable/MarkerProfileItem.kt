package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.model.MarkerBoltMode
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.common_yes
import com.snapshotpb.core.resources.profile_badge_unsaved
import com.snapshotpb.core.resources.profile_marker_eye_mode
import com.snapshotpb.core.resources.profile_marker_fire_mode
import com.snapshotpb.core.resources.profile_marker_max_bps
import com.snapshotpb.core.resources.profile_marker_profile_active
import org.jetbrains.compose.resources.stringResource

@Composable
fun MarkerProfileItem(
    index: Int,
    activeProfile: Boolean,
    profile: MarkerProfile,
    boltMode: MarkerBoltMode = MarkerBoltMode.CLOSED,
    isDiverged: Boolean = false,
    onClick: () -> Unit = {},
) {
    val containerColor = if (isDiverged) MaterialTheme.colorScheme.tertiaryContainer else null
    ActionContainer(
        modifier = Modifier
            .clickable { onClick() },
        containerColor = containerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    profile.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isDiverged) {
                    Space(8.dp)
                    Surface(
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(Res.string.profile_badge_unsaved),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (activeProfile) {
                Text(
                    text = stringResource(
                        Res.string.profile_marker_profile_active,
                        stringResource(Res.string.common_yes)
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Space(4.dp)

            Text(
                stringResource(
                    Res.string.profile_marker_max_bps,
                    profile.maxBpsForTiming(boltMode)
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(Res.string.profile_marker_fire_mode, markerFireModeLabel(profile.fireMode)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(Res.string.profile_marker_eye_mode, markerEyeModeLabel(profile.eyeMode)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
