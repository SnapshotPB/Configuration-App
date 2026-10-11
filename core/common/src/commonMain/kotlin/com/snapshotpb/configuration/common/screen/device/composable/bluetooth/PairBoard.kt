package com.snapshotpb.configuration.common.screen.device.composable.bluetooth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.composable.ActionContainer
import com.snapshotpb.configuration.common.composable.Space
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.bluetooth_icon
import com.snapshotpb.core.resources.button_pair_board
import com.snapshotpb.core.resources.pair_board_explanation
import com.snapshotpb.core.resources.pair_board_title
import com.snapshotpb.core.resources.start_scanning_icon_explanation
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The web form of [ScanForDevices]. A Web Bluetooth scan is one choice in the browser device list,
 * and the browser opens that list only inside a user gesture. Thus [onClick] must open the list
 * directly, not through the async request bus, and no Scanning state or Stop button follows.
 */
@Composable
fun PairBoard(
    onClick: () -> Unit
) {
    ActionContainer {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Image(
                painter = painterResource(Res.drawable.bluetooth_icon),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                contentDescription = stringResource(Res.string.start_scanning_icon_explanation),
                modifier = Modifier.align(Alignment.CenterVertically)
                    .size(32.dp)
            )
            Space(8.dp)
            Text(
                stringResource(Res.string.pair_board_title),
                style = MaterialTheme.typography.displaySmall,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Text(
                stringResource(Res.string.pair_board_explanation),
                style = MaterialTheme.typography.bodyMedium,
            )
            Space(8.dp)
            Button(
                modifier = Modifier.align(Alignment.End).testTag("btPairBoard"),
                onClick = onClick
            ) {
                Text(stringResource(Res.string.button_pair_board))
            }
            Space(8.dp)
        }
    }
}
