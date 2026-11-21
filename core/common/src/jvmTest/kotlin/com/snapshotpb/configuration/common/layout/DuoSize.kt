package com.snapshotpb.configuration.common.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app content sizes for iPhone Duo displays, in dp (1 dp = 1 pt). Values marked measured come
 * from the iOS 27.2 simulator (Xcode 27.2, 2026-11-21).
 */
internal enum class DuoSize(val label: String, val width: Dp, val height: Dp) {
    /**
     * Measured on the simulator. The window is 466 x 678 pt. The status bar is a column of about
     * 83 pt at the right edge, so the content is 383 dp wide. The small strips at the top and the
     * bottom are not subtracted.
     */
    OUTER_PORTRAIT("outer-portrait", 383.dp, 678.dp),

    /**
     * PROVISIONAL: the outer display in landscape is not measured. The turned outer window is
     * 678 x 466 pt. This entry assumes that the 83 pt status column stays at the side (595 dp,
     * below the two-pane limit of 600 dp). Together with [OUTER_LANDSCAPE_FULL], it brackets the
     * real value.
     */
    OUTER_LANDSCAPE_SIDE_BAR("outer-landscape-side-bar", 595.dp, 466.dp),

    /**
     * PROVISIONAL: the outer display in landscape is not measured. The turned outer window is
     * 678 x 466 pt. This entry assumes no side inset (678 dp). Together with
     * [OUTER_LANDSCAPE_SIDE_BAR], it brackets the real value.
     */
    OUTER_LANDSCAPE_FULL("outer-landscape-full", 678.dp, 466.dp),

    /**
     * Measured on the simulator. This is the normal state when the user opens the device. The
     * window is 951 x 669 pt. The status bar is a column of about 83 pt at the right edge, so the
     * content is 868 dp wide.
     */
    INNER_OPEN("inner-open", 868.dp, 669.dp),

    /**
     * Measured on the simulator. This state occurs after the user turns the open device with the
     * rotate button of the Xcode Device Hub. The window is 669 x 951 pt. The status bar is a strip
     * at the top; its height is not measured and is not subtracted.
     */
    INNER_ROTATED("inner-rotated", 669.dp, 951.dp),
}
