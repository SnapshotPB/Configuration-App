package com.snapshotpb.configuration.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.snapshotpb.configuration.common.composable.markerFireModeLabel
import com.snapshotpb.configuration.common.util.formatTenths
import com.snapshotpb.configuration.model.Device
import com.snapshotpb.configuration.model.MarkerProfile
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.navigation_back
import com.snapshotpb.core.resources.wear_profile_secondary
import com.snapshotpb.core.resources.wear_select_profile
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProfileSelectionScreen(
    device: Device,
    onProfileSelected: (MarkerProfile) -> Unit,
    onBack: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Text(stringResource(Res.string.wear_select_profile), style = MaterialTheme.typography.title3)
            }
        }

        items(device.knownProfiles) { profile ->
            val isActive = profile.id == device.activeProfileId
            Chip(
                onClick = { onProfileSelected(profile) },
                label = { Text(profile.name) },
                secondaryLabel = {
                    Text(
                        text = stringResource(
                            Res.string.wear_profile_secondary,
                            markerFireModeLabel(profile.fireMode),
                            formatTenths(profile.searSolenoidDwell),
                        ),
                        style = MaterialTheme.typography.caption3
                    )
                },
                colors = if (isActive) {
                    ChipDefaults.primaryChipColors()
                } else {
                    ChipDefaults.secondaryChipColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
            ) {
                Text(stringResource(Res.string.navigation_back))
            }
        }
    }
}
