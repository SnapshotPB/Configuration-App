package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.snapshotpb.configuration.common.theme.Theme

@Composable
fun ActionContainer(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (containerColor != null) CardDefaults.cardColors(containerColor = containerColor) else CardDefaults.cardColors(),
            elevation = CardDefaults.cardElevation(defaultElevation = Theme.elevation)
        ) {
            Column(
                modifier = Modifier
                    .then(modifier)
                    .fillMaxWidth(),
            ) {
                content()
            }
        }
    }
    Space(8.dp)
}
