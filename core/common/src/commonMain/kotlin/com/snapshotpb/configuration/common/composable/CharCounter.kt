package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.device_field_char_counter
import org.jetbrains.compose.resources.stringResource

/**
 * Gives a text field the accessible name [name].
 *
 * Use it on a text field that shows its heading in a separate `Text` and has no `label`. Such a
 * field has no name of its own. The text field merges the text of its [CharCounter] ("9/10"), and
 * VoiceOver then reads the counter as the name of the field. With this modifier, the screen reader
 * reads [name] as the name. The counter stays visible in the field as its supporting text, but a
 * screen reader can skip it.
 */
internal fun Modifier.textFieldName(name: String): Modifier =
    semantics { contentDescription = name }

/**
 * Right-aligned "current/max" character counter for a length-limited text field, intended to be
 * passed as the `supportingText` of an `OutlinedTextField`. When the field has no `label`, give it
 * a name with [textFieldName], or a screen reader reads this counter as the name of the field.
 */
@Composable
internal fun CharCounter(current: Int, max: Int) {
    Text(
        text = stringResource(Res.string.device_field_char_counter, current, max),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End,
    )
}
