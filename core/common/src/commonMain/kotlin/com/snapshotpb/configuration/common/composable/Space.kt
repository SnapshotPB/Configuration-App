package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Space(size: Dp = 4.dp) = Spacer(modifier = Modifier.size(size))
