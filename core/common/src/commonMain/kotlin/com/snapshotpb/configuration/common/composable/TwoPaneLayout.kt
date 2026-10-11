package com.snapshotpb.configuration.common.composable

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The narrowest container that shows two panes side by side. It is the Material 3 width
 * breakpoint between a compact window and a medium window.
 */
internal val TWO_PANE_MIN_WIDTH: Dp = 600.dp

/**
 * True when a screen container of [maxWidth] by [maxHeight] shows two panes side by side. False
 * when it shows one column.
 *
 * A container shows two panes only when it is wider than it is tall and at least
 * [TWO_PANE_MIN_WIDTH] wide. The aspect ratio alone is not sufficient: on a phone, the web app
 * can run in a frame that is 390 dp wide and less than 390 dp tall, and two panes in 390 dp are
 * too narrow for their text.
 */
internal fun fitsTwoPanes(maxWidth: Dp, maxHeight: Dp): Boolean =
    maxWidth > maxHeight && maxWidth >= TWO_PANE_MIN_WIDTH
