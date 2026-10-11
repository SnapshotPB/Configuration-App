package com.snapshotpb.configuration.common.screen.profile.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profiles_device_configuration_body
import com.snapshotpb.core.resources.profiles_device_configuration_button
import com.snapshotpb.core.resources.profiles_device_configuration_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeviceConfigurationCard(
    onClick: () -> Unit,
) {
    ActionContainer {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)
        ) {
            Text(
                stringResource(Res.string.profiles_device_configuration_title),
                style = MaterialTheme.typography.displaySmall,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Text(
                stringResource(Res.string.profiles_device_configuration_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Space(8.dp)
            Button(
                modifier = Modifier.align(Alignment.End),
                onClick = onClick,
            ) {
                Text(stringResource(Res.string.profiles_device_configuration_button))
            }
            Space(8.dp)
        }
    }
}
