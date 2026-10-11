package com.snapshotpb.configuration.common.composable

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.profile_button_decrement
import com.snapshotpb.core.resources.profile_button_increment
import org.jetbrains.compose.resources.stringResource

/**
 * A number field between a decrement and an increment button.
 *
 * The text field keeps its own text draft, apart from the committed [value]. A field that shows
 * only the committed value cannot hold partial text: the user could not clear the field, and a
 * deleted last digit came back at once. So:
 *
 * - Text that parses to a value that [normalize] keeps as it is, commits at once.
 * - Other text (empty, partial, or out of range) stays in the field and commits nothing.
 * - When the field loses focus, or on IME Done, the text commits as [normalize] of its parsed
 *   value, or goes back to [value] when it does not parse. The field then shows that value.
 *
 * A [value] that changes from outside the field (a button, a reload) replaces the text, unless
 * the text already parses to that value. So "10." stays while the user types "10.5".
 *
 * [step] gives the value one button press moves to, from the current value and a delta of -1 or
 * +1. [format] and [parse] convert between the stored value and the text. The field shows [unit]
 * after the number when it is not `null`. The unit is not part of the text.
 */
@Composable
fun NumberStepperField(
    value: Int,
    onValueChange: (Int) -> Unit,
    format: (Int) -> String,
    parse: (String) -> Int?,
    normalize: (Int) -> Int,
    step: (value: Int, delta: Int) -> Int,
    isDiverged: Boolean,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var text by remember { mutableStateOf(format(value)) }
    val focusState = remember { FocusMemory() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(value) {
        if (parse(text) != value) text = format(value)
    }

    fun finishDraft() {
        val finished = finishedDraftValue(text, value, parse, normalize)
        text = format(finished)
        if (finished != value) currentOnValueChange(finished)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        RepeatingIconButton(onClick = { currentOnValueChange(step(value, -1)) }) {
            Text(
                text = stringResource(Res.string.profile_button_decrement),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed
                immediateDraftValue(typed, parse, normalize)
                    ?.takeIf { it != value }
                    ?.let(currentOnValueChange)
            },
            colors = divergenceFieldColors(isDiverged),
            singleLine = true,
            suffix = unit?.let { symbol -> { Text(symbol) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    finishDraft()
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    if (focusState.hadFocus && !state.isFocused) finishDraft()
                    focusState.hadFocus = state.isFocused
                },
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
        )
        RepeatingIconButton(onClick = { currentOnValueChange(step(value, 1)) }) {
            Text(
                text = stringResource(Res.string.profile_button_increment),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Records whether the field had focus, so a focus change can tell a loss of focus. Not state. */
private class FocusMemory {
    var hadFocus: Boolean = false
}

/**
 * The value that typed [text] commits at once, or `null` when the text must stay a draft: it
 * does not parse, or [normalize] would change it (out of range, or in a gap of the range).
 */
internal fun immediateDraftValue(
    text: String,
    parse: (String) -> Int?,
    normalize: (Int) -> Int,
): Int? = parse(text)?.takeIf { normalize(it) == it }

/**
 * The value that the draft [text] commits when the field loses focus: [normalize] of the parsed
 * text, or the committed [value] when the text does not parse.
 */
internal fun finishedDraftValue(
    text: String,
    value: Int,
    parse: (String) -> Int?,
    normalize: (Int) -> Int,
): Int = parse(text)?.let(normalize) ?: value
