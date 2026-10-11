package com.snapshotpb.configuration

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.common.theme.AppTypography
import com.snapshotpb.configuration.common.theme.Theme.darkScheme
import com.snapshotpb.configuration.common.theme.Theme.lightScheme

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> darkScheme
        else -> lightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
