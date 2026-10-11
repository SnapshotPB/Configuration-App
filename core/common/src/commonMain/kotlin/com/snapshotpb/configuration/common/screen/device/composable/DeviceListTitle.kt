package com.snapshotpb.configuration.common.screen.device.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeviceListTitle(title: String) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}