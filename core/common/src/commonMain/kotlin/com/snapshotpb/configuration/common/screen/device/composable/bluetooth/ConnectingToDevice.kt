package com.snapshotpb.configuration.common.screen.device.composable.bluetooth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.bluetooth_icon
import com.snapshotpb.core.resources.connecting_explanation
import com.snapshotpb.core.resources.connecting_icon_explanation
import com.snapshotpb.core.resources.connecting_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectingToDevice(
    deviceName: String,
    fwVersion: String,
) {
    ActionContainer {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Box(
                modifier = Modifier.align(Alignment.CenterVertically).size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Image(
                    painter = painterResource(Res.drawable.bluetooth_icon),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    contentDescription = stringResource(Res.string.connecting_icon_explanation),
                    modifier = Modifier.size(20.dp)
                )
            }
            Space(8.dp)
            Text(
                stringResource(Res.string.connecting_title),
                style = MaterialTheme.typography.displaySmall,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Text(
                stringResource(Res.string.connecting_explanation, deviceName, fwVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            Space(8.dp)
        }
    }
}