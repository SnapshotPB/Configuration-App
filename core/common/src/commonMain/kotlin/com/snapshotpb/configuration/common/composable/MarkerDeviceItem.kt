package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.device_screen_active_profile
import com.snapshotpb.core.resources.device_screen_no_profiles
import com.snapshotpb.core.resources.profile_marker_eye_mode
import com.snapshotpb.core.resources.profile_marker_fire_mode
import com.snapshotpb.core.resources.profile_marker_max_bps
import org.jetbrains.compose.resources.stringResource

@Composable
fun MarkerDeviceItem(
    photoPath: String,
    device: Device,
) {
    // TODO remember the last active profile
    val activeProfile = device.knownProfiles.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        DeviceBadge(
            photoPath = photoPath,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        )

        Space(8.dp)

        Column(
            modifier = Modifier
                .padding(4.dp)
        ) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(device.name)
            )
            if (activeProfile != null) {
                Text(
                    stringResource(
                        Res.string.device_screen_active_profile,
                        activeProfile.name,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        Res.string.profile_marker_max_bps,
                        activeProfile.maxBpsForTiming(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(Res.string.profile_marker_fire_mode, markerFireModeLabel(activeProfile.fireMode)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(Res.string.profile_marker_eye_mode, markerEyeModeLabel(activeProfile.eyeMode)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(Res.string.device_screen_no_profiles),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
