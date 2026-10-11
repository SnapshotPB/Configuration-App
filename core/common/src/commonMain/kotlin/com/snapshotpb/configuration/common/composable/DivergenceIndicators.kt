package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_badge_unsaved
import org.jetbrains.compose.resources.stringResource

/**
 * Marks a value that differs from the one the board last reported, so the user can see what a save
 * would send. Shared by the device-configuration and profile screens, which both diff against the
 * cached `BoardState`.
 */
@Composable
fun UnsavedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiary,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(Res.string.profile_badge_unsaved),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Text-field colors that carry the same "unsaved" tint as [UnsavedBadge]. */
@Composable
fun divergenceFieldColors(isDiverged: Boolean): TextFieldColors =
    if (isDiverged) {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.tertiary,
            unfocusedBorderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
            focusedLabelColor = MaterialTheme.colorScheme.tertiary,
            unfocusedLabelColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }
