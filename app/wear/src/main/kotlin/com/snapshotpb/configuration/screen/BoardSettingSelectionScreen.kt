package com.snapshotpb.configuration.screen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import com.snapshotpb.configuration.model.SCREEN_OFF_DELAY_DEFAULT
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.navigation_back
import org.jetbrains.compose.resources.stringResource

/**
 * Choice of one value for a board setting (the board language, the screen brightness, or the
 * screen-off delay) or for a list-valued profile field (the fire mode or the eye mode). Every one
 * of these is a short list with one active value, so they share a screen rather than repeat it.
 * An unknown fire mode or eye mode is not in [options], so no chip is active for it.
 *
 * The choice stays on the watch until the user saves to the board. The board can refuse a value it
 * does not know, so the stored value tracks what the board reports after the write.
 *
 * [selected] holds no value when the board reported none. The caller shows the card that opens this
 * screen only when the board reported one, so no chip is active only if the board holds a value
 * that [options] does not carry.
 */
@Composable
fun <T> BoardSettingSelectionScreen(
    title: String,
    options: List<T>,
    selected: T?,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onBack: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Text(title, style = MaterialTheme.typography.title3)
            }
        }

        items(options) { option ->
            val isActive = option == selected
            Chip(
                onClick = { onSelect(option) },
                label = { Text(optionLabel(option)) },
                colors = if (isActive) {
                    ChipDefaults.primaryChipColors()
                } else {
                    ChipDefaults.secondaryChipColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
            ) {
                Text(stringResource(Res.string.navigation_back))
            }
        }
    }
}

/**
 * Screen-off delays the watch offers, in seconds. The watch has no keypad, so it offers a short
 * list rather than the whole 1-999 range; the phone gives exact control. The Zepp app offers the
 * same list, so the two watches agree.
 */
val SCREEN_OFF_DELAY_PRESETS = listOf(SCREEN_OFF_DELAY_DEFAULT, 10, 15, 30, 60, 120, 300, 999)

/**
 * The delays to offer for a board that holds [current].
 *
 * The board can report any delay from 1 to 999, and the phone can set one. A value that is not a
 * preset joins the list in order, so the watch shows it as the active choice and the user can
 * return to it after looking at the others. Without this a single tap would lose a delay the watch
 * could not offer again.
 */
fun screenOffDelayOptions(current: Int?): List<Int> =
    if (current == null || current in SCREEN_OFF_DELAY_PRESETS) {
        SCREEN_OFF_DELAY_PRESETS
    } else {
        (SCREEN_OFF_DELAY_PRESETS + current).sorted()
    }
