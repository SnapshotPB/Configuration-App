package com.snapshotpb.configuration.common.composable

import androidx.compose.runtime.Composable
import com.snapshotpb.configuration.model.BoardScreenBrightness
import com.snapshotpb.core.resources.Res
import com.snapshotpb.core.resources.board_brightness_default
import com.snapshotpb.core.resources.board_brightness_high
import com.snapshotpb.core.resources.board_brightness_low
import org.jetbrains.compose.resources.stringResource

/**
 * Localized name for a [BoardScreenBrightness] (e.g. "Low" rather than the raw enum name). The
 * protocol carries only the level, so the app supplies the name in the app's own language.
 */
@Composable
fun boardScreenBrightnessLabel(brightness: BoardScreenBrightness): String = stringResource(
    when (brightness) {
        BoardScreenBrightness.LOW -> Res.string.board_brightness_low
        BoardScreenBrightness.DEFAULT -> Res.string.board_brightness_default
        BoardScreenBrightness.HIGH -> Res.string.board_brightness_high
    }
)
