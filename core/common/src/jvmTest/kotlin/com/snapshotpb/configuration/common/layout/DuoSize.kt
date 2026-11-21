package com.snapshotpb.configuration.common.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The content areas of iPhone Duo displays in dp (1 dp = 1 pt): measured, simulator, 2026-11-21.
 * Each value is the window less all safe-area insets.
 */
internal enum class DuoSize(val label: String, val width: Dp, val height: Dp) {
    /** Window 466 x 678 pt, insets (0, 17, 84, 34) pt (left, top, right, bottom). */
    OUTER_PORTRAIT("outer-portrait", 382.dp, 627.dp),

    /** Window 678 x 466 pt, insets (84, 17, 0, 34) pt. The status column is at the left edge, and 594 dp is below the 600 dp two-pane limit. */
    OUTER_LANDSCAPE("outer-landscape", 594.dp, 415.dp),

    /** Window 951 x 669 pt, insets (0, 16, 84, 34) pt (left, top, right, bottom). */
    INNER_OPEN("inner-open", 867.dp, 619.dp),

    /** Window 669 x 951 pt, insets (0, 82, 0, 34) pt. This is the state after the user turns the open device. */
    INNER_ROTATED("inner-rotated", 669.dp, 835.dp),
}
